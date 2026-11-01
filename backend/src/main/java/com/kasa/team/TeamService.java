package com.kasa.team;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.auth.AuthService;
import com.kasa.common.Hashes;
import com.kasa.tenant.TenantScope;

@Service
public class TeamService {

    public record Member(UUID id, String email, String role, OffsetDateTime disabledAt, OffsetDateTime createdAt) {
    }

    public record Invitation(UUID id, String email, String role, OffsetDateTime expiresAt, OffsetDateTime createdAt) {
    }

    public record Team(List<Member> members, List<Invitation> invitations) {
    }

    /** The only time the invitation token is available: just its hash is stored. */
    public record CreatedInvitation(UUID id, String email, String role, OffsetDateTime expiresAt, String token) {
    }

    /** What someone holding an invitation link may see before they have an account. */
    public record PublicInvitation(String tenantName, String email, String role, String status) {
    }

    private record StoredInvitation(UUID id, UUID tenantId, String tenantName, String email, String role,
                                    OffsetDateTime expiresAt, OffsetDateTime acceptedAt, OffsetDateTime revokedAt) {
        String status() {
            if (acceptedAt != null) {
                return "ACCEPTED";
            }
            if (revokedAt != null) {
                return "REVOKED";
            }
            return expiresAt.isBefore(OffsetDateTime.now()) ? "EXPIRED" : "OPEN";
        }
    }

    private static final Set<String> ROLES = Set.of("OWNER", "ADMIN", "VIEWER");
    private static final Duration INVITATION_LIFETIME = Duration.ofDays(7);

    private final SecureRandom random = new SecureRandom();
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final AuditLog auditLog;

