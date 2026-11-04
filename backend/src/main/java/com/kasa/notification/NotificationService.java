package com.kasa.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.tenant.TenantScope;

/**
 * The bell in the dashboard. Written in the same transaction as the event it reports, so a notification never
 * announces something that was rolled back, and nothing that happened goes unannounced.
 */
@Service
public class NotificationService {

    public record Notification(UUID id, String kind, String title, String body, String link, boolean read, OffsetDateTime createdAt) {
    }

    public record Inbox(List<Notification> items, long unread) {
    }

    public static final List<String> OWNERS = List.of("OWNER");
    public static final List<String> OPERATORS = List.of("OWNER", "ADMIN");
    public static final List<String> EVERYONE = List.of("OWNER", "ADMIN", "VIEWER");

    private static final int INBOX_SIZE = 30;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;

    public NotificationService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
    }

    /** Expects the tenant to be bound. One row for each active member who holds one of the roles. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void toRoles(UUID tenantId, List<String> roles, String kind, String title, String body, String link) {
        jdbc.sql("""
                        INSERT INTO notifications (tenant_id, user_id, kind, title, body, link)
                        SELECT :tenantId, id, :kind, :title, :body, :link
                        FROM users WHERE disabled_at IS NULL AND role IN (:roles)
                        """)
                .param("tenantId", tenantId)
                .param("kind", kind)
                .param("title", title)
                .param("body", body)
                .param("link", link)
                .param("roles", roles)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void toUser(UUID tenantId, UUID userId, String kind, String title, String body, String link) {
        jdbc.sql("""
                        INSERT INTO notifications (tenant_id, user_id, kind, title, body, link)
                        VALUES (:tenantId, :userId, :kind, :title, :body, :link)
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("kind", kind)
                .param("title", title)
                .param("body", body)
                .param("link", link)
                .update();
    }

    public Inbox inbox(UUID tenantId, UUID userId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            List<Notification> items = jdbc.sql("SELECT * FROM notifications WHERE user_id = :id ORDER BY created_at DESC, id LIMIT :limit")
                    .param("id", userId)
                    .param("limit", INBOX_SIZE)
                    .query((rs, rowNum) -> new Notification(
                            rs.getObject("id", UUID.class),
                            rs.getString("kind"),
                            rs.getString("title"),
                            rs.getString("body"),
                            rs.getString("link"),
                            rs.getObject("read_at", OffsetDateTime.class) != null,
                            rs.getObject("created_at", OffsetDateTime.class)))
                    .list();
            long unread = jdbc.sql("SELECT COUNT(*) FROM notifications WHERE user_id = :id AND read_at IS NULL")
                    .param("id", userId)
                    .query(Long.class)
                    .single();
            return new Inbox(items, unread);
        });
    }

    /** Scoped to the person as well as the tenant, so nobody can mark a colleague's notification as read. */
    public void markRead(UUID tenantId, UUID userId, UUID notificationId) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            int updated = jdbc.sql("UPDATE notifications SET read_at = COALESCE(read_at, now()) WHERE id = :id AND user_id = :user")
                    .param("id", notificationId)
                    .param("user", userId)
                    .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "notification not found");
            }
        });
    }

    public void markAllRead(UUID tenantId, UUID userId) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = :id AND read_at IS NULL")
                    .param("id", userId)
                    .update();
        });
    }
}
