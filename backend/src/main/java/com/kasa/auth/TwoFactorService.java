package com.kasa.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.common.Hashes;
import com.kasa.security.SecretCipher;
import com.kasa.security.Totp;
import com.kasa.tenant.TenantScope;

/**
 * The second login step. Failures are counted on the user's row rather than in memory, because six digits can be
 * guessed: the limit has to hold across restarts and instances, and a new password attempt must not reset it.
 */
@Service
public class TwoFactorService {

    public record Status(boolean enabled, int recoveryCodesLeft) {
    }

    public record Setup(String secret, String enrollmentUri) {
    }

    /** The only time recovery codes are available: just their hashes are stored. */
    public record EnabledResult(List<String> recoveryCodes, int tokenVersion) {
    }

    public record Passed(UUID userId, UUID tenantId, int tokenVersion) {
    }

    private record Challenge(UUID tenantId, UUID userId, int tokenVersion) {
    }

    private record Guarded(String email, String secret, long lastStep, int failures, boolean locked, boolean disabled,
                           int tokenVersion) {
    }

    private enum Verdict { OK, WRONG, LOCKED }

    private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_FAILURES = 10;
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final String ISSUER = "Kasa";

    private final SecureRandom random = new SecureRandom();
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final PasswordEncoder passwordEncoder;
    private final SecretCipher cipher;
    private final AuditLog auditLog;

