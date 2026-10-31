package com.kasa.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.security.JwtService;
import com.kasa.tenant.TenantScope;

@Service
public class AuthService {

    public record TokenResponse(UUID tenantId, String accessToken, long expiresInSeconds) {
    }

    private record LoginCredentials(UUID userId, UUID tenantId, String passwordHash) {
    }

    private static final int BCRYPT_MAX_BYTES = 72;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String decoyHash;

    public AuthService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public TokenResponse register(String businessName, String email, String password) {
        if (!fitsBcrypt(password)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must be at most 72 bytes");
        }
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
                return jdbc.sql("""
                                INSERT INTO users (tenant_id, email, password_hash)
                                VALUES (:tenantId, :email, :passwordHash)
                                RETURNING id
                                """)
                        .param("tenantId", tenantId)
                        .param("email", normalizedEmail)
                        .param("passwordHash", passwordHash)
                        .query(UUID.class)
                        .single();
            });
            return token(userId, tenantId);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email is already registered");
        }
    }

    public TokenResponse login(String email, String password) {
        Optional<LoginCredentials> credentials = jdbc.sql("""
                        SELECT user_id, tenant_id, password_hash
                        FROM find_login_credentials(:email)
                        """)
                .param("email", normalize(email))
                .query((rs, rowNum) -> new LoginCredentials(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("password_hash")))
                .optional();

        // Unknown emails still pay for a hash comparison, so response time does not reveal which emails exist.
        String hash = credentials.map(LoginCredentials::passwordHash).orElse(decoyHash);
        boolean matches = fitsBcrypt(password) && passwordEncoder.matches(password, hash);
        if (credentials.isEmpty() || !matches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid email or password");
        }
        return token(credentials.get().userId(), credentials.get().tenantId());
    }

    private TokenResponse token(UUID userId, UUID tenantId) {
        return new TokenResponse(tenantId, jwtService.issue(userId, tenantId), jwtService.ttlSeconds());
    }

    private static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }

    // Locale.ROOT: under the JVM's Turkish locale "I" would otherwise lowercase to a dotless "ı".
    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
