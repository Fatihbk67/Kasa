package com.kasa.admin;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.auth.AuthService;
import com.kasa.security.JwtService;
import com.kasa.security.SecretCipher;
import com.kasa.security.Totp;

/**
 * Personal accounts for the people who run the platform, so the log says who did something rather than
 * "whoever had the token". The shared token stays for machines, such as Prometheus, and to create the first account.
 */
@Service
public class AdminAccountService {

    public record AdminView(UUID id, String email, boolean disabled, OffsetDateTime createdAt) {
    }

    /** The only time the authenticator secret is available. */
    public record CreatedAdmin(UUID id, String email, String totpSecret, String enrollmentUri) {
    }

    public record AdminToken(String accessToken, long expiresInSeconds) {
    }

    public record AuditEntry(long id, OffsetDateTime createdAt, String actor, String action, String target, String ip) {
    }

    private record Stored(UUID id, String passwordHash, String secret, long lastStep, int failures, boolean locked, int tokenVersion) {
    }

    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final int AUDIT_PAGE = 100;
    private static final String ISSUER = "Kasa Yonetim";

    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwordEncoder;
    private final SecretCipher cipher;
    private final JwtService jwtService;
    private final String decoyHash;

    public AdminAccountService(JdbcClient jdbc, TransactionTemplate tx, PasswordEncoder passwordEncoder, SecretCipher cipher, JwtService jwtService) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
        this.jwtService = jwtService;
        this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public CreatedAdmin create(String actor, String ip, String email, String password) {
        AuthService.requireFitsBcrypt(password);
        String normalized = AuthService.normalize(email);
        String hash = passwordEncoder.encode(password);
        UUID id = UUID.randomUUID();
        String secret = Totp.newSecret();
        try {
            tx.executeWithoutResult(status -> {
                jdbc.sql("""
                                INSERT INTO platform_admins (id, email, password_hash, totp_secret)
                                VALUES (:id, :email, :hash, :secret)
                                """)
                        .param("id", id)
                        .param("email", normalized)
                        .param("hash", hash)
                        .param("secret", cipher.encrypt(context(id), secret))
                        .update();
                audit(actor, ip, "ADMIN_CREATED", normalized);
            });
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this email already has an administrator account");
        }
        return new CreatedAdmin(id, normalized, secret, Totp.enrollmentUri(secret, normalized, ISSUER));
    }

