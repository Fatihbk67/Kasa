package com.kasa;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kasa.auth.AuthService;
import com.kasa.bank.SimulatedBank;
import com.kasa.reconciliation.ReconciliationService;
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
        // Durable commits, as in production. The embedded default turns them off, which would flatter any load test.
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setLocaleConfig("locale", "C")
                .setServerConfig("synchronous_commit", "on")
                .start();
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
        // The load test registers its merchants in one burst from one address.
        System.setProperty("kasa.rate-limit.register-per-ip", "1000");
        // A clean way to stop, which also removes the throwaway database. A killed process would leave it running.
        System.setProperty("management.endpoints.web.exposure.include", "health,prometheus,shutdown");
        System.setProperty("management.endpoint.shutdown.access", "unrestricted");

        ConfigurableApplicationContext context = SpringApplication.run(KasaApplication.class, args);
        UUID tenantId = seedDemoData(context);
        seedHistory(context, tenantId, JdbcClient.create(postgres.getPostgresDatabase()));
        log.info("Demo merchant ready: {} / {}", DEMO_EMAIL, DEMO_PASSWORD);
    }

    /** Sixty days of realistic activity for the analytics charts, plus bank errors for the admin panel to show. */
    private static void seedHistory(ConfigurableApplicationContext context, UUID tenantId, JdbcClient superuser) {
        PaymentService payments = context.getBean(PaymentService.class);
        Random random = new Random(42);
        List<String> descriptions = List.of("Çekirdek kahve aboneliği", "Filtre kahve seti", "Toplu sipariş", "Espresso makinesi",
                "Hediye kartı", "Kurumsal sipariş");
        String lastCaptureReference = null;

        for (int daysAgo = 59; daysAgo >= 1; daysAgo--) {
            int paymentsThatDay = 2 + random.nextInt(4) + (60 - daysAgo) / 15;
            for (int i = 0; i < paymentsThatDay; i++) {
                int roll = random.nextInt(100);
                String token = roll < 7 ? "tok_insufficient_funds" : roll < 11 ? "tok_declined" : "tok_success";
                long amount = 5_000 + random.nextInt(245_000);
                UUID id = authorize(payments, tenantId, amount, token, descriptions.get(random.nextInt(descriptions.size())));
                if (token.equals("tok_success") && random.nextInt(100) < 88) {
                    capture(payments, tenantId, id);
                    if (random.nextInt(100) < 9) {
                        payments.refund(tenantId, id, key(), amount / 2);
                    }
                }
                int hours = 8 + random.nextInt(12);
                superuser.sql("UPDATE payments SET created_at = now() - make_interval(days => :d, hours => :h), updated_at = now() - make_interval(days => :d, hours => :h) WHERE id = :id")
                        .param("d", daysAgo).param("h", hours).param("id", id).update();
                superuser.sql("UPDATE payment_operations SET created_at = now() - make_interval(days => :d, hours => :h), updated_at = now() - make_interval(days => :d, hours => :h) WHERE payment_id = :id")
                        .param("d", daysAgo).param("h", hours).param("id", id).update();
            }
        }

        lastCaptureReference = superuser.sql("SELECT bank_reference FROM payment_operations WHERE type = 'CAPTURE' AND status = 'SUCCEEDED' ORDER BY updated_at DESC LIMIT 1")
                .query(String.class).single();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        SimulatedBank bank = context.getBean(SimulatedBank.class);
        bank.simulateAmountChange(lastCaptureReference, 1_000);
        bank.simulateUnknownLine(today, "bank_demo_bilinmeyen_islem", 75_000);
        context.getBean(ReconciliationService.class).reconcile(today);
    }

    private static UUID seedDemoData(ConfigurableApplicationContext context) {
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
        return tenantId;
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
