package com.kasa.observability;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** Counts what the business cares about, so dashboards and alerts can show money flow and not only server load. */
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final AtomicLong pendingOperations = new AtomicLong();
    private final AtomicLong pendingPayouts = new AtomicLong();
    private final AtomicLong queuedWebhooks = new AtomicLong();

    public BusinessMetrics(MeterRegistry registry, JdbcClient jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        Gauge.builder("kasa.backlog.payment.operations", pendingOperations, AtomicLong::get)
                .description("Bank operations whose outcome is still unknown")
                .register(registry);
        Gauge.builder("kasa.backlog.payouts", pendingPayouts, AtomicLong::get)
                .description("Payouts awaiting the bank's answer")
                .register(registry);
        Gauge.builder("kasa.backlog.webhook.deliveries", queuedWebhooks, AtomicLong::get)
                .description("Webhooks queued or awaiting retry")
                .register(registry);
    }

    public void count(String name, String... tags) {
        registry.counter(name, tags).increment();
    }

    /** Counts only if the surrounding transaction commits, so a rollback never inflates the numbers. */
    public void countAfterCommit(String name, String... tags) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                count(name, tags);
            }
        });
    }

    @Scheduled(fixedDelayString = "${kasa.metrics.refresh-interval}")
    void refreshBacklog() {
        jdbc.sql("SELECT * FROM platform_backlog_counts()")
                .query(rs -> {
                    pendingOperations.set(rs.getLong("pending_payment_operations"));
                    pendingPayouts.set(rs.getLong("pending_payouts"));
                    queuedWebhooks.set(rs.getLong("queued_webhook_deliveries"));
                });
    }
}
