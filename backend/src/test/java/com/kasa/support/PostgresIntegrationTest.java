package com.kasa.support;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Runs the application against a real PostgreSQL, connected as the restricted role like production.
 * {@link #owner} is a superuser connection for arranging data and asserting past row level security.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    protected static final JdbcClient owner = JdbcClient.create(POSTGRES.getDatabase("postgres", "kasa"));

    protected static final TransactionTemplate ownerTx =
            new TransactionTemplate(new JdbcTransactionManager(POSTGRES.getDatabase("postgres", "kasa")));

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        String url = "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/kasa";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "kasa_app");
        registry.add("spring.datasource.password", () -> "kasa_app");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 20);
        registry.add("spring.flyway.url", () -> url);
        // Migrations run as an ordinary database owner, as on a managed PostgreSQL where no superuser is available.
        registry.add("spring.flyway.user", () -> "kasa_owner");
        registry.add("spring.flyway.password", () -> "kasa_owner");
        registry.add("kasa.jwt.secret", () -> "test-only-secret-test-only-secret-1234");
        // Tests run recovery themselves, at the moment they choose.
        registry.add("kasa.payments.recovery-interval", () -> "PT1H");
        // Every test registers from the same address. The per-email login limit stays real, for RateLimitApiTest.
        registry.add("kasa.rate-limit.register-per-ip", () -> Integer.MAX_VALUE);
        registry.add("kasa.rate-limit.login-per-ip", () -> Integer.MAX_VALUE);
        registry.add("kasa.rate-limit.checkout-pay-per-ip", () -> Integer.MAX_VALUE);
        registry.add("kasa.rate-limit.payment-link-session-per-ip", () -> Integer.MAX_VALUE);
        registry.add("kasa.disputes.job-interval", () -> "PT1H");
        registry.add("kasa.settlement.cron", () -> "-");
        registry.add("kasa.settlement.recovery-interval", () -> "PT1H");
        registry.add("kasa.reconciliation.cron", () -> "-");
        registry.add("kasa.admin.token", () -> "test-admin-token");
        registry.add("kasa.webhooks.dispatch-interval", () -> "PT1H");
        registry.add("kasa.webhooks.max-attempts", () -> 3);
        registry.add("kasa.webhooks.request-timeout", () -> "PT1S");
        registry.add("kasa.webhooks.connect-timeout", () -> "PT1S");
        // The test receiver runs on localhost; WebhookUrlGuardTest covers the guard with this turned off.
        registry.add("kasa.webhooks.allow-insecure-targets", () -> true);
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            // initdb rejects non-ASCII OS locale names such as "Turkish_Türkiye.1254".
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().setLocaleConfig("locale", "C").start();
            JdbcClient admin = JdbcClient.create(postgres.getPostgresDatabase());
            admin.sql("CREATE ROLE kasa_app LOGIN PASSWORD 'kasa_app' NOSUPERUSER NOBYPASSRLS").update();
            admin.sql("CREATE ROLE kasa_owner LOGIN PASSWORD 'kasa_owner' NOSUPERUSER NOBYPASSRLS").update();
            admin.sql("CREATE DATABASE kasa OWNER kasa_owner").update();
            return postgres;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
