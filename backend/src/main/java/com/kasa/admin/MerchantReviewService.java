package com.kasa.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.auth.Mailer;
import com.kasa.tenant.TenantScope;

/**
 * The platform's check on who may take money. Each decision goes into both logs: the platform's, which says which
 * administrator decided, and the merchant's own, so the merchant can see why their account changed.
 */
@Service
public class MerchantReviewService {

    public record Merchant(UUID id, String name, String status, String statusReason, OffsetDateTime createdAt,
                           OffsetDateTime reviewedAt, String reviewedBy, String ownerEmail, long memberCount,
                           long paymentCount, long captured) {
    }

    private record Change(String to, List<String> from, String auditAction) {
    }

    private static final int LIST_LIMIT = 200;
    private static final Change APPROVE = new Change("ACTIVE", List.of("PENDING"), "MERCHANT_APPROVED");
    private static final Change SUSPEND = new Change("SUSPENDED", List.of("PENDING", "ACTIVE"), "MERCHANT_SUSPENDED");
    private static final Change REACTIVATE = new Change("ACTIVE", List.of("SUSPENDED"), "MERCHANT_REACTIVATED");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final AuditLog auditLog;
    private final AdminAccountService accounts;
    private final Mailer mailer;

    public MerchantReviewService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, AuditLog auditLog,
                                 AdminAccountService accounts, Mailer mailer) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.auditLog = auditLog;
        this.accounts = accounts;
        this.mailer = mailer;
    }

    public List<Merchant> list(String status) {
        return jdbc.sql("SELECT * FROM platform_merchants(:status, :limit)")
                .param("status", status)
                .param("limit", LIST_LIMIT)
                .query((rs, rowNum) -> new Merchant(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getString("status_reason"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("reviewed_at", OffsetDateTime.class),
                        rs.getString("reviewed_by"),
                        rs.getString("owner_email"),
                        rs.getLong("member_count"),
                        rs.getLong("payment_count"),
                        rs.getLong("captured")))
                .list();
    }

    public void approve(String actor, String ip, UUID tenantId) {
        change(actor, ip, tenantId, APPROVE, null);
    }

    /** Stops new payments and payouts at once. Refunds, disputes and the dashboard keep working. */
    public void suspend(String actor, String ip, UUID tenantId, String reason) {
        change(actor, ip, tenantId, SUSPEND, reason);
    }

    public void reactivate(String actor, String ip, UUID tenantId) {
        change(actor, ip, tenantId, REACTIVATE, null);
    }

    private void change(String actor, String ip, UUID tenantId, Change change, String reason) {
        record Updated(String name, String ownerEmail) {
        }
        Updated updated = tx.execute(status -> {
            tenantScope.apply(tenantId);
            String name = jdbc.sql("""
                            UPDATE tenants
                            SET status = :to, status_reason = :reason, reviewed_at = now(), reviewed_by = :actor
                            WHERE id = :id AND status IN (:from)
                            RETURNING name
                            """)
                    .param("to", change.to())
                    .param("reason", reason)
                    .param("actor", actor)
                    .param("id", tenantId)
                    .param("from", change.from())
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "the merchant is not in a state that allows this"));
            auditLog.record(tenantId, new AuditLog.Actor("SYSTEM", null, "Platform yönetimi"), change.auditAction(), "TENANT", tenantId,
                    reason == null ? Map.of() : Map.of("reason", reason));
            accounts.audit(actor, ip, change.auditAction(), name);
            String owner = jdbc.sql("SELECT email FROM users WHERE role = 'OWNER' AND disabled_at IS NULL ORDER BY created_at LIMIT 1")
                    .query(String.class)
                    .optional()
                    .orElse(null);
            return new Updated(name, owner);
        });
        if (updated.ownerEmail() != null) {
            mailer.send(updated.ownerEmail(), "Kasa hesap durumu", switch (change.auditAction()) {
                case "MERCHANT_SUSPENDED" -> updated.name() + " hesabı askıya alındı. Gerekçe: " + reason;
                default -> updated.name() + " hesabı onaylandı. Artık ödeme alabilirsiniz.";
            });
        }
    }
}
