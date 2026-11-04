package com.kasa.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.kasa.billing.BillingService;
import com.kasa.customer.CustomerService;
import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class RiskApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Value("${local.server.port}")
    int port;

    @Autowired
    BillingService billing;

    @Autowired
    CustomerService customers;

    RestClient client;
    TestMerchant merchant;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
        merchant = TestMerchant.register(port);
    }

    @Test
    void withoutRulesEveryPaymentGoesToTheBank() {
        Map<String, Object> payment = create(10_000, "tok_success");

        assertThat(payment).containsEntry("status", "AUTHORIZED").containsEntry("riskLevel", "NORMAL")
                .containsEntry("reviewStatus", null);
    }

    @Test
    void aCardTriedTooOftenIsBlockedWithoutAskingTheBank() {
        rule("CARD_VELOCITY", 3, 10, null, "BLOCK");
        for (int i = 0; i < 3; i++) {
            assertThat(create(1_000, "tok_success")).containsEntry("status", "AUTHORIZED");
        }

        Map<String, Object> blocked = create(1_000, "tok_success");

        assertThat(blocked).containsEntry("status", "DECLINED").containsEntry("failureCode", "risk_blocked")
                .containsEntry("riskLevel", "BLOCKED").containsEntry("riskReasons", List.of("CARD_VELOCITY"));
        assertThat(owner.sql("""
                        SELECT COUNT(*) FROM sim_bank_operations b JOIN payment_operations o ON o.id = b.operation_id
                        WHERE o.payment_id = :id
                        """).param("id", UUID.fromString((String) blocked.get("id"))).query(Long.class).single()).isZero();
        // Another card of the same business is not affected.
        assertThat(create(1_000, "tok_success_other")).containsEntry("status", "AUTHORIZED");
    }

    @Test
    void aBurstFromOneCardCannotSlipPastTheLimit() throws Exception {
        rule("CARD_VELOCITY", 5, 10, null, "BLOCK");

        List<String> statuses = Concurrency.run(20, index -> () -> (String) create(1_000, "tok_success").get("status"));

        assertThat(statuses.stream().filter("AUTHORIZED"::equals).count()).isEqualTo(5);
        assertThat(statuses.stream().filter("DECLINED"::equals).count()).isEqualTo(15);
    }

    @Test
    void repeatedBankDeclinesBlockTheCardButBlocksDoNotFeedThemselves() {
        rule("CARD_DECLINES", 2, 60, null, "BLOCK");
        assertThat(create(1_000, "tok_declined")).containsEntry("failureCode", "card_declined");
        assertThat(create(1_000, "tok_declined")).containsEntry("failureCode", "card_declined");

        assertThat(create(1_000, "tok_declined")).containsEntry("failureCode", "risk_blocked")
                .containsEntry("riskReasons", List.of("CARD_DECLINES"));
        assertThat(owner.sql("SELECT COUNT(*) FROM payments WHERE tenant_id = :t AND failure_code = 'card_declined'")
                .param("t", merchant.tenantId()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void blockedAttemptsAreNotCountedAsBankDeclines() {
        rule("CARD_DECLINES", 2, 60, null, "BLOCK");
        String first = (String) create(500, "tok_success_reported").get("id");
        dashboard(HttpMethod.POST, "/api/v1/risk/blocklist", Map.of("paymentId", first, "reason", "Yanlış alarm"));
        assertThat(create(500, "tok_success_reported")).containsEntry("failureCode", "risk_blocked");
        assertThat(create(500, "tok_success_reported")).containsEntry("failureCode", "risk_blocked");

        String fingerprint = (String) listOf("/api/v1/risk/blocklist").getFirst().get("fingerprint");
        client.delete().uri("/api/v1/risk/blocklist/" + fingerprint).header("Authorization", "Bearer " + merchant.token())
                .retrieve().toBodilessEntity();

        // Two refusals of our own are on record, but the bank has declined this card zero times.
        assertThat(create(500, "tok_success_reported")).containsEntry("status", "AUTHORIZED");
    }

    @Test
    void aHeldPaymentFromALinkIsNotCollectedUntilReleased() {
        rule("AMOUNT_ABOVE", 100, null, "TRY", "REVIEW");
        Map<String, Object> body = new HashMap<>(Map.of("title", "Atölye", "amount", 7_500, "currency", "TRY"));
        Map<String, Object> link = merchant.post("/api/v1/payment-links", body);
        Map<String, Object> session = client.post().uri("/api/v1/public/links/" + link.get("slug") + "/sessions")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("email", "katilimci@example.com", "name", "Katılımcı"))
                .retrieve().body(Map.class);

        Map<String, Object> paid = client.post().uri("/api/v1/public/checkout/" + session.get("id") + "/pay")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("cardToken", "tok_success")).retrieve().body(Map.class);

        assertThat(paid).containsEntry("status", "COMPLETED");
        assertThat(queue()).singleElement().satisfies(payment -> assertThat(payment).containsEntry("status", "AUTHORIZED"));
    }

    @Test
    void aLargePaymentIsHeldUntilAPersonReleasesIt() {
        rule("AMOUNT_ABOVE", 50_000, null, "TRY", "REVIEW");
        assertThat(create(50_000, "tok_success")).containsEntry("reviewStatus", null);

        Map<String, Object> held = create(50_001, "tok_success");
        String id = (String) held.get("id");

        assertThat(held).containsEntry("status", "AUTHORIZED").containsEntry("riskLevel", "REVIEW").containsEntry("reviewStatus", "OPEN");
        assertThat(status(client.post().uri("/api/v1/payments/" + id + "/capture").header("X-Api-Key", merchant.apiKey())
                .header("Idempotency-Key", UUID.randomUUID().toString()))).isEqualTo(409);
        assertThat(queue()).extracting(payment -> payment.get("id")).containsExactly(id);
        assertThat(owner.sql("SELECT COUNT(*) FROM notifications WHERE tenant_id = :t AND kind = 'PAYMENT_HELD'")
                .param("t", merchant.tenantId()).query(Long.class).single()).isEqualTo(1);

        Map<String, Object> released = dashboard(HttpMethod.POST, "/api/v1/risk/reviews/" + id + "/approve", Map.of("capture", true));

        assertThat(released).containsEntry("status", "CAPTURED").containsEntry("reviewStatus", "APPROVED");
        assertThat((String) released.get("reviewedBy")).endsWith("@example.com");
        assertThat(queue()).isEmpty();
    }

    @Test
    void rejectingCancelsTheAuthorizationAndCanBlockTheCard() {
        rule("AMOUNT_ABOVE", 100, null, "TRY", "REVIEW");
        String id = (String) create(5_000, "tok_success_stolen").get("id");

        Map<String, Object> rejected = dashboard(HttpMethod.POST, "/api/v1/risk/reviews/" + id + "/reject", Map.of("blockCard", true));

        assertThat(rejected).containsEntry("status", "VOIDED").containsEntry("reviewStatus", "REJECTED");
        assertThat(create(50, "tok_success_stolen")).containsEntry("failureCode", "risk_blocked").containsEntry("riskReasons", List.of("CARD_BLOCKLISTED"));
        assertThat(listOf("/api/v1/risk/blocklist")).singleElement().satisfies(card -> assertThat(card).containsEntry("sourcePayment", id));
    }

    @Test
    void aDecisionIsMadeOnce() throws Exception {
        rule("AMOUNT_ABOVE", 100, null, "TRY", "REVIEW");
        String id = (String) create(5_000, "tok_success").get("id");

        List<Integer> results = Concurrency.run(4, index -> () -> status(client.post()
                .uri("/api/v1/risk/reviews/" + id + (index % 2 == 0 ? "/approve" : "/reject"))
                .header("Authorization", "Bearer " + merchant.token())));

        assertThat(results).containsOnlyOnce(200);
        assertThat(results.stream().filter(code -> code == 409).count()).isEqualTo(3);
    }

    @Test
    void theDatabaseRefusesToCaptureAHeldPayment() {
        rule("AMOUNT_ABOVE", 100, null, "TRY", "REVIEW");
        String id = (String) create(5_000, "tok_success").get("id");

        assertThatThrownBy(() -> owner.sql("UPDATE payments SET status = 'CAPTURED', captured_amount = amount WHERE id = :id")
                .param("id", UUID.fromString(id)).update()).hasMessageContaining("held for risk review");
    }

    @Test
    void aHoldOnADeclinedPaymentLeavesTheQueue() {
        rule("AMOUNT_ABOVE", 100, null, "TRY", "REVIEW");

        assertThat(create(5_000, "tok_declined")).containsEntry("riskLevel", "REVIEW").containsEntry("reviewStatus", null);
        assertThat(queue()).isEmpty();
        assertThat(owner.sql("SELECT COUNT(*) FROM notifications WHERE tenant_id = :t AND kind = 'PAYMENT_HELD'")
                .param("t", merchant.tenantId()).query(Long.class).single()).isZero();
    }

    @Test
    void aRenewalIsNotHeldButABlockedCardIsStillRefused() {
        rule("AMOUNT_ABOVE", 100, null, "TRY", "REVIEW");
        UUID customer = customers.create(merchant.tenantId(), "abone-" + UUID.randomUUID() + "@example.com", "Abone").id();
        UUID plan = billing.createPlan(merchant.tenantId(), "Aylık", 24_900, "TRY", "MONTH").id();

        assertThat(billing.subscribe(merchant.tenantId(), customer, plan, "tok_success").subscription().status()).isEqualTo("ACTIVE");

        String paid = (String) create(500, "tok_success_lost").get("id");
        dashboard(HttpMethod.POST, "/api/v1/risk/reviews/" + paid + "/reject", Map.of("blockCard", true));
        UUID second = customers.create(merchant.tenantId(), "abone-" + UUID.randomUUID() + "@example.com", "İkinci").id();
        assertThat(billing.subscribe(merchant.tenantId(), second, plan, "tok_success_lost").subscription().status()).isNotEqualTo("ACTIVE");
    }

    @Test
    void aBusinessStartsWithTheRecommendedRulesOnce() {
        List<Map<String, Object>> rules = client.post().uri("/api/v1/risk/rules/recommended").header("Authorization", "Bearer " + merchant.token())
                .retrieve().body(List.class);

        assertThat(rules).extracting(rule -> rule.get("kind")).containsExactly("CARD_VELOCITY", "CARD_DECLINES", "AMOUNT_ABOVE");
        assertThat(status(client.post().uri("/api/v1/risk/rules/recommended").header("Authorization", "Bearer " + merchant.token())))
                .isEqualTo(409);
    }

    @Test
    void aRuleCanBeTunedOrSwitchedOff() {
        String id = (String) rule("CARD_VELOCITY", 1, 10, null, "BLOCK").get("id");
        create(1_000, "tok_success");
        assertThat(create(1_000, "tok_success")).containsEntry("status", "DECLINED");

        Map<String, Object> body = new HashMap<>(Map.of("kind", "CARD_VELOCITY", "threshold", 1, "windowMinutes", 10, "action", "BLOCK", "enabled", false));
        assertThat(dashboard(HttpMethod.PUT, "/api/v1/risk/rules/" + id, body)).containsEntry("enabled", false);

        assertThat(create(1_000, "tok_success")).containsEntry("status", "AUTHORIZED");
        assertThat(owner.sql("SELECT COUNT(*) FROM audit_log WHERE tenant_id = :t AND action LIKE 'RISK_RULE_%'")
                .param("t", merchant.tenantId()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void nonsenseRulesAreRefused() {
        assertThat(ruleStatus(Map.of("kind", "CARD_VELOCITY", "threshold", 5, "action", "BLOCK"))).isEqualTo(422);
        assertThat(ruleStatus(Map.of("kind", "AMOUNT_ABOVE", "threshold", 5, "action", "BLOCK"))).isEqualTo(422);
        assertThat(ruleStatus(Map.of("kind", "COUNTRY", "threshold", 5, "windowMinutes", 5, "action", "BLOCK"))).isEqualTo(422);
        assertThat(ruleStatus(Map.of("kind", "CARD_VELOCITY", "threshold", 0, "windowMinutes", 5, "action", "BLOCK"))).isEqualTo(422);
        assertThat(ruleStatus(Map.of("kind", "CARD_VELOCITY", "threshold", 5, "windowMinutes", 5, "action", "ALLOW"))).isEqualTo(422);
    }

    @Test
    void anApiKeyOrAViewerCannotTouchTheRules() {
        assertThat(status(client.get().uri("/api/v1/risk/rules").header("X-Api-Key", merchant.apiKey()))).isEqualTo(403);
        assertThat(status(client.post().uri("/api/v1/risk/rules/recommended").header("X-Api-Key", merchant.apiKey()))).isEqualTo(403);

        Map<String, Object> invitation = client.post().uri("/api/v1/team/invitations").header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("email", "izleyen-" + UUID.randomUUID() + "@example.com", "role", "VIEWER"))
                .retrieve().body(Map.class);
        String viewer = (String) client.post().uri("/api/v1/public/invitations/" + invitation.get("token") + "/accept")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("password", PASSWORD)).retrieve().body(Map.class).get("accessToken");
        assertThat(status(client.get().uri("/api/v1/risk/reviews").header("Authorization", "Bearer " + viewer))).isEqualTo(403);
    }

    @Test
    void oneBusinessesRulesDoNotReachAnother() {
        rule("CARD_VELOCITY", 1, 10, null, "BLOCK");
        TestMerchant other = TestMerchant.register(port);

        for (int i = 0; i < 3; i++) {
            Map<String, Object> result = other.post("/api/v1/payments", Map.of("amount", 1_000, "currency", "TRY", "cardToken", "tok_success"));
            assertThat((Map<String, Object>) result.get("payment")).containsEntry("status", "AUTHORIZED");
        }
        assertThat(status(client.get().uri("/api/v1/risk/rules").header("Authorization", "Bearer " + other.token()))).isEqualTo(200);
        assertThat(client.get().uri("/api/v1/risk/rules").header("Authorization", "Bearer " + other.token()).retrieve().body(List.class)).isEmpty();
    }

    private Map<String, Object> create(long amount, String cardToken) {
        Map<String, Object> result = merchant.post("/api/v1/payments", Map.of("amount", amount, "currency", "TRY", "cardToken", cardToken));
        return (Map<String, Object>) result.get("payment");
    }

    private Map<String, Object> rule(String kind, long threshold, Integer window, String currency, String action) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("threshold", threshold);
        body.put("windowMinutes", window);
        body.put("currency", currency);
        body.put("action", action);
        return dashboard(HttpMethod.POST, "/api/v1/risk/rules", body);
    }

    private int ruleStatus(Map<String, Object> body) {
        return status(client.post().uri("/api/v1/risk/rules").header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private Map<String, Object> dashboard(HttpMethod method, String path, Object body) {
        return client.method(method).uri(path).header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    }

    private List<Map<String, Object>> queue() {
        return listOf("/api/v1/risk/reviews");
    }

    private List<Map<String, Object>> listOf(String path) {
        return client.get().uri(path).header("Authorization", "Bearer " + merchant.token()).retrieve().body(List.class);
    }

    private static int status(RestClient.RequestHeadersSpec<?> request) {
        return TestMerchant.statusOf(request);
    }
}
