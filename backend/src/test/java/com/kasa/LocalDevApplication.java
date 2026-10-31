package com.kasa;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kasa.auth.AuthService;
import com.kasa.checkout.CheckoutService;
import com.kasa.payment.PaymentService;
import com.kasa.payment.PaymentViews.OperationResult;
import com.kasa.settlement.SettlementService;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Runs the real application with a throwaway PostgreSQL and demo data, needing nothing installed but Java:
 * {@code mvn spring-boot:test-run}. Lives in the test tree so it can never ship in the production build.
 * Everything is recreated on each start, because the simulated bank keeps its own records in memory too.
 */
public final class LocalDevApplication {

    private static final Logger log = LoggerFactory.getLogger(LocalDevApplication.class);

    private static final String DEMO_EMAIL = "demo@kasa.test";
    private static final String DEMO_PASSWORD = "demo-parola-12345";

    private LocalDevApplication() {
    }

    public static void main(String[] args) throws IOException {
        // initdb rejects non-ASCII OS locale names such as "Turkish_Türkiye.1254".
        EmbeddedPostgres postgres = EmbeddedPostgres.builder().setLocaleConfig("locale", "C").start();
        JdbcClient.create(postgres.getPostgresDatabase())
                .sql("CREATE ROLE kasa_app LOGIN PASSWORD 'kasa_app' NOSUPERUSER NOBYPASSRLS")
                .update();

        String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
        System.setProperty("spring.datasource.url", url);
        System.setProperty("spring.flyway.url", url);
        System.setProperty("spring.flyway.user", "postgres");
        System.setProperty("spring.flyway.password", "postgres");
        System.setProperty("kasa.jwt.secret", "local-development-only-secret-do-not-deploy");
        System.setProperty("kasa.admin.token", "local-dev-admin-token");
        System.setProperty("kasa.webhooks.allow-insecure-targets", "true");

        ConfigurableApplicationContext context = SpringApplication.run(KasaApplication.class, args);
        seedDemoData(context);
        log.info("Demo merchant ready: {} / {}", DEMO_EMAIL, DEMO_PASSWORD);
    }

    private static void seedDemoData(ConfigurableApplicationContext context) {
        UUID tenantId = context.getBean(AuthService.class).register("Demo Kahve", DEMO_EMAIL, DEMO_PASSWORD).tenantId();
        PaymentService payments = context.getBean(PaymentService.class);

        UUID captured = authorize(payments, tenantId, 125_000, "tok_success", "Çekirdek kahve aboneliği");
        capture(payments, tenantId, captured);
        context.getBean(SettlementService.class).settleAll(Instant.now().plusSeconds(1));

        UUID refunded = authorize(payments, tenantId, 48_000, "tok_success", "Filtre kahve seti");
        capture(payments, tenantId, refunded);
        payments.refund(tenantId, refunded, key(), 12_000L);
        authorize(payments, tenantId, 76_500, "tok_success", "Toplu sipariş");
        authorize(payments, tenantId, 32_000, "tok_insufficient_funds", "Hediye kartı");

        context.getBean(CheckoutService.class).create(tenantId, 19_900, "TRY", "Demo ödeme bağlantısı");
    }

    private static UUID authorize(PaymentService payments, UUID tenantId, long amount, String token, String description) {
        OperationResult result = payments.create(tenantId, key(), amount, "TRY", token, description);
        return result.payment().id();
    }

    private static void capture(PaymentService payments, UUID tenantId, UUID paymentId) {
        payments.capture(tenantId, paymentId, key(), null);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
