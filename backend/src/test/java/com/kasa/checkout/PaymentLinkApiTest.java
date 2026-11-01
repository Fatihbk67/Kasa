package com.kasa.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

/** The public endpoints are called with no credentials at all, as a visitor's browser does. */
class PaymentLinkApiTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    TestMerchant merchant;
    RestClient visitor;

    @BeforeEach
    void setUp() {
        merchant = TestMerchant.register(port);
        visitor = RestClient.create("http://localhost:" + port);
    }

    @Test
    void aVisitorSeesTheLinkButNotTheTenant() {
        Map<String, Object> link = createLink(25_000, null);

        Map<String, Object> shown = visitor.get().uri("/api/v1/public/links/" + link.get("slug")).retrieve().body(Map.class);

        assertThat(shown).containsEntry("merchantName", "Shop").containsEntry("title", "Kurs").containsEntry("status", "OPEN");
        assertThat(shown).doesNotContainKeys("tenantId", "id");
    }

    @Test
    void anUnknownSlugIsNotFound() {
        assertThat(TestMerchant.statusOf(visitor.get().uri("/api/v1/public/links/yok")))
                .isEqualTo(404);
    }

    @Test
    void payingThroughALinkCollectsTheMoneyAndRemembersTheCustomer() {
        Map<String, Object> link = createLink(25_000, null);

        String session = startSession(link, "ayse@example.com", "Ayşe", null);
        assertThat(pay(session, "tok_success")).isEqualTo(200);

        Map<String, Object> payment = onlyPayment();
        assertThat(payment).containsEntry("status", "CAPTURED").containsEntry("description", "Kurs");
        Map<String, Object> customer = merchant.client().get().uri("/api/v1/customers/" + payment.get("customerId"))
                .header("X-Api-Key", merchant.apiKey()).retrieve().body(Map.class);
        assertThat(customer).containsEntry("email", "ayse@example.com").containsEntry("name", "Ayşe");

        Map<String, Object> listed = links().getFirst();
        assertThat(number(listed, "paidCount")).isEqualTo(1);
        assertThat(number(listed, "collected")).isEqualTo(25_000);
    }

    @Test
    void aVisitorCannotRenameAnExistingCustomer() {
        Map<String, Object> link = createLink(1_000, null);
        startSession(link, "ayse@example.com", "Ayşe", null);
        startSession(link, "AYSE@example.com", "Sahte Ad", null);

        List<Map<String, Object>> customers = (List<Map<String, Object>>) merchant.client().get().uri("/api/v1/customers")
                .header("X-Api-Key", merchant.apiKey()).retrieve().body(Map.class).get("items");

        assertThat(customers).hasSize(1);
        assertThat(customers.getFirst()).containsEntry("name", "Ayşe");
    }

    @Test
    void anEmptyAmountLinkTakesTheVisitorsAmount() {
        Map<String, Object> link = createLink(null, null);

        assertThat(startStatus(link, Map.of("email", "a@example.com"))).isEqualTo(422);
        String session = startSession(link, "a@example.com", null, 7_500L);

        Map<String, Object> shown = visitor.get().uri("/api/v1/public/checkout/" + session).retrieve().body(Map.class);
        assertThat(number(shown, "amount")).isEqualTo(7_500);
    }

    @Test
    void aFixedAmountCannotBeChangedByTheVisitor() {
        Map<String, Object> link = createLink(25_000, null);

        assertThat(startStatus(link, Map.of("email", "a@example.com", "amount", 1))).isEqualTo(422);
    }

    @Test
    void oneLastPlaceSellsOnceHoweverManyPayAtOnce() throws Exception {
        Map<String, Object> link = createLink(1_000, 1);
        List<String> sessions = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sessions.add(startSession(link, "c" + i + "@example.com", null, null));
        }

        List<Integer> results = Concurrency.run(20, index -> () -> pay(sessions.get(index), "tok_success"));

        assertThat(results.stream().filter(status -> status == 200)).hasSize(1);
        assertThat(results.stream().filter(status -> status == 410)).hasSize(19);
        assertThat(payments()).hasSize(1);
        assertThat(links().getFirst()).containsEntry("status", "SOLD_OUT");
        assertThat(startStatus(link, Map.of("email", "late@example.com"))).isEqualTo(410);
    }

    @Test
    void placesAreCountedPerLink() throws Exception {
        Map<String, Object> link = createLink(1_000, 3);
        List<String> sessions = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sessions.add(startSession(link, "c" + i + "@example.com", null, null));
        }

        List<Integer> results = Concurrency.run(10, index -> () -> pay(sessions.get(index), "tok_success"));

        assertThat(results.stream().filter(status -> status == 200)).hasSize(3);
    }

    @Test
    void aDeclinedCardGivesThePlaceBack() {
        Map<String, Object> link = createLink(1_000, 1);
        String first = startSession(link, "a@example.com", null, null);
        String second = startSession(link, "b@example.com", null, null);

        assertThat(pay(first, "tok_declined")).isEqualTo(200);
        assertThat(pay(second, "tok_success")).isEqualTo(200);

        assertThat(links().getFirst()).containsEntry("status", "SOLD_OUT");
    }

    @Test
    void aSessionStuckMidPaymentStopsHoldingAPlaceWhenItExpires() {
        Map<String, Object> link = createLink(1_000, 1);
        UUID stuck = UUID.fromString(startSession(link, "a@example.com", null, null));
        owner.sql("UPDATE checkout_sessions SET status = 'PROCESSING' WHERE id = :id").param("id", stuck).update();
        assertThat(startStatus(link, Map.of("email", "b@example.com"))).isEqualTo(410);

        owner.sql("UPDATE checkout_sessions SET expires_at = now() - interval '1 minute' WHERE id = :id").param("id", stuck).update();

        assertThat(startStatus(link, Map.of("email", "b@example.com"))).isEqualTo(201);
    }

    @Test
    void aBankTimeoutHoldsThePlaceUntilTheOutcomeIsKnown() {
        Map<String, Object> link = createLink(1_000, 1);
        String first = startSession(link, "a@example.com", null, null);
        assertThat(pay(first, "tok_timeout_processed")).isEqualTo(200);

        assertThat(startStatus(link, Map.of("email", "b@example.com"))).isEqualTo(410);
    }

    @Test
    void aDisabledLinkStopsEvenSessionsAlreadyOpen() {
        Map<String, Object> link = createLink(1_000, null);
        String session = startSession(link, "a@example.com", null, null);

        merchant.post("/api/v1/payment-links/" + link.get("id") + "/disable", null);

        assertThat(pay(session, "tok_success")).isEqualTo(410);
        assertThat(startStatus(link, Map.of("email", "b@example.com"))).isEqualTo(410);
        assertThat(visitor.get().uri("/api/v1/public/links/" + link.get("slug")).retrieve().body(Map.class))
                .containsEntry("status", "DISABLED");
        assertThat(payments()).isEmpty();
    }

    @Test
    void anExpiredLinkTakesNoPayments() {
        Map<String, Object> link = createLink(1_000, null);
        String session = startSession(link, "a@example.com", null, null);

        owner.sql("UPDATE payment_links SET expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", UUID.fromString((String) link.get("id"))).update();

        assertThat(pay(session, "tok_success")).isEqualTo(410);
        assertThat(links().getFirst()).containsEntry("status", "EXPIRED");
    }

    @Test
    void anotherMerchantCannotDisableALink() {
        Map<String, Object> link = createLink(1_000, null);
        TestMerchant other = TestMerchant.register(port);

        assertThat(TestMerchant.statusOf(other.client().post().uri("/api/v1/payment-links/" + link.get("id") + "/disable")
                .header("X-Api-Key", other.apiKey()))).isEqualTo(404);
        assertThat(links().getFirst()).containsEntry("status", "OPEN");
    }

    @Test
    void creatingALinkNeedsCredentials() {
        assertThat(TestMerchant.statusOf(visitor.post().uri("/api/v1/payment-links").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("title", "x", "currency", "TRY")))).isEqualTo(401);
    }

    @Test
    void slugsAreLongAndDifferent() {
        String first = (String) createLink(1_000, null).get("slug");
        String second = (String) createLink(1_000, null).get("slug");

        assertThat(first).hasSize(16).isNotEqualTo(second);
    }

    private Map<String, Object> createLink(Integer amount, Integer maxPayments) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Kurs");
        body.put("amount", amount);
        body.put("currency", "TRY");
        body.put("maxPayments", maxPayments);
        return merchant.post("/api/v1/payment-links", body);
    }

    private String startSession(Map<String, Object> link, String email, String name, Long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("name", name);
        body.put("amount", amount);
        Map<String, Object> created = visitor.post().uri("/api/v1/public/links/" + link.get("slug") + "/sessions")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
        return (String) created.get("id");
    }

    private int startStatus(Map<String, Object> link, Map<String, Object> body) {
        return visitor.post().uri("/api/v1/public/links/" + link.get("slug") + "/sessions")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private int pay(String session, String token) {
        return visitor.post().uri("/api/v1/public/checkout/" + session + "/pay")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("cardToken", token))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private List<Map<String, Object>> links() {
        return merchant.client().get().uri("/api/v1/payment-links").header("X-Api-Key", merchant.apiKey())
                .retrieve().body(List.class);
    }

    private List<Map<String, Object>> payments() {
        return (List<Map<String, Object>>) merchant.client().get().uri("/api/v1/payments").header("X-Api-Key", merchant.apiKey())
                .retrieve().body(Map.class).get("items");
    }

    private Map<String, Object> onlyPayment() {
        assertThat(payments()).hasSize(1);
        return payments().getFirst();
    }

    private static long number(Map<String, Object> body, String key) {
        return ((Number) body.get(key)).longValue();
    }
}
