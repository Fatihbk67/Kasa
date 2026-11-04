package com.kasa;

import java.io.IOException;
import java.nio.file.Path;
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

import com.kasa.admin.MerchantReviewService;
import com.kasa.onboarding.MerchantProfileService;
import com.kasa.risk.RiskService;
import com.kasa.auth.AuthService;
import com.kasa.bank.SimulatedBank;
import com.kasa.billing.BillingService;
import com.kasa.reconciliation.ReconciliationService;
import com.kasa.checkout.CheckoutService;
import com.kasa.checkout.PaymentLinkService;
import com.kasa.customer.CustomerService;
import com.kasa.dispute.DisputeService;
import com.kasa.payment.PaymentService;
import com.kasa.payment.PaymentViews.OperationResult;
import com.kasa.settlement.SettlementService;
import com.kasa.team.RoleRequestService;
import com.kasa.team.TeamService;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Runs the real application with a throwaway PostgreSQL and demo data, needing nothing installed but Java:
 * {@code mvn spring-boot:test-run}. Lives in the test tree so it can never ship in the production build.
 * Everything is recreated on each start, unless KASA_LOCAL_DATA_DIR names a folder to keep the data in.
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
        EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder()
                .setLocaleConfig("locale", "C")
                .setServerConfig("synchronous_commit", "on");
        // For finding slow queries: records the time every statement takes, which costs a little on every call.
        if ("true".equals(System.getenv("KASA_LOCAL_QUERY_STATS"))) {
            builder.setServerConfig("shared_preload_libraries", "pg_stat_statements");
        }
        String dataDirectory = System.getenv("KASA_LOCAL_DATA_DIR");
        if (dataDirectory != null && !dataDirectory.isBlank()) {
            builder.setDataDirectory(Path.of(dataDirectory)).setCleanDataDirectory(false);
        }
        EmbeddedPostgres postgres = builder.start();
        JdbcClient superuser = JdbcClient.create(postgres.getPostgresDatabase());
        boolean roleExists = superuser.sql("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kasa_app')")
                .query(Boolean.class)
                .single();
        if (!roleExists) {
            superuser.sql("CREATE ROLE kasa_app LOGIN PASSWORD 'kasa_app' NOSUPERUSER NOBYPASSRLS").update();
        }

        String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
        log.info("Local database: {} (user postgres, password postgres)", url);
        System.setProperty("spring.datasource.url", url);
        System.setProperty("spring.flyway.url", url);
        System.setProperty("spring.flyway.user", "postgres");
        System.setProperty("spring.flyway.password", "postgres");
        System.setProperty("kasa.jwt.secret", "local-development-only-secret-do-not-deploy");
        System.setProperty("kasa.admin.token", "local-dev-admin-token");
        System.setProperty("kasa.webhooks.allow-insecure-targets", "true");
        // No email provider locally either, so a password reset link can be picked up from the console.
        System.setProperty("kasa.mail.log-body", "true");
        // The load test registers its merchants in one burst from one address.
        System.setProperty("kasa.rate-limit.register-per-ip", "1000");
        // A clean way to stop, which also removes the throwaway database. A killed process would leave it running.
        System.setProperty("management.endpoints.web.exposure.include", "health,prometheus,shutdown");
        System.setProperty("management.endpoint.shutdown.access", "unrestricted");

        ConfigurableApplicationContext context = SpringApplication.run(KasaApplication.class, args);
        boolean alreadySeeded = superuser.sql("SELECT EXISTS (SELECT 1 FROM tenants)").query(Boolean.class).single();
        if (alreadySeeded) {
            log.info("Kept the data from the last run in {}", dataDirectory);
            return;
        }
        UUID tenantId = seedDemoData(context);
        seedHistory(context, tenantId, superuser);
        seedTeam(context, tenantId, superuser);
        seedCustomersLinksAndDisputes(context, tenantId, superuser);
        seedRisk(context, tenantId, superuser);
        // A second business waiting for review, so the admin panel has something to approve.
        UUID bakery = context.getBean(AuthService.class).register("Yeni Fırın", "firin@kasa.test", DEMO_PASSWORD).tenantId();
        context.getBean(MerchantProfileService.class).save(bakery, new MerchantProfileService.ProfileInput("SOLE_PROPRIETOR",
                "Ali Usta Fırıncılık", "10000000146", "Beşiktaş", "FOOD", null, "+90 212 555 01 02",
                "Sinanpaşa Mah. Çarşı Sk. No: 4", "İstanbul", "TR33 0006 1005 1978 6457 8413 26"));
        log.info("Demo merchant ready: {} / {}", DEMO_EMAIL, DEMO_PASSWORD);
    }

    /** The recommended rules, plus one payment held for review, a card stopped for too many tries, and a blocked card. */
    private static void seedRisk(ConfigurableApplicationContext context, UUID tenantId, JdbcClient superuser) {
        // The seed itself just used the test card many times. Moved back, so the first test payment is not
        // blocked by the velocity rule for its first ten minutes.
        superuser.sql("UPDATE payments SET created_at = created_at - interval '15 minutes' WHERE created_at > now() - interval '15 minutes'").update();
        RiskService risk = context.getBean(RiskService.class);
        risk.addRecommended(tenantId);
        PaymentService payments = context.getBean(PaymentService.class);
        payments.create(tenantId, key(), 1_450_000, "TRY", "tok_success_kurumsal", "Kurumsal toplu sipariş", null);
        for (int i = 0; i < 7; i++) {
            payments.create(tenantId, key(), 4_900, "TRY", "tok_success_deneme", "Hediye kartı", null);
        }
        UUID stolen = payments.create(tenantId, key(), 12_000, "TRY", "tok_success_calinti", "Espresso makinesi", null).payment().id();
        risk.blockCardOf(tenantId, stolen, "Kart sahibi kartın çalındığını bildirdi");
    }

    /** A viewer account shows what limited rights look like, and an open invitation fills out the team page. */
    private static void seedTeam(ConfigurableApplicationContext context, UUID tenantId, JdbcClient superuser) {
        TeamService team = context.getBean(TeamService.class);
        UUID ownerId = superuser.sql("SELECT id FROM users WHERE tenant_id = :t").param("t", tenantId).query(UUID.class).single();
        team.accept(team.invite(tenantId, ownerId, "izleyici@kasa.test", "VIEWER").token(), DEMO_PASSWORD);
        // A request waiting for the platform administrator, so the admin panel's requests tab has something to decide.
        UUID viewerId = superuser.sql("SELECT id FROM users WHERE email = 'izleyici@kasa.test'").query(UUID.class).single();
        context.getBean(RoleRequestService.class).submit(tenantId, viewerId, "ADMIN", "Muhasebeyi ben yürütüyorum, iade yapabilmem gerekiyor.");
        team.invite(tenantId, ownerId, "yeni-yonetici@kasa.test", "ADMIN");
    }

    /** One dispute at every stage, so each screen has something to show without any setup. */
    private static void seedCustomersLinksAndDisputes(ConfigurableApplicationContext context, UUID tenantId, JdbcClient superuser) {
        PaymentService payments = context.getBean(PaymentService.class);
        CustomerService customers = context.getBean(CustomerService.class);
        DisputeService disputes = context.getBean(DisputeService.class);

        UUID ayse = customers.create(tenantId, "ayse@kasa.test", "Ayşe Yılmaz").id();
        UUID mehmet = customers.create(tenantId, "mehmet@kasa.test", "Mehmet Demir").id();
        customers.create(tenantId, "zeynep@kasa.test", "Zeynep Kaya");

        UUID needsResponse = customerPayment(payments, tenantId, ayse, 89_900, "Espresso makinesi");
        customerPayment(payments, tenantId, ayse, 24_900, "Çekirdek kahve aboneliği");
        UUID underReview = customerPayment(payments, tenantId, mehmet, 45_000, "Filtre kahve seti");
        UUID won = customerPayment(payments, tenantId, mehmet, 32_500, "Hediye kartı");
        UUID lost = customerPayment(payments, tenantId, mehmet, 15_000, "Toplu sipariş");

        disputes.open(tenantId, needsResponse, null, "PRODUCT_NOT_RECEIVED");
        UUID reviewed = disputes.open(tenantId, underReview, null, "PRODUCT_UNACCEPTABLE").id();
        disputes.submitEvidence(tenantId, reviewed, new DisputeService.Evidence("Ürün sağlam gönderildi.", null, null));
        UUID wonId = disputes.open(tenantId, won, null, "DUPLICATE").id();
        disputes.submitEvidence(tenantId, wonId, new DisputeService.Evidence("Tek bir tahsilat yapıldı ve ürün teslim edildi.", "KARGO-48213", null));
        superuser.sql("UPDATE disputes SET evidence_submitted_at = now() - interval '1 hour' WHERE id = :id").param("id", wonId).update();
        disputes.resolveDue(java.time.Duration.ofMinutes(30));
        disputes.accept(tenantId, disputes.open(tenantId, lost, null, "FRAUDULENT").id());

        PaymentLinkService links = context.getBean(PaymentLinkService.class);
        links.create(tenantId, new PaymentLinkService.NewLink("Kahve demleme atölyesi", "Cumartesi 14.00, Kadıköy", 75_000L, "TRY", 12, null));
        links.create(tenantId, new PaymentLinkService.NewLink("Kahve fonuna destek", "Dilediğiniz kadar", null, "TRY", null, null));

        BillingService billing = context.getBean(BillingService.class);
        UUID box = billing.createPlan(tenantId, "Aylık kahve kutusu", 24_900, "TRY", "MONTH").id();
        billing.createPlan(tenantId, "Büyük kahve kutusu", 44_900, "TRY", "MONTH");
        UUID yearly = billing.createPlan(tenantId, "Yıllık üyelik", 249_000, "TRY", "YEAR").id();
        billing.subscribe(tenantId, ayse, box, "tok_success");
        billing.subscribe(tenantId, mehmet, yearly, "tok_success");
        UUID can = customers.create(tenantId, "can@kasa.test", "Can Öztürk").id();
        UUID pastDue = billing.subscribe(tenantId, can, box, "tok_success").subscription().id();
        billing.updateCard(tenantId, pastDue, "tok_declined");
        billing.simulateRenewal(tenantId, pastDue);
    }

    private static UUID customerPayment(PaymentService payments, UUID tenantId, UUID customerId, long amount, String description) {
        UUID id = payments.create(tenantId, key(), amount, "TRY", "tok_success", description, customerId).payment().id();
        capture(payments, tenantId, id);
        return id;
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
        context.getBean(MerchantProfileService.class).save(tenantId, new MerchantProfileService.ProfileInput("COMPANY",
                "Demo Kahve Gıda Ltd. Şti.", "1234567890", "Kadıköy", "ECOMMERCE", "https://demokahve.example", "+90 216 555 01 01",
                "Caferağa Mah. Moda Cd. No: 12", "İstanbul", "TR33 0006 1005 1978 6457 8413 26"));
        context.getBean(MerchantReviewService.class).approve("demo-kurulumu", null, tenantId);
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
