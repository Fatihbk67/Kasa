package com.kasa.webhook;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.tenant.TenantScope;

@Service
public class WebhookEndpointService {

    public record EndpointSummary(UUID id, String url, OffsetDateTime createdAt, OffsetDateTime disabledAt) {
    }

    /** The only time the signing secret is returned. */
    public record CreatedEndpoint(UUID id, String url, String secret) {
    }

    public record DeliverySummary(UUID id, UUID eventId, String eventType, String status, int attempts,
                                  OffsetDateTime nextAttemptAt, Integer lastStatusCode, String lastError,
                                  OffsetDateTime deliveredAt, OffsetDateTime createdAt) {
    }

    private static final int MAX_ACTIVE_ENDPOINTS = 10;
    private static final int DELIVERY_PAGE_SIZE = 50;

    private final SecureRandom random = new SecureRandom();
    private final JdbcClient jdbc;
    private final TenantScope tenantScope;
    private final WebhookUrlGuard urlGuard;
    private final AuditLog auditLog;

    public WebhookEndpointService(JdbcClient jdbc, TenantScope tenantScope, WebhookUrlGuard urlGuard, AuditLog auditLog) {
        this.auditLog = auditLog;
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.urlGuard = urlGuard;
    }

    @Transactional
    public CreatedEndpoint create(UUID tenantId, String url) {
        try {
            urlGuard.check(url);
        } catch (WebhookUrlGuard.UnsafeUrlException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
        tenantScope.apply(tenantId);
        int active = jdbc.sql("SELECT count(*) FROM webhook_endpoints WHERE disabled_at IS NULL")
                .query(Integer.class)
                .single();
        if (active >= MAX_ACTIVE_ENDPOINTS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "too many active webhook endpoints");
        }
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String secret = "whsec_" + HexFormat.of().formatHex(secretBytes);
        UUID id = jdbc.sql("""
                        INSERT INTO webhook_endpoints (tenant_id, url, secret)
                        VALUES (:tenantId, :url, :secret)
                        RETURNING id
                        """)
                .param("tenantId", tenantId)
                .param("url", url)
                .param("secret", secret)
                .query(UUID.class)
                .single();
        auditLog.record(tenantId, "WEBHOOK_ENDPOINT_CREATED", "WEBHOOK_ENDPOINT", id, Map.of("url", url));
        return new CreatedEndpoint(id, url, secret);
    }

    @Transactional(readOnly = true)
    public List<EndpointSummary> list(UUID tenantId) {
        tenantScope.apply(tenantId);
        return jdbc.sql("SELECT id, url, created_at, disabled_at FROM webhook_endpoints ORDER BY created_at DESC, id")
                .query((rs, rowNum) -> new EndpointSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("url"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("disabled_at", OffsetDateTime.class)))
                .list();
    }

    @Transactional
    public void disable(UUID tenantId, UUID endpointId) {
        tenantScope.apply(tenantId);
        int updated = jdbc.sql("UPDATE webhook_endpoints SET disabled_at = now() WHERE id = :id AND disabled_at IS NULL")
                .param("id", endpointId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "active webhook endpoint not found");
        }
        jdbc.sql("""
                        UPDATE webhook_deliveries SET status = 'CANCELLED', updated_at = now()
                        WHERE endpoint_id = :id AND status = 'PENDING'
                        """)
                .param("id", endpointId)
                .update();
        auditLog.record(tenantId, "WEBHOOK_ENDPOINT_DISABLED", "WEBHOOK_ENDPOINT", endpointId, Map.of());
    }

    @Transactional(readOnly = true)
    public List<DeliverySummary> deliveries(UUID tenantId, UUID endpointId) {
        tenantScope.apply(tenantId);
        return jdbc.sql("""
                        SELECT d.id, d.event_id, e.type, d.status, d.attempts, d.next_attempt_at, d.last_status_code,
                               d.last_error, d.delivered_at, d.created_at
                        FROM webhook_deliveries d
                        JOIN webhook_events e ON e.id = d.event_id
                        WHERE d.endpoint_id = :id
                        ORDER BY d.created_at DESC, d.id
                        LIMIT :limit
                        """)
                .param("id", endpointId)
                .param("limit", DELIVERY_PAGE_SIZE)
                .query((rs, rowNum) -> new DeliverySummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getObject("next_attempt_at", OffsetDateTime.class),
                        rs.getObject("last_status_code", Integer.class),
                        rs.getString("last_error"),
                        rs.getObject("delivered_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    /** Lets a merchant replay a delivery that exhausted its retries, for example after fixing their server. */
    @Transactional
    public void retry(UUID tenantId, UUID deliveryId) {
        tenantScope.apply(tenantId);
        int updated = jdbc.sql("""
                        UPDATE webhook_deliveries
                        SET status = 'PENDING', attempts = 0, next_attempt_at = now(), updated_at = now()
                        WHERE id = :id AND status = 'FAILED'
                          AND endpoint_id IN (SELECT id FROM webhook_endpoints WHERE disabled_at IS NULL)
                        """)
                .param("id", deliveryId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "failed delivery not found");
        }
    }
}
