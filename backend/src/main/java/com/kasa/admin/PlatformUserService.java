package com.kasa.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.auth.Mailer;
import com.kasa.notification.NotificationService;
import com.kasa.tenant.TenantScope;

/**
 * The platform administrator's view of every person on the platform, and the power to change their roles.
 * Each change runs inside the person's own tenant, so row level security still applies to the write, and goes
 * into both the platform's log and the business's own log.
 */
@Service
public class PlatformUserService {

    public record PlatformUser(UUID id, String email, String role, boolean disabled, boolean twoFactor, OffsetDateTime createdAt,
                               UUID tenantId, String tenantName, String tenantStatus, String pendingRequest) {
    }

    public record PlatformRoleRequest(UUID id, UUID userId, String email, UUID tenantId, String tenantName, String currentRole,
                                      String requestedRole, String reason, String status, String decidedBy, String decisionNote,
                                      OffsetDateTime decidedAt, OffsetDateTime createdAt) {
    }

    private static final Set<String> ROLES = Set.of("OWNER", "ADMIN", "VIEWER");
    private static final int LIST_LIMIT = 200;
    private static final AuditLog.Actor PLATFORM = new AuditLog.Actor("SYSTEM", null, "Platform yönetimi");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final AuditLog auditLog;
    private final AdminAccountService accounts;
    private final Mailer mailer;
    private final NotificationService notifications;

