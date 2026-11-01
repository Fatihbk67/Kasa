package com.kasa.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.security.JwtService;
import com.kasa.tenant.TenantScope;

@Service
public class AuthService {

    public record TokenResponse(UUID tenantId, String accessToken, long expiresInSeconds) {
    }

    public record Me(UUID userId, String email, String role, UUID tenantId, String tenantName) {
    }

    /** Either a token, or with {@code twoFactorRequired} a challenge token to bring to the second step. */
    public record LoginResponse(UUID tenantId, String accessToken, Long expiresInSeconds, boolean twoFactorRequired,
                                String challengeToken) {
    }

    private record LoginCredentials(UUID userId, UUID tenantId, String passwordHash, int tokenVersion, boolean twoFactor) {
    }

    private static final int BCRYPT_MAX_BYTES = 72;
    private static final int INITIAL_TOKEN_VERSION = 1;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditLog auditLog;
    private final TwoFactorService twoFactor;
    private final String decoyHash;

    public AuthService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope,
                       PasswordEncoder passwordEncoder, JwtService jwtService, AuditLog auditLog, TwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditLog = auditLog;
        this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public TokenResponse register(String businessName, String email, String password) {
        requireFitsBcrypt(password);
        String normalizedEmail = normalize(email);
        // Hashing is deliberately slow, so it happens before a pooled connection is taken.
        String passwordHash = passwordEncoder.encode(password);
        UUID tenantId = UUID.randomUUID();

        try {
            UUID userId = tx.execute(status -> {
                tenantScope.apply(tenantId);
                jdbc.sql("INSERT INTO tenants (id, name) VALUES (:id, :name)")
                        .param("id", tenantId)
                        .param("name", businessName)
                        .update();
                UUID id = jdbc.sql("""
                                INSERT INTO users (tenant_id, email, password_hash, role)
                                VALUES (:tenantId, :email, :passwordHash, 'OWNER')
                                RETURNING id
                                """)
                        .param("tenantId", tenantId)
                        .param("email", normalizedEmail)
                        .param("passwordHash", passwordHash)
                        .query(UUID.class)
                        .single();
                auditLog.record(tenantId, AuditLog.Actor.user(id, normalizedEmail), "TENANT_REGISTERED", "TENANT", tenantId,
                        Map.of("businessName", businessName));
                return id;
            });
            return token(userId, tenantId, INITIAL_TOKEN_VERSION);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email is already registered");
        }
    }

    public LoginResponse login(String email, String password) {
        String normalizedEmail = normalize(email);
        Optional<LoginCredentials> credentials = jdbc.sql("""
                        SELECT user_id, tenant_id, password_hash, token_version, two_factor
                        FROM find_login_credentials(:email)
                        """)
                .param("email", normalizedEmail)
                .query((rs, rowNum) -> new LoginCredentials(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("password_hash"),
                        rs.getInt("token_version"),
                        rs.getBoolean("two_factor")))
                .optional();

        // Unknown emails still pay for a hash comparison, so response time does not reveal which emails exist.
        String hash = credentials.map(LoginCredentials::passwordHash).orElse(decoyHash);
        boolean matches = fitsBcrypt(password) && passwordEncoder.matches(password, hash);
        String challenge = credentials.map(found -> tx.execute(status -> {
            tenantScope.apply(found.tenantId());
            boolean needsCode = matches && found.twoFactor();
            auditLog.record(found.tenantId(), AuditLog.Actor.user(found.userId(), normalizedEmail),
                    !matches ? "LOGIN_FAILED" : needsCode ? "LOGIN_PASSWORD_ACCEPTED" : "LOGIN_SUCCEEDED", "USER", found.userId(), Map.of());
            return needsCode ? twoFactor.startChallenge(found.tenantId(), found.userId(), found.tokenVersion()) : "";
        })).orElse("");
        if (credentials.isEmpty() || !matches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid email or password");
        }
        LoginCredentials found = credentials.get();
        if (found.twoFactor()) {
            return new LoginResponse(null, null, null, true, challenge);
        }
        TokenResponse issued = token(found.userId(), found.tenantId(), found.tokenVersion());
        return new LoginResponse(issued.tenantId(), issued.accessToken(), issued.expiresInSeconds(), false, null);
    }

    public TokenResponse completeLogin(String challengeToken, String code) {
        TwoFactorService.Passed passed = twoFactor.completeChallenge(challengeToken, code);
        return token(passed.userId(), passed.tenantId(), passed.tokenVersion());
    }

    public Me me(UUID tenantId, UUID userId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            return jdbc.sql("""
                            SELECT u.id, u.email, u.role, t.id AS tenant_id, t.name
                            FROM users u
                            JOIN tenants t ON t.id = u.tenant_id
                            WHERE u.id = :id
                            """)
                    .param("id", userId)
                    .query((rs, rowNum) -> new Me(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getString("role"),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("name")))
                    .single();
        });
    }

    public void renameTenant(UUID tenantId, String name) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            jdbc.sql("UPDATE tenants SET name = :name WHERE id = :id")
                    .param("name", name)
                    .param("id", tenantId)
                    .update();
            auditLog.record(tenantId, "TENANT_RENAMED", "TENANT", tenantId, Map.of("name", name));
        });
    }

    /** Raises the token version, which signs out every other session, and returns a new token for this one. */
    public TokenResponse changePassword(UUID tenantId, UUID userId, String currentPassword, String newPassword) {
        requireFitsBcrypt(newPassword);
        String storedHash = tx.execute(status -> {
            tenantScope.apply(tenantId);
            return jdbc.sql("SELECT password_hash FROM users WHERE id = :id")
                    .param("id", userId)
                    .query(String.class)
                    .single();
        });
        if (!fitsBcrypt(currentPassword) || !passwordEncoder.matches(currentPassword, storedHash)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "current password is wrong");
        }
        String newHash = passwordEncoder.encode(newPassword);
        int version = tx.execute(status -> {
            tenantScope.apply(tenantId);
            int updated = jdbc.sql("""
                            UPDATE users SET password_hash = :hash, token_version = token_version + 1
                            WHERE id = :id
                            RETURNING token_version
                            """)
                    .param("hash", newHash)
                    .param("id", userId)
                    .query(Integer.class)
                    .single();
            auditLog.record(tenantId, "PASSWORD_CHANGED", "USER", userId, Map.of());
            return updated;
        });
        return token(userId, tenantId, version);
    }

    public TokenResponse token(UUID userId, UUID tenantId, int tokenVersion) {
        return new TokenResponse(tenantId, jwtService.issue(userId, tenantId, tokenVersion), jwtService.ttlSeconds());
    }

    public static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }

    public static void requireFitsBcrypt(String password) {
        if (!fitsBcrypt(password)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must be at most 72 bytes");
        }
    }

    // Locale.ROOT: under the JVM's Turkish locale "I" would otherwise lowercase to a dotless "ı".
    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
