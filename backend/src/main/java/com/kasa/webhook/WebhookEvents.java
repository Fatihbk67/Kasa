package com.kasa.webhook;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

@Component
public class WebhookEvents {

    private record Envelope(UUID id, String type, OffsetDateTime createdAt, Object data) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public WebhookEvents(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Records an event and queues it for the tenant's active endpoints. Joining the caller's transaction
     * means the event commits if and only if the change it describes commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID tenantId, String type, Object data) {
        UUID eventId = UUID.randomUUID();
        Envelope envelope = new Envelope(eventId, type, OffsetDateTime.now(ZoneOffset.UTC), data);
        jdbc.sql("""
                        INSERT INTO webhook_events (id, tenant_id, type, payload)
                        VALUES (:id, :tenantId, :type, :payload)
                        """)
                .param("id", eventId)
                .param("tenantId", tenantId)
                .param("type", type)
                .param("payload", jsonMapper.writeValueAsString(envelope))
                .update();
        // FOR SHARE orders this against a concurrent disable: either this query waits and skips the endpoint,
        // or the disable waits and then cancels the delivery created here.
        jdbc.sql("""
                        INSERT INTO webhook_deliveries (tenant_id, event_id, endpoint_id)
                        SELECT tenant_id, :eventId, id FROM webhook_endpoints WHERE disabled_at IS NULL FOR SHARE
                        """)
                .param("eventId", eventId)
                .update();
    }
}