    public PlatformUserService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, AuditLog auditLog,
                               AdminAccountService accounts, Mailer mailer, NotificationService notifications) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.auditLog = auditLog;
        this.accounts = accounts;
        this.mailer = mailer;
    }

    public List<PlatformUser> users(String query) {
        // Escaped so a typed % or _ means itself rather than a wildcard.
        String pattern = query == null ? null : query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jdbc.sql("SELECT * FROM platform_users(:query, :limit)")
                .param("query", pattern)
                .param("limit", LIST_LIMIT)
                .query((rs, rowNum) -> new PlatformUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("role"),
                        rs.getBoolean("disabled"),
                        rs.getBoolean("two_factor"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_name"),
                        rs.getString("tenant_status"),
                        rs.getString("pending_request")))
                .list();
    }

    public List<PlatformRoleRequest> requests(String status) {
        return jdbc.sql("SELECT * FROM platform_role_requests(:status, :limit)")
                .param("status", status)
                .param("limit", LIST_LIMIT)
                .query((rs, rowNum) -> new PlatformRoleRequest(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("email"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_name"),
                        rs.getString("from_role"),
                        rs.getString("requested_role"),
                        rs.getString("reason"),
                        rs.getString("status"),
                        rs.getString("decided_by"),
                        rs.getString("decision_note"),
                        rs.getObject("decided_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public void changeRole(String actor, String ip, UUID userId, String role) {
        requireRole(role);
        UUID tenantId = tenantOfUser(userId);
        String email = tx.execute(status -> {
            tenantScope.apply(tenantId);
            return applyRole(tenantId, userId, role);
        });
        accounts.audit(actor, ip, "USER_ROLE_CHANGED", email + " → " + role);
    }

    /** A disabled person is out at once, because every request checks their access in the database. */
    public void setDisabled(String actor, String ip, UUID userId, boolean disabled) {
        UUID tenantId = tenantOfUser(userId);
        String email = tx.execute(status -> {
            tenantScope.apply(tenantId);
            lockOwners();
            Member member = lockMember(userId);
            if (disabled && member.disabled()) {
                throw conflict("this person is already disabled");
            }
            if (!disabled && !member.disabled()) {
                throw conflict("this person is already active");
            }
            if (disabled && member.role().equals("OWNER") && activeOwners() == 1) {
                throw conflict("a business cannot lose its last owner");
            }
            jdbc.sql("UPDATE users SET disabled_at = CASE WHEN :disabled THEN now() END WHERE id = :id")
                    .param("disabled", disabled)
                    .param("id", userId)
                    .update();
            auditLog.record(tenantId, PLATFORM, disabled ? "MEMBER_REMOVED" : "MEMBER_RESTORED", "USER", userId, Map.of("role", member.role()));
            return member.email();
        });
        accounts.audit(actor, ip, disabled ? "USER_DISABLED" : "USER_ENABLED", email);
    }

    /** Approving applies the role in the same transaction, so a request can never read approved without the change. */
    public void decide(String actor, String ip, UUID requestId, boolean approve, String note) {
        UUID tenantId = jdbc.sql("SELECT find_role_request_tenant(:id)")
                .param("id", requestId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request not found"));
        record Decided(String email, String role) {
        }
        Decided decided = tx.execute(status -> {
            tenantScope.apply(tenantId);
            record Open(UUID userId, String requested) {
            }
            Open open = jdbc.sql("SELECT user_id, requested_role FROM role_requests WHERE id = :id AND status = 'PENDING' FOR UPDATE")
                    .param("id", requestId)
                    .query((rs, rowNum) -> new Open(rs.getObject("user_id", UUID.class), rs.getString("requested_role")))
                    .optional()
                    .orElseThrow(() -> conflict("this request has already been decided"));
            // An owner may have granted the role in the meantime. The request is then simply closed as approved.
            Member member = lockMember(open.userId());
            boolean alreadyGranted = member.role().equals(open.requested());
            String email = approve && !alreadyGranted ? applyRole(tenantId, open.userId(), open.requested()) : member.email();
            jdbc.sql("""
                            UPDATE role_requests
                            SET status = :status, decided_by = :actor, decision_note = :note, decided_at = now()
                            WHERE id = :id
                            """)
                    .param("status", approve ? "APPROVED" : "REJECTED")
                    .param("actor", actor)
                    .param("note", note)
                    .param("id", requestId)
                    .update();
            auditLog.record(tenantId, PLATFORM, approve ? "ROLE_REQUEST_APPROVED" : "ROLE_REQUEST_REJECTED", "USER", open.userId(),
                    note == null ? Map.of("to", open.requested()) : Map.of("to", open.requested(), "note", note));
            notifications.toUser(tenantId, open.userId(), approve ? "ROLE_REQUEST_APPROVED" : "ROLE_REQUEST_REJECTED",
                    approve ? "Rol talebiniz onaylandı" : "Rol talebiniz reddedildi",
                    approve ? "Yeni rolünüz: " + open.requested() + "."
                            : (note == null ? "Talep platform yönetimi tarafından reddedildi." : "Not: " + note),
                    "/settings");
            return new Decided(email, open.requested());
        });
        accounts.audit(actor, ip, approve ? "ROLE_REQUEST_APPROVED" : "ROLE_REQUEST_REJECTED", decided.email() + " → " + decided.role());
        mailer.send(decided.email(), "Kasa: rol talebiniz", approve
                ? "Talebiniz onaylandı. Yeni rolünüz: " + decided.role() + "."
                : "Talebiniz reddedildi." + (note == null ? "" : " Not: " + note));
    }

    private record Member(String email, String role, boolean disabled) {
    }

    /**
     * Expects the person's tenant to be bound. Locks every owner first, so two changes at once cannot together
     * leave a business without an owner.
     */
    private String applyRole(UUID tenantId, UUID userId, String role) {
        lockOwners();
        Member member = lockMember(userId);
        if (member.disabled()) {
            throw conflict("this person is disabled");
        }
        if (member.role().equals(role)) {
            throw conflict("this person already has this role");
        }
        if (member.role().equals("OWNER") && activeOwners() == 1) {
            throw conflict("a business cannot lose its last owner");
        }
        jdbc.sql("UPDATE users SET role = :role WHERE id = :id")
                .param("role", role)
                .param("id", userId)
                .update();
        auditLog.record(tenantId, PLATFORM, "MEMBER_ROLE_CHANGED", "USER", userId, Map.of("from", member.role(), "to", role));
        return member.email();
    }

    private void lockOwners() {
        jdbc.sql("SELECT id FROM users WHERE role = 'OWNER' AND disabled_at IS NULL ORDER BY id FOR UPDATE").query(UUID.class).list();
    }

    private int activeOwners() {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'OWNER' AND disabled_at IS NULL").query(Integer.class).single();
    }

    private Member lockMember(UUID userId) {
        return jdbc.sql("SELECT email, role, disabled_at IS NOT NULL AS disabled FROM users WHERE id = :id FOR UPDATE")
                .param("id", userId)
                .query((rs, rowNum) -> new Member(rs.getString("email"), rs.getString("role"), rs.getBoolean("disabled")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "person not found"));
    }

    private UUID tenantOfUser(UUID userId) {
        return jdbc.sql("SELECT find_user_tenant(:id)")
                .param("id", userId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "person not found"));
    }

    private static void requireRole(String role) {
        if (!ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown role");
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