    public TeamService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, PasswordEncoder passwordEncoder,
                       AuthService authService, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.auditLog = auditLog;
    }

    public Team team(UUID tenantId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            List<Member> members = jdbc.sql("SELECT id, email, role, disabled_at, created_at FROM users ORDER BY created_at, id")
                    .query((rs, rowNum) -> new Member(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getString("role"),
                            rs.getObject("disabled_at", OffsetDateTime.class),
                            rs.getObject("created_at", OffsetDateTime.class)))
                    .list();
            List<Invitation> invitations = jdbc.sql("""
                            SELECT id, email, role, expires_at, created_at
                            FROM team_invitations
                            WHERE accepted_at IS NULL AND revoked_at IS NULL
                            ORDER BY created_at DESC, id
                            """)
                    .query((rs, rowNum) -> new Invitation(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getString("role"),
                            rs.getObject("expires_at", OffsetDateTime.class),
                            rs.getObject("created_at", OffsetDateTime.class)))
                    .list();
            return new Team(members, invitations);
        });
    }

    /** A manager may add viewers, but only an owner can hand out management rights. */
    public CreatedInvitation invite(UUID tenantId, UUID actorId, String email, String role) {
        requireValidRole(role);
        String normalizedEmail = AuthService.normalize(email);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = HexFormat.of().formatHex(secret);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(INVITATION_LIFETIME);
        try {
            UUID id = tx.execute(status -> {
                tenantScope.apply(tenantId);
                String actorRole = roleOf(actorId);
                if (!role.equals("VIEWER") && !actorRole.equals("OWNER")) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only an owner can invite this role");
                }
                // Expired invitations must not block inviting the same person again.
                jdbc.sql("""
                                UPDATE team_invitations SET revoked_at = now()
                                WHERE email = :email AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at < now()
                                """)
                        .param("email", normalizedEmail)
                        .update();
                UUID created = jdbc.sql("""
                                INSERT INTO team_invitations (tenant_id, email, role, token_hash, invited_by, expires_at)
                                VALUES (:tenantId, :email, :role, :hash, :invitedBy, :expiresAt)
                                RETURNING id
                                """)
                        .param("tenantId", tenantId)
                        .param("email", normalizedEmail)
                        .param("role", role)
                        .param("hash", Hashes.sha256Hex(token))
                        .param("invitedBy", actorId)
                        .param("expiresAt", expiresAt)
                        .query(UUID.class)
                        .single();
                auditLog.record(tenantId, "MEMBER_INVITED", "INVITATION", created, Map.of("email", normalizedEmail, "role", role));
                return created;
            });
            return new CreatedInvitation(id, normalizedEmail, role, expiresAt, token);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this email already has an open invitation");
        }
    }

    public void revokeInvitation(UUID tenantId, UUID invitationId) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            int updated = jdbc.sql("""
                            UPDATE team_invitations SET revoked_at = now()
                            WHERE id = :id AND accepted_at IS NULL AND revoked_at IS NULL
                            """)
                    .param("id", invitationId)
                    .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "open invitation not found");
            }
            auditLog.record(tenantId, "INVITATION_REVOKED", "INVITATION", invitationId, Map.of());
        });
    }

    public PublicInvitation publicInvitation(String token) {
        StoredInvitation stored = findInvitation(token);
        return new PublicInvitation(stored.tenantName(), stored.email(), stored.role(), stored.status());
    }

    public AuthService.TokenResponse accept(String token, String password) {
        AuthService.requireFitsBcrypt(password);
        StoredInvitation invitation = findInvitation(token);
        if (!invitation.status().equals("OPEN")) {
            throw new ResponseStatusException(HttpStatus.GONE, "invitation is no longer valid");
        }
        String passwordHash = passwordEncoder.encode(password);
        try {
            UUID userId = tx.execute(status -> {
                tenantScope.apply(invitation.tenantId());
                // The conditional update lets only one of two simultaneous attempts use the invitation.
                int claimed = jdbc.sql("""
                                UPDATE team_invitations SET accepted_at = now()
                                WHERE id = :id AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()
                                """)
                        .param("id", invitation.id())
                        .update();
                if (claimed == 0) {
                    throw new ResponseStatusException(HttpStatus.GONE, "invitation is no longer valid");
                }
                UUID id = jdbc.sql("""
                                INSERT INTO users (tenant_id, email, password_hash, role)
                                VALUES (:tenantId, :email, :hash, :role)
                                RETURNING id
                                """)
                        .param("tenantId", invitation.tenantId())
                        .param("email", invitation.email())
                        .param("hash", passwordHash)
                        .param("role", invitation.role())
                        .query(UUID.class)
                        .single();
                auditLog.record(invitation.tenantId(), AuditLog.Actor.user(id, invitation.email()), "MEMBER_JOINED", "USER", id,
                        Map.of("role", invitation.role()));
                return id;
            });
            return authService.token(userId, invitation.tenantId(), 1);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this email already has an account");
        }
    }

    public void changeRole(UUID tenantId, UUID actorId, UUID memberId, String role) {
        requireValidRole(role);
        ownerChange(tenantId, actorId, memberId, previousRole -> {
            jdbc.sql("UPDATE users SET role = :role WHERE id = :id")
                    .param("role", role)
                    .param("id", memberId)
                    .update();
            auditLog.record(tenantId, "MEMBER_ROLE_CHANGED", "USER", memberId, Map.of("from", previousRole, "to", role));
        });
    }

    public void remove(UUID tenantId, UUID actorId, UUID memberId) {
        ownerChange(tenantId, actorId, memberId, previousRole -> {
            jdbc.sql("UPDATE users SET disabled_at = now() WHERE id = :id")
                    .param("id", memberId)
                    .update();
            auditLog.record(tenantId, "MEMBER_REMOVED", "USER", memberId, Map.of("role", previousRole));
        });
    }

    /**
     * Locks every owner first and re-reads the actor's role under that lock. Otherwise two owners demoting
     * each other at the same moment could both succeed and leave the business with no owner at all.
     */
    private void ownerChange(UUID tenantId, UUID actorId, UUID memberId, java.util.function.Consumer<String> change) {
        if (actorId.equals(memberId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "you cannot change your own membership");
        }
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            jdbc.sql("SELECT id FROM users WHERE role = 'OWNER' AND disabled_at IS NULL ORDER BY id FOR UPDATE")
                    .query(UUID.class)
                    .list();
            if (!roleOf(actorId).equals("OWNER")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only an owner can do this");
            }
            String previousRole = jdbc.sql("SELECT role FROM users WHERE id = :id AND disabled_at IS NULL FOR UPDATE")
                    .param("id", memberId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "member not found"));
            change.accept(previousRole);
        });
    }

    private String roleOf(UUID userId) {
        return jdbc.sql("SELECT role FROM users WHERE id = :id AND disabled_at IS NULL")
                .param("id", userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "no longer a member"));
    }

    private StoredInvitation findInvitation(String token) {
        return jdbc.sql("SELECT * FROM find_invitation(:hash)")
                .param("hash", Hashes.sha256Hex(token))
                .query((rs, rowNum) -> new StoredInvitation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_name"),
                        rs.getString("email"),
                        rs.getString("role"),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("accepted_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "invitation not found"));
    }

    private static void requireValidRole(String role) {
        if (!ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown role");
        }
    }
}
