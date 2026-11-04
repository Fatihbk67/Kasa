package com.kasa.team;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.auth.Mailer;
import com.kasa.tenant.TenantScope;

/** A member's side of asking for a different role. The platform administrator decides in the admin panel. */
@Service
public class RoleRequestService {

    public record RoleRequest(UUID id, String currentRole, String requestedRole, String reason, String status,
                              String decisionNote, OffsetDateTime decidedAt, OffsetDateTime createdAt) {
    }

    private static final Set<String> ROLES = Set.of("OWNER", "ADMIN", "VIEWER");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final AuditLog auditLog;
    private final Mailer mailer;

    public RoleRequestService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, AuditLog auditLog, Mailer mailer) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.auditLog = auditLog;
        this.mailer = mailer;
    }

    /** Lets every active administrator know, because a request nobody sees is a request nobody answers. */
    public RoleRequest submit(UUID tenantId, UUID userId, String requestedRole, String reason) {
        if (!ROLES.contains(requestedRole)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown role");
        }
        RoleRequest created;
        String email;
        try {
            record Created(RoleRequest request, String email) {
            }
            Created result = tx.execute(status -> {
                tenantScope.apply(tenantId);
                record Member(String role, String email) {
                }
                Member member = jdbc.sql("SELECT role, email FROM users WHERE id = :id AND disabled_at IS NULL")
                        .param("id", userId)
                        .query((rs, rowNum) -> new Member(rs.getString("role"), rs.getString("email")))
                        .single();
                if (member.role().equals(requestedRole)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "you already have this role");
                }
                UUID id = jdbc.sql("""
                                INSERT INTO role_requests (tenant_id, user_id, from_role, requested_role, reason)
                                VALUES (:tenantId, :userId, :current, :requested, :reason)
                                RETURNING id
                                """)
                        .param("tenantId", tenantId)
                        .param("userId", userId)
                        .param("current", member.role())
                        .param("requested", requestedRole)
                        .param("reason", reason)
                        .query(UUID.class)
                        .single();
                auditLog.record(tenantId, "ROLE_REQUESTED", "USER", userId, Map.of("from", member.role(), "to", requestedRole));
                return new Created(find(id), member.email());
            });
            created = result.request();
            email = result.email();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "you already have an open request");
        }
        List<String> admins = jdbc.sql("SELECT email FROM platform_admins WHERE disabled_at IS NULL").query(String.class).list();
        admins.forEach(admin -> mailer.send(admin, "Kasa: yeni rol talebi",
                email + " " + created.requestedRole() + " rolü istiyor. Yönetici panelindeki Talepler sekmesinden karar verebilirsiniz."));
        return created;
    }

    public Optional<RoleRequest> latest(UUID tenantId, UUID userId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            return jdbc.sql("SELECT * FROM role_requests WHERE user_id = :id ORDER BY created_at DESC LIMIT 1")
                    .param("id", userId)
                    .query(RoleRequestService::map)
                    .optional();
        });
    }

    public void cancel(UUID tenantId, UUID userId) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            int updated = jdbc.sql("UPDATE role_requests SET status = 'CANCELED', decided_at = now() WHERE user_id = :id AND status = 'PENDING'")
                    .param("id", userId)
                    .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no open request");
            }
            auditLog.record(tenantId, "ROLE_REQUEST_CANCELED", "USER", userId, Map.of());
        });
    }

    private RoleRequest find(UUID id) {
        return jdbc.sql("SELECT * FROM role_requests WHERE id = :id").param("id", id).query(RoleRequestService::map).single();
    }

    private static RoleRequest map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RoleRequest(
                rs.getObject("id", UUID.class),
                rs.getString("from_role"),
                rs.getString("requested_role"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getString("decision_note"),
                rs.getObject("decided_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
