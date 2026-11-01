package com.kasa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.client.RestClient;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Every other test starts from an empty database, which says nothing about real data. This one builds the schema
 * the live demo runs on, fills it, and starts the current application against it, as a deployment would.
 */
class UpgradeFromLiveVersionTest {

    private static final String LIVE_VERSION = "7";
    private static final String PASSWORD = "an-existing-merchant-password";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CAPTURED_PAYMENT = UUID.randomUUID();
    private static final UUID AUTHORIZED_PAYMENT = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    @Test
    void theApplicationStartsOnLiveDataAndKeepsItUsable() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().setLocaleConfig("locale", "C").start()) {
            JdbcClient admin = JdbcClient.create(postgres.getPostgresDatabase());
            admin.sql("CREATE ROLE kasa_app LOGIN PASSWORD 'kasa_app' NOSUPERUSER NOBYPASSRLS").update();
            admin.sql("CREATE ROLE kasa_owner LOGIN PASSWORD 'kasa_owner' NOSUPERUSER NOBYPASSRLS").update();
            admin.sql("CREATE DATABASE kasa OWNER kasa_owner").update();
            String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/kasa";
            DataSource superuser = postgres.getDatabase("postgres", "kasa");
            JdbcClient owner = JdbcClient.create(superuser);

            Flyway.configure().dataSource(url, "kasa_owner", "kasa_owner").target(LIVE_VERSION).load().migrate();
            seedLiveData(owner);

            try (ConfigurableApplicationContext app = start(url)) {
                RestClient client = RestClient.create("http://localhost:" + app.getEnvironment().getProperty("local.server.port"));
                assertMigratedToTheEnd(owner);
                assertOldWebhookSecretIsNowEncrypted(owner);
                assertMerchantCanStillWork(client);
                assertOldCheckoutSessionStillOpens(client);
            }
        }
    }

    private static void seedLiveData(JdbcClient owner) {
        owner.sql("INSERT INTO tenants (id, name) VALUES (:id, 'Eski Dükkan')").param("id", TENANT).update();
        owner.sql("INSERT INTO users (tenant_id, email, password_hash) VALUES (:t, 'eski@example.com', :hash)")
                .param("t", TENANT).param("hash", new BCryptPasswordEncoder().encode(PASSWORD)).update();
        owner.sql("""
                        INSERT INTO payments (id, tenant_id, amount, currency, status, captured_amount, fee_amount, bank_reference)
                        VALUES (:captured, :t, 10000, 'TRY', 'CAPTURED', 10000, 250, 'bank_old_capture'),
                               (:authorized, :t, 5000, 'TRY', 'AUTHORIZED', 0, 0, 'bank_forgotten_by_restart')
                        """)
                .param("captured", CAPTURED_PAYMENT).param("authorized", AUTHORIZED_PAYMENT).param("t", TENANT).update();
        owner.sql("""
                        INSERT INTO payment_operations (id, tenant_id, payment_id, type, amount, status, idempotency_key, request_hash, bank_reference)
                        VALUES (gen_random_uuid(), :t, :p, 'CAPTURE', 10000, 'SUCCEEDED', 'old-key', 'old-hash', 'bank_old_capture')
                        """)
                .param("t", TENANT).param("p", CAPTURED_PAYMENT).update();
        owner.sql("INSERT INTO webhook_endpoints (tenant_id, url, secret) VALUES (:t, 'https://example.com/hook', 'whsec_live_plain')")
                .param("t", TENANT).update();
        owner.sql("""
                        INSERT INTO checkout_sessions (id, tenant_id, amount, currency, description, status, expires_at)
                        VALUES (:id, :t, 1000, 'TRY', 'Eski bağlantı', 'OPEN', now() + interval '1 hour')
                        """)
                .param("id", SESSION).param("t", TENANT).update();
    }

    private static ConfigurableApplicationContext start(String url) {
        // As arguments, because these must outrank application.yml as a real deployment's settings do.
        return new SpringApplicationBuilder(KasaApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=kasa_app",
                "--spring.datasource.password=kasa_app",
                "--spring.flyway.url=" + url,
                "--spring.flyway.user=kasa_owner",
                "--spring.flyway.password=kasa_owner",
                "--kasa.jwt.secret=test-only-secret-test-only-secret-1234",
                "--kasa.encryption.key=a-key-for-this-test",
                "--kasa.payments.recovery-interval=PT1H",
                "--kasa.settlement.cron=-",
                "--kasa.settlement.recovery-interval=PT1H",
                "--kasa.reconciliation.cron=-",
                "--kasa.webhooks.dispatch-interval=PT1H",
                "--kasa.disputes.job-interval=PT1H",
                "--kasa.billing.job-interval=PT1H");
    }

    private static void assertMigratedToTheEnd(JdbcClient owner) {
        List<String> failed = owner.sql("SELECT version FROM flyway_schema_history WHERE NOT success").query(String.class).list();
        assertThat(failed).isEmpty();
        Long applied = owner.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL").query(Long.class).single();
        assertThat(applied).isGreaterThanOrEqualTo(13);
    }

    private static void assertOldWebhookSecretIsNowEncrypted(JdbcClient owner) {
        String stored = owner.sql("SELECT secret FROM webhook_endpoints WHERE tenant_id = :t").param("t", TENANT).query(String.class).single();
        assertThat(stored).startsWith("enc1:").doesNotContain("whsec_live_plain");
    }

    private static void assertMerchantCanStillWork(RestClient client) {
        Map<String, Object> login = client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "eski@example.com", "password", PASSWORD)).retrieve().body(Map.class);
        assertThat(login).containsEntry("twoFactorRequired", false);
        String bearer = "Bearer " + login.get("accessToken");

        Map<String, Object> me = client.get().uri("/api/v1/me").header("Authorization", bearer).retrieve().body(Map.class);
        assertThat(me).containsEntry("role", "OWNER").containsEntry("tenantName", "Eski Dükkan");

        List<Map<String, Object>> payments = (List<Map<String, Object>>) client.get().uri("/api/v1/payments").header("Authorization", bearer)
                .retrieve().body(Map.class).get("items");
        assertThat(payments).hasSize(2);
        assertThat(payments).allSatisfy(payment -> assertThat(((Number) payment.get("disputedAmount")).longValue()).isZero());

        Map<String, Object> dispute = client.post().uri("/api/v1/disputes/simulate").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("paymentId", CAPTURED_PAYMENT, "reason", "OTHER")).retrieve().body(Map.class);
        assertThat(dispute).containsEntry("status", "NEEDS_RESPONSE");

        // The bank forgot this authorization in a restart before it became durable. It must fail cleanly, not crash.
        Map<String, Object> capture = client.post().uri("/api/v1/payments/" + AUTHORIZED_PAYMENT + "/capture").header("Authorization", bearer)
                .header("Idempotency-Key", UUID.randomUUID().toString()).retrieve().body(Map.class);
        assertThat((Map<String, Object>) capture.get("operation")).containsEntry("status", "FAILED").containsEntry("failureCode", "unknown_authorization");
    }

    private static void assertOldCheckoutSessionStillOpens(RestClient client) {
        Map<String, Object> session = client.get().uri("/api/v1/public/checkout/" + SESSION).retrieve().body(Map.class);
        assertThat(session).containsEntry("status", "OPEN").containsEntry("merchantName", "Eski Dükkan");
    }
}
