package com.kasa.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.common.Hashes;
import com.kasa.tenant.TenantScope;

/**
 * Proves that a person controls the address they signed up with. Only a hash of the link's token is stored, so a
 * copy of the database cannot verify anyone; the link works once, for a day, and a new one replaces the old.
 */
@Service
public class EmailVerificationService {

    private record Pending(UUID tenantId, UUID userId, String email) {
    }

    private static final Duration LIFETIME = Duration.ofHours(24);

    private final SecureRandom random = new SecureRandom();
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final AuditLog auditLog;
    private final Mailer mailer;
    private final String publicUrl;
    private final boolean required;

    public EmailVerificationService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, AuditLog auditLog, Mailer mailer,
                                    @Value("${kasa.public-url}") String publicUrl,
                                    @Value("${kasa.auth.require-email-verification}") boolean required) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.auditLog = auditLog;
        this.mailer = mailer;
        this.publicUrl = publicUrl;
        this.required = required;
    }

    /**
     * Expects the person's tenant to be bound. Runs in the caller's transaction, so the account and the email
     * that confirms it are committed together: there is never an account whose link was lost.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(UUID tenantId, UUID userId, String email) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = HexFormat.of().formatHex(secret);
        jdbc.sql("UPDATE email_verification_tokens SET used_at = now() WHERE user_id = :id AND used_at IS NULL")
                .param("id", userId)
                .update();
        jdbc.sql("""
                        INSERT INTO email_verification_tokens (token_hash, tenant_id, user_id, email, expires_at)
                        VALUES (:hash, :tenantId, :userId, :email, now() + make_interval(secs => :seconds))
                        """)
                .param("hash", Hashes.sha256Hex(token))
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("email", email)
                .param("seconds", LIFETIME.toSeconds())
                .update();
        mailer.send(email, "Kasa: e-posta adresinizi doğrulayın",
                "Kasa hesabınızı açtığınız için teşekkürler. E-posta adresinizi doğrulamak için bağlantıyı açın:\n"
                        + publicUrl + "/verify-email/" + token + "\n"
                        + "Bağlantı 24 saat geçerlidir. Bu hesabı siz açmadıysanız bu e-postayı dikkate almayın.");
    }

    /** A second click on the same link, or two at once, finds it used: only one conditional update can claim it. */
    public void confirm(String token) {
        String hash = Hashes.sha256Hex(token);
        Pending pending = jdbc.sql("SELECT tenant_id, user_id, email FROM find_email_verification(:hash)")
                .param("hash", hash)
                .query((rs, rowNum) -> new Pending(rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("email")))
                .optional()
                .orElseThrow(EmailVerificationService::gone);
        tx.executeWithoutResult(status -> {
            tenantScope.apply(pending.tenantId());
            int claimed = jdbc.sql("UPDATE email_verification_tokens SET used_at = now() WHERE token_hash = :hash AND used_at IS NULL AND expires_at > now()")
                    .param("hash", hash)
                    .update();
            if (claimed == 0) {
                throw gone();
            }
            // Only the address the link was sent to: if the account's email had changed since, the old link proves nothing.
            jdbc.sql("UPDATE users SET email_verified_at = COALESCE(email_verified_at, now()) WHERE id = :id AND email = :email")
                    .param("id", pending.userId())
                    .param("email", pending.email())
                    .update();
            auditLog.record(pending.tenantId(), AuditLog.Actor.user(pending.userId(), pending.email()), "EMAIL_VERIFIED", "USER",
                    pending.userId(), Map.of());
        });
    }

    public void resend(UUID tenantId, UUID userId) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            record Account(String email, boolean verified) {
            }
            Account account = jdbc.sql("SELECT email, email_verified_at IS NOT NULL AS verified FROM users WHERE id = :id")
                    .param("id", userId)
                    .query((rs, rowNum) -> new Account(rs.getString("email"), rs.getBoolean("verified")))
                    .single();
            if (account.verified()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "this email address is already verified");
            }
            issue(tenantId, userId, account.email());
        });
    }

    /** Expects the tenant to be bound. Says yes when verification is switched off, as it is for most tests. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean ownerVerified() {
        if (!required) {
            return true;
        }
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM users
                                       WHERE role = 'OWNER' AND disabled_at IS NULL AND email_verified_at IS NOT NULL)
                        """)
                .query(Boolean.class)
                .single();
    }

    private static ResponseStatusException gone() {
        return new ResponseStatusException(HttpStatus.GONE, "this link is no longer valid");
    }
}
