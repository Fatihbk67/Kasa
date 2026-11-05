package com.kasa.demo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.admin.MerchantReviewService;
import com.kasa.auth.AuthService;
import com.kasa.checkout.PaymentLinkService;
import com.kasa.customer.CustomerService;
import com.kasa.dispute.DisputeService;
import com.kasa.onboarding.MerchantProfileService;
import com.kasa.payment.PaymentService;
import com.kasa.settlement.SettlementService;

/**
 * The sample business behind "try the demo". Visitors sign in as its viewer, a role that can look at everything
 * money-related but move nothing; {@link DemoReadOnlyFilter} also stops the few changes a viewer could make to
 * their own account, since every visitor shares it.
 *
 * <p>The addresses end in {@code .invalid}, a domain reserved never to exist, and the mailer drops them, so
 * seeding and visitors can never send email anywhere. History is back-dated through the database owner's
 * connection, the same one migrations use; the application's own role is never given that power.
 */
@Service
public class DemoAccountService {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountService.class);

    public static final String OWNER_EMAIL = "sahip@demo.invalid";
    public static final String VISITOR_EMAIL = "ziyaretci@demo.invalid";
    private static final String BUSINESS_NAME = "Demo Kahve";
    private static final int HISTORY_DAYS = 45;
    private static final List<String> DESCRIPTIONS = List.of("Çekirdek kahve aboneliği", "Filtre kahve seti", "Toplu sipariş",
            "Espresso makinesi", "Hediye kartı", "Kurumsal sipariş");

    private record Visitor(UUID id, UUID tenantId, int tokenVersion) {
    }

    private final DemoProperties properties;
    private final JdbcClient owner;
    private final AuthService auth;
    private final PasswordEncoder passwordEncoder;
    private final MerchantProfileService profiles;
    private final MerchantReviewService reviews;
    private final PaymentService payments;
    private final CustomerService customers;
    private final DisputeService disputes;
    private final PaymentLinkService links;
    private final SettlementService settlement;

    public DemoAccountService(DemoProperties properties,
                              @Value("${spring.flyway.url:${spring.datasource.url}}") String url,
                              @Value("${spring.flyway.user}") String ownerUser,
                              @Value("${spring.flyway.password}") String ownerPassword,
                              AuthService auth, PasswordEncoder passwordEncoder, MerchantProfileService profiles,
                              MerchantReviewService reviews, PaymentService payments, CustomerService customers,
                              DisputeService disputes, PaymentLinkService links, SettlementService settlement) {
        this.properties = properties;
        DataSource ownerSource = new DriverManagerDataSource(url, ownerUser, ownerPassword);
        this.owner = JdbcClient.create(ownerSource);
        this.auth = auth;
        this.passwordEncoder = passwordEncoder;
        this.profiles = profiles;
        this.reviews = reviews;
        this.payments = payments;
        this.customers = customers;
        this.disputes = disputes;
        this.links = links;
        this.settlement = settlement;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** A session as the demo's viewer. */
    public AuthService.TokenResponse signIn() {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "the demo is not available");
        }
        Visitor visitor = visitor().orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "the demo is being prepared"));
        return auth.token(visitor.id(), visitor.tenantId(), visitor.tokenVersion());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prepare() {
        if (!properties.enabled() || visitor().isPresent()) {
            return;
        }
        try {
            seed();
            log.info("Demo business ready");
        } catch (RuntimeException e) {
            // The rest of the application works without the demo; the button just stays unavailable.
            log.error("Could not prepare the demo business", e);
        }
    }

    private Optional<Visitor> visitor() {
        return owner.sql("SELECT id, tenant_id, token_version FROM users WHERE email = :email AND disabled_at IS NULL")
                .param("email", VISITOR_EMAIL)
                .query((rs, rowNum) -> new Visitor(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getInt("token_version")))
                .optional();
    }

    private void seed() {
        UUID existing = owner.sql("SELECT tenant_id FROM users WHERE email = :email")
                .param("email", OWNER_EMAIL)
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (existing != null) {
            // An earlier start stopped part way; what is there is shown rather than seeded twice.
            addVisitor(existing);
            return;
        }
        UUID tenantId = createBusiness();
        seedHistory(tenantId, HISTORY_DAYS, 1);
        // Pays out the history, so the payouts page has a payout; what follows stays as balance.
        settlement.settleAll(Instant.now());
        seedCustomersAndDisputes(tenantId);
        seedHistory(tenantId, 0, 0);
        links.create(tenantId, new PaymentLinkService.NewLink("Kahve demleme atölyesi", "Cumartesi 14.00, Kadıköy", 75_000L, "TRY", 12, null));
        links.create(tenantId, new PaymentLinkService.NewLink("Kahve fonuna destek", "Dilediğiniz kadar", null, "TRY", null, null));
        // Authorised but not yet captured, so the dashboard's to-do list has something in it.
        payments.create(tenantId, key(), 76_500, "TRY", "tok_success", "Toplu sipariş");
        // Last, so the button opens only a fully stocked business.
        addVisitor(tenantId);
    }

    private UUID createBusiness() {
        UUID tenantId = auth.registerVerified(BUSINESS_NAME, OWNER_EMAIL, "demo").tenantId();
        String status = owner.sql("SELECT status FROM tenants WHERE id = :id").param("id", tenantId).query(String.class).single();
        if (!status.equals("PENDING")) {
            // Where review is switched off, a new business starts approved and needs no application.
            return tenantId;
        }
        profiles.save(tenantId, new MerchantProfileService.ProfileInput("COMPANY", "Demo Kahve Gıda Ltd. Şti.", "1234567890",
                "Kadıköy", "ECOMMERCE", "https://demokahve.example", "+90 216 555 01 01", "Caferağa Mah. Moda Cd. No: 12",
                "İstanbul", "TR33 0006 1005 1978 6457 8413 26"));
        reviews.approve("demo-kurulumu", null, tenantId);
        return tenantId;
    }

    /** Nobody knows this password: the only way in is the demo button. */
    private void addVisitor(UUID tenantId) {
        String unusable = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        owner.sql("""
                        INSERT INTO users (tenant_id, email, password_hash, role, email_verified_at)
                        VALUES (:tenantId, :email, :hash, 'VIEWER', now())
                        """)
                .param("tenantId", tenantId)
                .param("email", VISITOR_EMAIL)
                .param("hash", passwordEncoder.encode(unusable))
                .update();
    }

    /** Weeks of ordinary trade, growing a little over time, with the occasional decline and partial refund. */
    private void seedHistory(UUID tenantId, int firstDaysAgo, int lastDaysAgo) {
        Random random = new Random(42 + firstDaysAgo);
        for (int daysAgo = firstDaysAgo; daysAgo >= lastDaysAgo; daysAgo--) {
            int count = 2 + random.nextInt(4) + (HISTORY_DAYS - daysAgo) / 12;
            for (int i = 0; i < count; i++) {
                int roll = random.nextInt(100);
                String token = roll < 7 ? "tok_insufficient_funds" : roll < 11 ? "tok_declined" : "tok_success";
                long amount = 5_000 + random.nextInt(245_000);
                UUID id = payments.create(tenantId, key(), amount, "TRY", token, DESCRIPTIONS.get(random.nextInt(DESCRIPTIONS.size())))
                        .payment().id();
                if (token.equals("tok_success") && random.nextInt(100) < 90) {
                    payments.capture(tenantId, id, key(), null);
                    if (random.nextInt(100) < 8) {
                        payments.refund(tenantId, id, key(), amount / 2);
                    }
                }
                if (daysAgo > 0) {
                    backdate(id, daysAgo, 8 + random.nextInt(12));
                }
            }
        }
    }

    private void seedCustomersAndDisputes(UUID tenantId) {
        UUID ayse = customers.create(tenantId, "ayse@demo.invalid", "Ayşe Yılmaz").id();
        UUID mehmet = customers.create(tenantId, "mehmet@demo.invalid", "Mehmet Demir").id();
        customers.create(tenantId, "zeynep@demo.invalid", "Zeynep Kaya");

        UUID needsResponse = customerPayment(tenantId, ayse, 89_900, "Espresso makinesi");
        customerPayment(tenantId, ayse, 24_900, "Çekirdek kahve aboneliği");
        UUID underReview = customerPayment(tenantId, mehmet, 45_000, "Filtre kahve seti");
        UUID lost = customerPayment(tenantId, mehmet, 15_000, "Toplu sipariş");

        disputes.open(tenantId, needsResponse, null, "PRODUCT_NOT_RECEIVED");
        UUID reviewed = disputes.open(tenantId, underReview, null, "PRODUCT_UNACCEPTABLE").id();
        disputes.submitEvidence(tenantId, reviewed, new DisputeService.Evidence("Ürün sağlam gönderildi.", "KARGO-48213", null));
        disputes.accept(tenantId, disputes.open(tenantId, lost, null, "FRAUDULENT").id());
    }

    private UUID customerPayment(UUID tenantId, UUID customerId, long amount, String description) {
        UUID id = payments.create(tenantId, key(), amount, "TRY", "tok_success", description, customerId).payment().id();
        payments.capture(tenantId, id, key(), null);
        return id;
    }

    /**
     * Moves the payment and its steps to that local day and hour, so "yesterday" is the merchant's yesterday whatever
     * time the seed runs. Everything shifts by the same amount, so the steps keep their order and spacing.
     */
    private void backdate(UUID paymentId, int days, int hour) {
        String shift = "((((now() AT TIME ZONE 'Europe/Istanbul')::date - :d)::timestamp + make_interval(hours => :h))"
                + " AT TIME ZONE 'Europe/Istanbul') - (SELECT created_at FROM payments WHERE id = :id)";
        // The steps first: the shift is measured from the payment's own time, which the second update changes.
        owner.sql("UPDATE payment_operations SET created_at = created_at + (" + shift + "), updated_at = updated_at + (" + shift + ")"
                        + " WHERE payment_id = :id")
                .param("d", days).param("h", hour).param("id", paymentId).update();
        owner.sql("UPDATE payments SET created_at = created_at + (" + shift + "), updated_at = updated_at + (" + shift + ") WHERE id = :id")
                .param("d", days).param("h", hour).param("id", paymentId).update();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