    public TwoFactorService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, PasswordEncoder passwordEncoder,
                            SecretCipher cipher, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
        this.auditLog = auditLog;
    }

    public Status status(UUID tenantId, UUID userId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            boolean enabled = jdbc.sql("SELECT totp_enabled_at IS NOT NULL FROM users WHERE id = :id")
                    .param("id", userId)
                    .query(Boolean.class)
                    .single();
            int left = jdbc.sql("SELECT COUNT(*) FROM recovery_codes WHERE user_id = :id AND used_at IS NULL")
                    .param("id", userId)
                    .query(Integer.class)
                    .single();
            return new Status(enabled, left);
        });
    }

    /** Asks for the password so a session left open on a shared computer cannot quietly attach an attacker's phone. */
    public Setup beginSetup(UUID tenantId, UUID userId, String password) {
        requirePassword(tenantId, userId, password);
        String secret = Totp.newSecret();
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            Guarded user = lockUser(userId);
            if (isEnabled(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "two-factor login is already on");
            }
            jdbc.sql("UPDATE users SET totp_secret = :secret WHERE id = :id")
                    .param("secret", cipher.encrypt(context(userId), secret))
                    .param("id", userId)
                    .update();
            return new Setup(secret, Totp.enrollmentUri(secret, user.email(), ISSUER));
        });
    }

    /** Turns on only after a correct code, which proves the app holds the secret before anyone can be locked out. */
    public EnabledResult enable(UUID tenantId, UUID userId, String code) {
        List<String> codes = new ArrayList<>();
        Integer version = tx.execute(status -> {
            tenantScope.apply(tenantId);
            Guarded user = lockUser(userId);
            if (isEnabled(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "two-factor login is already on");
            }
            if (user.secret() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "start the setup first");
            }
            long step = Totp.matchingStep(cipher.decrypt(context(userId), user.secret()), code, user.lastStep(), Totp.currentStep());
            if (step < 0) {
                return null;
            }
            for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
                String recoveryCode = newRecoveryCode();
                codes.add(recoveryCode);
                jdbc.sql("INSERT INTO recovery_codes (tenant_id, user_id, code_hash) VALUES (:tenantId, :userId, :hash)")
                        .param("tenantId", tenantId)
                        .param("userId", userId)
                        .param("hash", hashRecoveryCode(recoveryCode))
                        .update();
            }
            auditLog.record(tenantId, "TWO_FACTOR_ENABLED", "USER", userId, Map.of());
            // Sessions opened before this point passed only the password, so they end here.
            return jdbc.sql("""
                            UPDATE users
                            SET totp_enabled_at = now(), totp_last_step = :step, totp_failures = 0, token_version = token_version + 1
                            WHERE id = :id
                            RETURNING token_version
                            """)
                    .param("step", step)
                    .param("id", userId)
                    .query(Integer.class)
                    .single();
        });
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "the code is not correct");
        }
        return new EnabledResult(codes, version);
    }

    /** Needs both factors, so neither a stolen password nor an unlocked phone is enough to remove the protection. */
    public int disable(UUID tenantId, UUID userId, String password, String code) {
        requirePassword(tenantId, userId, password);
        Integer version = tx.execute(status -> {
            tenantScope.apply(tenantId);
            Guarded user = lockUser(userId);
            if (!isEnabled(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "two-factor login is not on");
            }
            if (Totp.matchingStep(cipher.decrypt(context(userId), user.secret()), code, user.lastStep(), Totp.currentStep()) < 0) {
                return null;
            }
            jdbc.sql("UPDATE recovery_codes SET used_at = now() WHERE user_id = :id AND used_at IS NULL")
                    .param("id", userId)
                    .update();
            auditLog.record(tenantId, "TWO_FACTOR_DISABLED", "USER", userId, Map.of());
            return jdbc.sql("""
                            UPDATE users
                            SET totp_secret = NULL, totp_enabled_at = NULL, totp_last_step = 0, totp_failures = 0,
                                totp_locked_until = NULL, token_version = token_version + 1
                            WHERE id = :id
                            RETURNING token_version
                            """)
                    .param("id", userId)
                    .query(Integer.class)
                    .single();
        });
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "the code is not correct");
        }
        return version;
    }

    /** Runs in the login's transaction. The returned token proves the password step passed and opens nothing else. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String startChallenge(UUID tenantId, UUID userId, int tokenVersion) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = HexFormat.of().formatHex(secret);
        jdbc.sql("""
                        INSERT INTO login_challenges (token_hash, tenant_id, user_id, token_version, expires_at)
                        VALUES (:hash, :tenantId, :userId, :version, :expires)
                        """)
                .param("hash", Hashes.sha256Hex(token))
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("version", tokenVersion)
                .param("expires", OffsetDateTime.now(ZoneOffset.UTC).plus(CHALLENGE_LIFETIME))
                .update();
        return token;
    }

    /** The outcome commits before any error is raised, or a wrong guess would roll back its own failure count. */
    public Passed completeChallenge(String challengeToken, String code) {
        String hash = Hashes.sha256Hex(challengeToken);
        Challenge challenge = jdbc.sql("SELECT tenant_id, user_id, token_version FROM find_login_challenge(:hash)")
                .param("hash", hash)
                .query((rs, rowNum) -> new Challenge(rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getInt("token_version")))
                .optional()
                .orElseThrow(TwoFactorService::rejected);
        Verdict verdict = tx.execute(status -> {
            tenantScope.apply(challenge.tenantId());
            return verify(challenge, hash, code);
        });
        return switch (verdict) {
            case OK -> new Passed(challenge.userId(), challenge.tenantId(), challenge.tokenVersion());
            case LOCKED -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many wrong codes; try again later");
            case WRONG -> throw rejected();
        };
    }

    private Verdict verify(Challenge challenge, String challengeHash, String code) {
        UUID userId = challenge.userId();
        Guarded user = lockUser(userId);
        if (user.disabled() || user.tokenVersion() != challenge.tokenVersion() || user.secret() == null) {
            return Verdict.WRONG;
        }
        if (user.locked()) {
            return Verdict.LOCKED;
        }
        // Checked under the user's lock: two requests with the same code must not both get in.
        boolean unused = jdbc.sql("UPDATE login_challenges SET used_at = now() WHERE token_hash = :hash AND used_at IS NULL")
                .param("hash", challengeHash)
                .update() == 1;
        if (!unused) {
            return Verdict.WRONG;
        }
        Optional<String> method = checkCode(userId, user, code);
        if (method.isPresent()) {
            auditLog.record(challenge.tenantId(), AuditLog.Actor.user(userId, user.email()), "LOGIN_SUCCEEDED", "USER", userId,
                    Map.of("secondFactor", method.get()));
            return Verdict.OK;
        }
        // A wrong code must not burn the challenge, or a typo would send the person back to the password step.
        jdbc.sql("UPDATE login_challenges SET used_at = NULL WHERE token_hash = :hash")
                .param("hash", challengeHash)
                .update();
        int failures = user.failures() + 1;
        boolean lock = failures >= MAX_FAILURES;
        jdbc.sql("""
                        UPDATE users
                        SET totp_failures = :failures, totp_locked_until = CASE WHEN :lock THEN now() + make_interval(secs => :seconds) END
                        WHERE id = :id
                        """)
                .param("failures", lock ? 0 : failures)
                .param("lock", lock)
                .param("seconds", LOCK_DURATION.toSeconds())
                .param("id", userId)
                .update();
        auditLog.record(challenge.tenantId(), AuditLog.Actor.user(userId, user.email()), lock ? "TWO_FACTOR_LOCKED" : "LOGIN_FAILED",
                "USER", userId, Map.of("secondFactor", "wrong"));
        return lock ? Verdict.LOCKED : Verdict.WRONG;
    }

    private Optional<String> checkCode(UUID userId, Guarded user, String code) {
        long step = Totp.matchingStep(cipher.decrypt(context(userId), user.secret()), code, user.lastStep(), Totp.currentStep());
        if (step >= 0) {
            jdbc.sql("UPDATE users SET totp_last_step = :step, totp_failures = 0, totp_locked_until = NULL WHERE id = :id")
                    .param("step", step)
                    .param("id", userId)
                    .update();
            return Optional.of("app");
        }
        int spent = jdbc.sql("UPDATE recovery_codes SET used_at = now() WHERE user_id = :id AND code_hash = :hash AND used_at IS NULL")
                .param("id", userId)
                .param("hash", hashRecoveryCode(code))
                .update();
        if (spent == 1) {
            jdbc.sql("UPDATE users SET totp_failures = 0, totp_locked_until = NULL WHERE id = :id")
                    .param("id", userId)
                    .update();
            return Optional.of("recovery");
        }
        return Optional.empty();
    }

    private boolean isEnabled(UUID userId) {
        return jdbc.sql("SELECT totp_enabled_at IS NOT NULL FROM users WHERE id = :id")
                .param("id", userId)
                .query(Boolean.class)
                .single();
    }

    private Guarded lockUser(UUID userId) {
        return jdbc.sql("""
                        SELECT email, totp_secret, totp_last_step, totp_failures,
                               COALESCE(totp_locked_until > now(), false) AS locked, disabled_at IS NOT NULL AS disabled, token_version
                        FROM users WHERE id = :id FOR UPDATE
                        """)
                .param("id", userId)
                .query((rs, rowNum) -> new Guarded(rs.getString("email"), rs.getString("totp_secret"), rs.getLong("totp_last_step"),
                        rs.getInt("totp_failures"), rs.getBoolean("locked"), rs.getBoolean("disabled"), rs.getInt("token_version")))
                .single();
    }

    private void requirePassword(UUID tenantId, UUID userId, String password) {
        String hash = tx.execute(status -> {
            tenantScope.apply(tenantId);
            return jdbc.sql("SELECT password_hash FROM users WHERE id = :id")
                    .param("id", userId)
                    .query(String.class)
                    .single();
        });
        if (!AuthService.fitsBcrypt(password) || !passwordEncoder.matches(password, hash)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "the password is not correct");
        }
    }

    private String newRecoveryCode() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        return hex.substring(0, 8) + "-" + hex.substring(8);
    }

    // Plain SHA-256 is enough here: unlike a password, a 64-bit random code cannot be guessed from a dictionary.
    private static String hashRecoveryCode(String code) {
        return Hashes.sha256Hex(code.trim().toLowerCase(Locale.ROOT));
    }

    private static String context(UUID userId) {
        return "user-totp:" + userId;
    }

    private static ResponseStatusException rejected() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "the code is not correct");
    }
}