    public List<AdminView> list() {
        return jdbc.sql("SELECT id, email, disabled_at, created_at FROM platform_admins ORDER BY created_at, id")
                .query((rs, rowNum) -> new AdminView(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getObject("disabled_at", OffsetDateTime.class) != null, rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    /**
     * The password step of the shared login page. Always pays for one hash comparison, so a merchant's login is
     * not faster or slower depending on whether their email also belongs to an administrator. A wrong password is
     * logged only for a real account: every merchant login passes through here too.
     */
    public Optional<String> startLogin(String ip, String email, String password) {
        String normalized = AuthService.normalize(email);
        Optional<Stored> found = find(normalized);
        String hash = found.map(Stored::passwordHash).orElse(decoyHash);
        boolean passwordOk = AuthService.fitsBcrypt(password) && passwordEncoder.matches(password, hash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (!passwordOk) {
            tx.executeWithoutResult(status -> audit(normalized, ip, "ADMIN_LOGIN_FAILED", null));
            return Optional.empty();
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = java.util.HexFormat.of().formatHex(secret);
        tx.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO admin_login_challenges (token_hash, admin_id, token_version, expires_at)
                        VALUES (:hash, :id, :version, now() + interval '5 minutes')
                        """)
                .param("hash", com.kasa.common.Hashes.sha256Hex(token))
                .param("id", found.get().id())
                .param("version", found.get().tokenVersion())
                .update());
        return Optional.of(token);
    }

    /** The code step. A wrong code leaves the challenge usable, so a typo does not send the person back to the start. */
    public AdminToken completeLogin(String ip, String challengeToken, String code) {
        String hash = com.kasa.common.Hashes.sha256Hex(challengeToken);
        record Challenge(UUID adminId, int tokenVersion, String email) {
        }
        Challenge challenge = jdbc.sql("""
                        SELECT c.admin_id, c.token_version, a.email
                        FROM admin_login_challenges c JOIN platform_admins a ON a.id = c.admin_id
                        WHERE c.token_hash = :hash AND c.used_at IS NULL AND c.expires_at > now() AND a.disabled_at IS NULL
                        """)
                .param("hash", hash)
                .query((rs, rowNum) -> new Challenge(rs.getObject("admin_id", UUID.class), rs.getInt("token_version"), rs.getString("email")))
                .optional()
                .orElseThrow(AdminAccountService::rejected);
        Integer version = tx.execute(status -> {
            // Marked used under the admin's lock, so the same code sent twice at once opens one session.
            int claimed = jdbc.sql("UPDATE admin_login_challenges SET used_at = now() WHERE token_hash = :hash AND used_at IS NULL")
                    .param("hash", hash)
                    .update();
            if (claimed == 0) {
                return null;
            }
            Integer passed = checkCode(challenge.adminId(), code, challenge.email(), ip);
            if (passed == null || passed != challenge.tokenVersion()) {
                jdbc.sql("UPDATE admin_login_challenges SET used_at = NULL WHERE token_hash = :hash")
                        .param("hash", hash)
                        .update();
                return null;
            }
            return passed;
        });
        if (version == null) {
            throw rejected();
        }
        return new AdminToken(jwtService.issueAdmin(challenge.adminId(), version), jwtService.ttlSeconds());
    }

    /** Raising the token version ends the account's open sessions at once. */
    public void disable(String actor, String ip, UUID id, UUID actingAdminId) {
        if (id.equals(actingAdminId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "you cannot disable your own account");
        }
        tx.executeWithoutResult(status -> {
            String email = jdbc.sql("""
                            UPDATE platform_admins SET disabled_at = now(), token_version = token_version + 1
                            WHERE id = :id AND disabled_at IS NULL
                            RETURNING email
                            """)
                    .param("id", id)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "active administrator not found"));
            audit(actor, ip, "ADMIN_DISABLED", email);
        });
    }

    public void audit(String actor, String ip, String action, String target) {
        jdbc.sql("INSERT INTO admin_audit_log (actor, action, target, ip) VALUES (:actor, :action, :target, :ip)")
                .param("actor", actor)
                .param("action", action)
                .param("target", target)
                .param("ip", ip)
                .update();
    }

    public List<AuditEntry> auditLog() {
        return jdbc.sql("SELECT * FROM admin_audit_log ORDER BY id DESC LIMIT :limit")
                .param("limit", AUDIT_PAGE)
                .query((rs, rowNum) -> new AuditEntry(rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("actor"), rs.getString("action"), rs.getString("target"), rs.getString("ip")))
                .list();
    }

    /** Commits its failure count by returning null instead of throwing, which would roll the count back. */
    private Integer checkCode(UUID id, String code, String email, String ip) {
        Stored admin = jdbc.sql("""
                        SELECT id, password_hash, totp_secret, totp_last_step, totp_failures,
                               COALESCE(totp_locked_until > now(), false) AS locked, token_version
                        FROM platform_admins WHERE id = :id FOR UPDATE
                        """)
                .param("id", id)
                .query((rs, rowNum) -> mapStored(rs))
                .single();
        if (admin.locked()) {
            audit(email, ip, "ADMIN_LOGIN_LOCKED", null);
            return null;
        }
        long step = Totp.matchingStep(cipher.decrypt(context(id), admin.secret()), code, admin.lastStep(), Totp.currentStep());
        if (step >= 0) {
            jdbc.sql("UPDATE platform_admins SET totp_last_step = :step, totp_failures = 0, totp_locked_until = NULL WHERE id = :id")
                    .param("step", step)
                    .param("id", id)
                    .update();
            audit(email, ip, "ADMIN_LOGIN", null);
            return admin.tokenVersion();
        }
        int failures = admin.failures() + 1;
        boolean lock = failures >= MAX_FAILURES;
        jdbc.sql("""
                        UPDATE platform_admins
                        SET totp_failures = :failures, totp_locked_until = CASE WHEN :lock THEN now() + make_interval(secs => :seconds) END
                        WHERE id = :id
                        """)
                .param("failures", lock ? 0 : failures)
                .param("lock", lock)
                .param("seconds", LOCK_DURATION.toSeconds())
                .param("id", id)
                .update();
        audit(email, ip, "ADMIN_LOGIN_FAILED", null);
        return null;
    }

    private Optional<Stored> find(String email) {
        return jdbc.sql("""
                        SELECT id, password_hash, totp_secret, totp_last_step, totp_failures,
                               COALESCE(totp_locked_until > now(), false) AS locked, token_version
                        FROM platform_admins WHERE email = :email AND disabled_at IS NULL
                        """)
                .param("email", email)
                .query((rs, rowNum) -> mapStored(rs))
                .optional();
    }

    private static Stored mapStored(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stored(rs.getObject("id", UUID.class), rs.getString("password_hash"), rs.getString("totp_secret"),
                rs.getLong("totp_last_step"), rs.getInt("totp_failures"), rs.getBoolean("locked"), rs.getInt("token_version"));
    }

    private static String context(UUID id) {
        return "admin-totp:" + id;
    }

    private static ResponseStatusException rejected() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
    }
}
