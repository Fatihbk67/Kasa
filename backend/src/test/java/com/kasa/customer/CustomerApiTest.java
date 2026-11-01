package com.kasa.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class CustomerApiTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    TestMerchant merchant;

    @BeforeEach
    void registerMerchant() {
        merchant = TestMerchant.register(port);
    }

    @Test
    void anEmailIsStoredInOneFormAndOnlyOnce() {
        Map<String, Object> created = merchant.post("/api/v1/customers", Map.of("email", "Ayse@Example.COM", "name", "Ayşe"));

        assertThat(created).containsEntry("email", "ayse@example.com").containsEntry("name", "Ayşe");
        assertThat(status("POST", "/api/v1/customers", Map.of("email", "ayse@example.com"))).isEqualTo(409);
    }

    @Test
    void twoMerchantsCanHaveTheSameCustomerEmail() {
        merchant.post("/api/v1/customers", Map.of("email", "ayse@example.com"));
        TestMerchant other = TestMerchant.register(port);

        assertThat(other.post("/api/v1/customers", Map.of("email", "ayse@example.com"))).containsKey("id");
    }

    @Test
    void anInvalidEmailIsRejected() {
        assertThat(status("POST", "/api/v1/customers", Map.of("email", "not-an-email"))).isEqualTo(400);
    }

    @Test
    void spentCountsOnlyWhatTheMerchantKept() {
        String customer = id(merchant.post("/api/v1/customers", Map.of("email", "a@example.com")));
        UUID refunded = capturedFor(customer, 10_000);
        merchant.refund(refunded, 4_000);
        UUID disputed = capturedFor(customer, 5_000);
        merchant.post("/api/v1/disputes/simulate", Map.of("paymentId", disputed, "reason", "FRAUDULENT"));
        authorizedFor(customer, 99_000);

        List<Map<String, Object>> totals = (List<Map<String, Object>>) get(customer).get("totals");

        assertThat(totals).hasSize(1);
        assertThat(totals.getFirst()).containsEntry("currency", "TRY");
        assertThat(((Number) totals.getFirst().get("spent")).longValue()).isEqualTo(6_000);
        assertThat(((Number) totals.getFirst().get("paymentCount")).longValue()).isEqualTo(2);
    }

    @Test
    void differentCurrenciesAreNeverAddedTogether() {
        String customer = id(merchant.post("/api/v1/customers", Map.of("email", "a@example.com")));
        capturedFor(customer, 10_000);
        Map<String, Object> usd = merchant.post("/api/v1/payments",
                Map.of("amount", 500, "currency", "USD", "cardToken", "tok_success", "customerId", customer));
        merchant.post("/api/v1/payments/" + ((Map<String, Object>) usd.get("payment")).get("id") + "/capture", null);

        assertThat((List<?>) get(customer).get("totals")).hasSize(2);
    }

    @Test
    void paymentsCanBeListedByCustomer() {
        String customer = id(merchant.post("/api/v1/customers", Map.of("email", "a@example.com")));
        UUID theirs = authorizedFor(customer, 1_000);
        merchant.authorized(2_000, "tok_success");

        List<Map<String, Object>> items = (List<Map<String, Object>>) merchant.client().get()
                .uri("/api/v1/payments?customerId=" + customer).header("X-Api-Key", merchant.apiKey())
                .retrieve().body(Map.class).get("items");

        assertThat(items).extracting(item -> item.get("id")).containsExactly(theirs.toString());
    }

    @Test
    void searchTreatsWildcardsLiterally() {
        merchant.post("/api/v1/customers", Map.of("email", "ayse@example.com", "name", "Ayşe Yılmaz"));
        merchant.post("/api/v1/customers", Map.of("email", "mehmet@example.com"));

        assertThat(search("YILMAZ")).isEmpty();
        assertThat(search("Yılmaz")).hasSize(1);
        assertThat(search("mehmet")).hasSize(1);
        assertThat(search("%25")).isEmpty();
    }

    @Test
    void theListPages() {
        for (int i = 0; i < 3; i++) {
            merchant.post("/api/v1/customers", Map.of("email", "c" + i + "@example.com"));
        }

        Map<String, Object> page = merchant.client().get().uri("/api/v1/customers?limit=2").header("X-Api-Key", merchant.apiKey())
                .retrieve().body(Map.class);

        assertThat((List<?>) page.get("items")).hasSize(2);
        List<?> rest = (List<?>) merchant.client().get().uri("/api/v1/customers?before=" + page.get("nextCursor"))
                .header("X-Api-Key", merchant.apiKey()).retrieve().body(Map.class).get("items");
        assertThat(rest).hasSize(1);
    }

    @Test
    void anotherMerchantsCustomerDoesNotExist() {
        String customer = id(merchant.post("/api/v1/customers", Map.of("email", "a@example.com")));
        TestMerchant other = TestMerchant.register(port);

        assertThat(TestMerchant.statusOf(other.client().get().uri("/api/v1/customers/" + customer).header("X-Api-Key", other.apiKey()))).isEqualTo(404);
        assertThat(TestMerchant.statusOf(other.client().delete().uri("/api/v1/customers/" + customer).header("X-Api-Key", other.apiKey()))).isEqualTo(404);
        assertThat(TestMerchant.statusOf(other.client().post().uri("/api/v1/payments")
                .header("X-Api-Key", other.apiKey())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", 100, "currency", "TRY", "cardToken", "tok_success", "customerId", customer)))).isEqualTo(422);
    }

    @Test
    void anUpdateCannotTakeAnotherCustomersEmail() {
        merchant.post("/api/v1/customers", Map.of("email", "a@example.com"));
        String second = id(merchant.post("/api/v1/customers", Map.of("email", "b@example.com")));

        assertThat(status("PUT", "/api/v1/customers/" + second, Map.of("email", "a@example.com"))).isEqualTo(409);
        assertThat(status("PUT", "/api/v1/customers/" + second, Map.of("email", "c@example.com", "name", "Cem"))).isEqualTo(200);
        assertThat(get(second)).containsEntry("email", "c@example.com").containsEntry("name", "Cem");
    }

    @Test
    void anonymizingErasesThePersonAndKeepsTheMoneyRecords() {
        String customer = id(merchant.post("/api/v1/customers", Map.of("email", "ayse@example.com", "name", "Ayşe")));
        UUID payment = capturedFor(customer, 10_000);

        assertThat(status("DELETE", "/api/v1/customers/" + customer, null)).isEqualTo(204);

        Map<String, Object> erased = get(customer);
        assertThat(erased).containsEntry("email", null).containsEntry("name", null).containsEntry("anonymized", true);
        assertThat(owner.sql("SELECT COUNT(*) FROM customers WHERE id = :id AND email IS NULL AND name IS NULL")
                .param("id", UUID.fromString(customer)).query(Long.class).single()).isEqualTo(1);
        assertThat(TestMerchant.statusOf(merchant.client().get().uri("/api/v1/payments/" + payment).header("X-Api-Key", merchant.apiKey()))).isEqualTo(200);
        assertThat((List<?>) erased.get("totals")).hasSize(1);
    }

    @Test
    void anAnonymizedCustomerStaysAnonymized() {
        String customer = id(merchant.post("/api/v1/customers", Map.of("email", "ayse@example.com")));
        status("DELETE", "/api/v1/customers/" + customer, null);

        assertThat(status("PUT", "/api/v1/customers/" + customer, Map.of("email", "new@example.com"))).isEqualTo(404);
        assertThat(status("DELETE", "/api/v1/customers/" + customer, null)).isEqualTo(404);
        assertThat(status("POST", "/api/v1/payments", Map.of("amount", 100, "currency", "TRY", "cardToken", "tok_success",
                "customerId", customer))).isEqualTo(422);
        assertThat(merchant.post("/api/v1/customers", Map.of("email", "ayse@example.com"))).containsKey("id");
    }

    @Test
    void anOpenDisputeHoldsBackAnonymizing() {
        String customer = id(merchant.post("/api/v1/customers", Map.of("email", "a@example.com")));
        UUID payment = capturedFor(customer, 10_000);
        String dispute = id(merchant.post("/api/v1/disputes/simulate", Map.of("paymentId", payment, "reason", "OTHER")));

        assertThat(status("DELETE", "/api/v1/customers/" + customer, null)).isEqualTo(409);
        merchant.post("/api/v1/disputes/" + dispute + "/accept", null);
        assertThat(status("DELETE", "/api/v1/customers/" + customer, null)).isEqualTo(204);
    }

    @Test
    void thePermanentLogNeverHoldsTheCustomersDetails() {
        String customer = id(merchant.post("/api/v1/customers", Map.of("email", "ayse@example.com", "name", "Ayşe")));
        status("PUT", "/api/v1/customers/" + customer, Map.of("email", "ayse2@example.com", "name", "Ayşe Y"));
        status("DELETE", "/api/v1/customers/" + customer, null);

        assertThat(owner.sql("SELECT action FROM audit_log WHERE tenant_id = :t AND target_type = 'CUSTOMER'")
                .param("t", merchant.tenantId()).query(String.class).list())
                .containsExactlyInAnyOrder("CUSTOMER_CREATED", "CUSTOMER_UPDATED", "CUSTOMER_ANONYMIZED");
        assertThat(owner.sql("SELECT COUNT(*) FROM audit_log WHERE tenant_id = :t AND metadata::text ILIKE '%ayse%'")
                .param("t", merchant.tenantId()).query(Long.class).single()).isZero();
    }

    private UUID capturedFor(String customer, long amount) {
        UUID id = authorizedFor(customer, amount);
        merchant.post("/api/v1/payments/" + id + "/capture", null);
        return id;
    }

    private UUID authorizedFor(String customer, long amount) {
        Map<String, Object> result = merchant.post("/api/v1/payments",
                Map.of("amount", amount, "currency", "TRY", "cardToken", "tok_success", "customerId", customer));
        return UUID.fromString((String) ((Map<String, Object>) result.get("payment")).get("id"));
    }

    private Map<String, Object> get(String customer) {
        return merchant.client().get().uri("/api/v1/customers/" + customer).header("X-Api-Key", merchant.apiKey())
                .retrieve().body(Map.class);
    }

    private List<?> search(String query) {
        return (List<?>) merchant.client().get().uri(java.net.URI.create(
                        "http://localhost:" + port + "/api/v1/customers?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)))
                .header("X-Api-Key", merchant.apiKey()).retrieve().body(Map.class).get("items");
    }

    private int status(String method, String path, Object body) {
        var request = merchant.client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .header("X-Api-Key", merchant.apiKey())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((sent, response) -> response.getStatusCode().value());
    }

    private static String id(Map<String, Object> body) {
        return (String) body.get("id");
    }
}
