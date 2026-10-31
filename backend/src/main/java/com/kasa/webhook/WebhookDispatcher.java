package com.kasa.webhook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.kasa.tenant.TenantScope;

/**
 * Sends queued webhooks with at-least-once delivery: a receiver may see an event twice and must deduplicate
 * on the event id. Events may also arrive out of order, so receivers should rely on the payload's timestamp.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private record Claimed(UUID id, UUID tenantId, int attempts, String url, String secret, UUID eventId,
                           String eventType, String payload) {
    }

    private record Outcome(Integer statusCode, String error) {
        boolean delivered() {
            return statusCode != null && statusCode >= 200 && statusCode < 300;
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final WebhookUrlGuard urlGuard;
    private final WebhookProperties properties;
    private final HttpClient httpClient;

    public WebhookDispatcher(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope,
                             WebhookUrlGuard urlGuard, WebhookProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.urlGuard = urlGuard;
        this.properties = properties;
        // Redirects stay off: following one could lead to an internal address after the guard has passed.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(fixedDelayString = "${kasa.webhooks.dispatch-interval}",
            initialDelayString = "${kasa.webhooks.dispatch-interval}")
    void scheduledDispatch() {
        dispatchDue();
    }

    /** Sends one batch and returns its size. Sends run in parallel so a slow receiver cannot delay the others. */
    public int dispatchDue() {
        List<Claimed> claimed = claim();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Claimed delivery : claimed) {
                executor.submit(() -> attempt(delivery));
            }
        }
        return claimed.size();
    }

    private List<Claimed> claim() {
        return jdbc.sql("SELECT * FROM claim_due_webhook_deliveries(:limit, :leaseSeconds)")
                .param("limit", properties.batchSize())
                .param("leaseSeconds", properties.lease().toSeconds())
                .query((rs, rowNum) -> new Claimed(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getInt("attempts"),
                        rs.getString("url"),
                        rs.getString("secret"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
    }

    private void attempt(Claimed delivery) {
        try {
            record(delivery, send(delivery));
        } catch (RuntimeException e) {
            // The lease expires on its own, so the delivery is retried even though nothing was recorded.
            log.error("Could not record webhook delivery {}", delivery.id(), e);
        }
    }

    private Outcome send(Claimed delivery) {
        try {
            URI target = urlGuard.check(delivery.url());
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(properties.requestTimeout())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Kasa-Webhooks/1")
                    .header("X-Kasa-Event-Id", delivery.eventId().toString())
                    .header("X-Kasa-Event-Type", delivery.eventType())
                    .header("X-Kasa-Signature", WebhookSigner.signatureHeader(
                            delivery.secret(), Instant.now().getEpochSecond(), delivery.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.payload(), StandardCharsets.UTF_8))
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return new Outcome(status, status >= 200 && status < 300 ? null : "unexpected_status");
        } catch (WebhookUrlGuard.UnsafeUrlException e) {
            return new Outcome(null, "target_blocked");
        } catch (IOException e) {
            return new Outcome(null, e instanceof java.net.http.HttpTimeoutException ? "timeout" : "connection_failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(null, "interrupted");
        }
    }

    /** The attempts check discards this result if the lease expired and another dispatcher has since re-claimed it. */
    private void record(Claimed delivery, Outcome outcome) {
        boolean exhausted = delivery.attempts() >= properties.maxAttempts();
        String status = outcome.delivered() ? "DELIVERED" : exhausted ? "FAILED" : "PENDING";
        Instant nextAttempt = Instant.now().plus(backoffAfter(delivery.attempts()));
        tx.executeWithoutResult(transaction -> {
            tenantScope.apply(delivery.tenantId());
            jdbc.sql("""
                            UPDATE webhook_deliveries
                            SET status = :status, last_status_code = :statusCode, last_error = :error,
                                delivered_at = CASE WHEN :status = 'DELIVERED' THEN now() END,
                                next_attempt_at = :nextAttempt::timestamptz, updated_at = now()
                            WHERE id = :id AND status = 'PENDING' AND attempts = :attempts
                            """)
                    .param("status", status)
                    .param("statusCode", outcome.statusCode())
                    .param("error", outcome.error())
                    .param("nextAttempt", nextAttempt.toString())
                    .param("id", delivery.id())
                    .param("attempts", delivery.attempts())
                    .update();
        });
    }

    /** Exponential, capped, with jitter so receivers recovering from an outage are not hit all at once. */
    Duration backoffAfter(int attempt) {
        long baseSeconds = properties.baseBackoff().toSeconds();
        long exponential = baseSeconds << Math.min(attempt - 1, 20);
        long capped = Math.min(exponential, properties.maxBackoff().toSeconds());
        double jitter = ThreadLocalRandom.current().nextDouble(0.8, 1.2);
        return Duration.ofSeconds(Math.max(1, Math.round(capped * jitter)));
    }
}
