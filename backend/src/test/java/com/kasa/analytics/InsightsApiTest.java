package com.kasa.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class InsightsApiTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    TestMerchant merchant;

    @BeforeEach
    void registerMerchant() {
        merchant = TestMerchant.register(port);
    }

    @Test
    void summaryAddsUpMoneyAndNetsFeesAndRefunds() {
        UUID first = merchant.capturedPayment(10_000);
        merchant.capturedPayment(20_000);
        merchant.refund(first, 4_000);

        Map<String, Object> totals = totals(summary(merchant, 30));

        assertThat(number(totals, "captured")).isEqualTo(30_000);
        assertThat(number(totals, "refunded")).isEqualTo(4_000);
        assertThat(number(totals, "fees")).isEqualTo(750);
        assertThat(number(totals, "net")).isEqualTo(25_250);
        assertThat(number(totals, "captureCount")).isEqualTo(2);
    }

    @Test
    void netAgreesWithTheMerchantsLedgerBalance() {
        UUID first = merchant.capturedPayment(10_000);
        merchant.capturedPayment(33_333);
        merchant.refund(first, 1_234);

        List<Map<String, Object>> balances = merchant.client().get().uri("/api/v1/balances")
                .header("X-Api-Key", merchant.apiKey()).retrieve().body(List.class);

        assertThat(number(totals(summary(merchant, 7)), "net")).isEqualTo(number(balances.getFirst(), "available"));
    }

    @Test
    void everyDayInTheRangeAppearsEvenWithoutActivity() {
        merchant.capturedPayment(10_000);

        List<Map<String, Object>> daily = (List<Map<String, Object>>) summary(merchant, 7).get("daily");

        assertThat(daily).hasSize(7);
        assertThat(number(daily.getLast(), "captured")).isEqualTo(10_000);
        assertThat(number(daily.getFirst(), "captured")).isZero();
    }

    @Test
    void moneyOutsideTheRangeIsNotCounted() {
        UUID old = merchant.capturedPayment(50_000);
        merchant.capturedPayment(1_000);
        owner.sql("UPDATE payment_operations SET updated_at = now() - interval '40 days', created_at = now() - interval '40 days' WHERE payment_id = :p")
                .param("p", old).update();

        assertThat(number(totals(summary(merchant, 30)), "captured")).isEqualTo(1_000);
        assertThat(number(totals(summary(merchant, 90)), "captured")).isEqualTo(51_000);
    }

    @Test
    void approvalRateInputsAndDeclineReasonsAreReported() {
        merchant.authorized(1_000, "tok_success");
        merchant.authorized(1_000, "tok_success");
        merchant.authorized(1_000, "tok_insufficient_funds");

        Map<String, Object> summary = summary(merchant, 7);
        Map<String, Object> totals = totals(summary);
        List<Map<String, Object>> failures = (List<Map<String, Object>>) summary.get("failures");

        assertThat(number(totals, "authorizationAttempts")).isEqualTo(3);
        assertThat(number(totals, "authorizationsApproved")).isEqualTo(2);
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).containsEntry("code", "insufficient_funds");
    }

    @Test
    void summaryNeverIncludesAnotherMerchant() {
        TestMerchant other = TestMerchant.register(port);
        other.capturedPayment(99_000);
        merchant.capturedPayment(1_000);

        assertThat(number(totals(summary(merchant, 30)), "captured")).isEqualTo(1_000);
    }

    @Test
    void anUnsupportedRangeIsRejected() {
        assertThat(status("/api/v1/analytics/summary?days=45")).isEqualTo(400);
        assertThat(status("/api/v1/analytics/summary?currency=abc")).isEqualTo(400);
    }

    @Test
    void searchMatchesDescriptionsCaseInsensitivelyAndExactIds() {
        UUID coffee = create(1_000, "Çekirdek Kahve");
        create(1_000, "Çay");

        assertThat(ids("/api/v1/payments?q=kahve")).containsExactly(coffee.toString());
        assertThat(ids("/api/v1/payments?q=" + coffee)).containsExactly(coffee.toString());
        assertThat(ids("/api/v1/payments?q=" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void wildcardCharactersInASearchMeanThemselves() {
        create(1_000, "indirim %50");
        create(1_000, "Normal sipariş");

        assertThat(ids("/api/v1/payments?q={q}", "%")).hasSize(1);
        assertThat(ids("/api/v1/payments?q={q}", "_")).isEmpty();
    }

    @Test
    void aSearchCannotInjectSql() {
        create(1_000, "Sipariş");

        assertThat(ids("/api/v1/payments?q={q}", "' OR 1=1 --")).isEmpty();
    }

    @Test
    void amountAndDateFiltersNarrowTheResults() {
        UUID small = create(1_000, "küçük");
        UUID large = create(50_000, "büyük");
        owner.sql("UPDATE payments SET created_at = now() - interval '10 days' WHERE id = :p").param("p", large).update();

        assertThat(ids("/api/v1/payments?minAmount=2000")).containsExactly(large.toString());
        assertThat(ids("/api/v1/payments?maxAmount=2000")).containsExactly(small.toString());

        String today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Istanbul")).toString();
        assertThat(ids("/api/v1/payments?from=" + today + "&to=" + today)).containsExactly(small.toString());
    }

    @Test
    void filtersWorkAcrossPages() {
        for (int i = 0; i < 3; i++) {
            create(1_000, "sayfa " + i);
        }
        create(1_000, "başka");

        Map<String, Object> first = get("/api/v1/payments?q=sayfa&limit=2");
        Map<String, Object> second = get("/api/v1/payments?q=sayfa&limit=2&before=" + first.get("nextCursor"));

        assertThat((List<?>) first.get("items")).hasSize(2);
        assertThat((List<?>) second.get("items")).hasSize(1);
        assertThat(second.get("nextCursor")).isNull();
    }

    @Test
    void exportProducesAnExcelReadyFileOfOnlyTheFilteredPayments() {
        create(125_075, "Kahve; \"özel\"");
        create(2_000, "Çay");

        ResponseEntity<byte[]> response = merchant.client().get().uri("/api/v1/payments/export?q=kahve")
                .header("X-Api-Key", merchant.apiKey()).retrieve().toEntity(byte[].class);
        String csv = new String(response.getBody(), StandardCharsets.UTF_8);

        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("kasa-odemeler.csv");
        assertThat(csv).startsWith("﻿Ödeme kimliği;");
        assertThat(csv).contains("1250,75;TRY").contains("\"Kahve; \"\"özel\"\"\"").doesNotContain("Çay");
    }

    @Test
    void exportNeutralizesSpreadsheetFormulas() {
        create(1_000, "=HYPERLINK(\"http://kotu.example\")");

        String csv = new String(merchant.client().get().uri("/api/v1/payments/export")
                .header("X-Api-Key", merchant.apiKey()).retrieve().body(byte[].class), StandardCharsets.UTF_8);

        assertThat(csv).contains("\"'=HYPERLINK(");
    }

    @Test
    void exportContainsOnlyTheMerchantsOwnPayments() {
        TestMerchant other = TestMerchant.register(port);
        UUID theirs = other.authorized(1_000, "tok_success");
        create(1_000, "benim");

        String csv = new String(merchant.client().get().uri("/api/v1/payments/export")
                .header("X-Api-Key", merchant.apiKey()).retrieve().body(byte[].class), StandardCharsets.UTF_8);

        assertThat(csv).contains("benim").doesNotContain(theirs.toString());
    }

    @Test
    void theOperatorSeesPlatformTotalsButMerchantsCannot() {
        merchant.capturedPayment(1_000);

        Map<String, Object> overview = client().get().uri("/api/v1/admin/overview")
                .header("X-Admin-Token", "test-admin-token").retrieve().body(Map.class);

        assertThat(number(overview, "tenantCount")).isGreaterThanOrEqualTo(1);
        assertThat(number(overview, "capturesLastDay")).isGreaterThanOrEqualTo(1);
        assertThat(overview).containsKeys("pendingOperations", "queuedWebhooks", "failedPayouts");
        assertThat(client().get().uri("/api/v1/admin/overview").header("X-Api-Key", merchant.apiKey())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value()).isEqualTo(403);
    }

    private UUID create(long amount, String description) {
        Map<String, Object> result = merchant.post("/api/v1/payments",
                Map.of("amount", amount, "currency", "TRY", "cardToken", "tok_success", "description", description));
        return UUID.fromString((String) ((Map<String, Object>) result.get("payment")).get("id"));
    }

    private List<Object> ids(String uri, Object... variables) {
        Map<String, Object> page = merchant.client().get().uri(uri, variables)
                .header("X-Api-Key", merchant.apiKey()).retrieve().body(Map.class);
        return ((List<Map<String, Object>>) page.get("items")).stream().map(item -> item.get("id")).toList();
    }

    private Map<String, Object> get(String uri) {
        return merchant.client().get().uri(uri).header("X-Api-Key", merchant.apiKey()).retrieve().body(Map.class);
    }

    private int status(String uri) {
        return merchant.client().get().uri(uri).header("X-Api-Key", merchant.apiKey())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value();
    }

    private static Map<String, Object> summary(TestMerchant who, int days) {
        return who.client().get().uri("/api/v1/analytics/summary?days=" + days)
                .header("X-Api-Key", who.apiKey())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(Map.class);
    }

    private static Map<String, Object> totals(Map<String, Object> summary) {
        return (Map<String, Object>) summary.get("totals");
    }

    private static long number(Map<String, Object> map, String field) {
        return ((Number) map.get(field)).longValue();
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }
}
