package com.kasa.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.common.Hashes;
import com.kasa.tenant.TenantScope;

/**
 * Resetting leaves two-factor login on: someone who reached the mailbox should still need the phone. The link
 * is built from configuration, never from the request's Host header, which an attacker could set to their own site.
 */
@Service
public class PasswordResetService {

    private record Reset(UUID tenantId, UUID userId) {
    }

    private record Account(UUID userId, UUID tenantId) {
    }

    private static final Duration LIFETIME = Duration.ofMinutes(30);

    private final SecureRandom random = new SecureRandom();
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final PasswordEncoder passwordEncoder;
    private final AuditLog auditLog;
    private final Mailer mailer;
    private final String publicUrl;

    public PasswordResetService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, PasswordEncoder passwordEncoder,
                                AuditLog auditLog, Mailer mailer, @Value("${kasa.public-url}") String publicUrl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.passwordEncoder = passwordEncoder;
        this.auditLog = auditLog;
        this.mailer = mailer;
        this.publicUrl = publicUrl;
    }

    /** Behaves the same for an unknown email, so this form cannot be used to find out who has an account. */
    public void request(String email) {
        String normalized = AuthService.normalize(email);
        jdbc.sql("SELECT user_id, tenant_id FROM find_login_credentials(:email)")
                .param("email", normalized)
                .query((rs, rowNum) -> new Account(rs.getObject("user_id", UUID.class), rs.getObject("tenant_id", UUID.class)))
                .optional()
                .ifPresent(account -> {
                    String token = issue(account);
                    mailer.send(normalized, "Kasa parola sıfırlama",
                            "Parolanızı sıfırlamak için: " + publicUrl + "/reset-password/" + token + "\n"
                                    + "Bağlantı 30 dakika geçerlidir. Bu isteği siz yapmadıysanız dikkate almayın.");
                });
    }

    /** Raising the token version signs out every session, which is the point if the old password was stolen. */
    public void confirm(String token, String newPassword) {
        AuthService.requireFitsBcrypt(newPassword);
        String hash = Hashes.sha256Hex(token);
        Reset reset = jdbc.sql("SELECT tenant_id, user_id FROM find_password_reset(:hash)")
                .param("hash", hash)
                .query((rs, rowNum) -> new Reset(rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class)))
                .optional()
                .orElseThrow(PasswordResetService::gone);
        // Hashing is deliberately slow, so it happens before a pooled connection is taken.
        String newHash = passwordEncoder.encode(newPassword);
        tx.executeWithoutResult(status -> {
            tenantScope.apply(reset.tenantId());
            // The conditional update lets only one of two simultaneous attempts use the link.
            int claimed = jdbc.sql("UPDATE password_reset_tokens SET used_at = now() WHERE token_hash = :hash AND used_at IS NULL AND expires_at > now()")
                    .param("hash", hash)
                    .update();
            if (claimed == 0) {
                throw gone();
            }
            jdbc.sql("UPDATE users SET password_hash = :hash, token_version = token_version + 1 WHERE id = :id AND disabled_at IS NULL")
                    .param("hash", newHash)
                    .param("id", reset.userId())
                    .update();
            auditLog.record(reset.tenantId(), AuditLog.Actor.SYSTEM, "PASSWORD_RESET", "USER", reset.userId(), Map.of());
        });
    }

    private String issue(Account account) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = HexFormat.of().formatHex(secret);
        tx.executeWithoutResult(status -> {
            tenantScope.apply(account.tenantId());
            // Only the newest link works, so an old email cannot be dug out and used later.
            jdbc.sql("UPDATE password_reset_tokens SET used_at = now() WHERE user_id = :id AND used_at IS NULL")
                    .param("id", account.userId())
                    .update();
            jdbc.sql("""
                            INSERT INTO password_reset_tokens (token_hash, tenant_id, user_id, expires_at)
                            VALUES (:hash, :tenantId, :userId, :expires)
                            """)
                    .param("hash", Hashes.sha256Hex(token))
                    .param("tenantId", account.tenantId())
                    .param("userId", account.userId())
                    .param("expires", OffsetDateTime.now(ZoneOffset.UTC).plus(LIFETIME))
                    .update();
            auditLog.record(account.tenantId(), AuditLog.Actor.SYSTEM, "PASSWORD_RESET_REQUESTED", "USER", account.userId(), Map.of());
        });
        return token;
    }

    private static ResponseStatusException gone() {
        return new ResponseStatusException(HttpStatus.GONE, "this link is no longer valid");
    }
}
