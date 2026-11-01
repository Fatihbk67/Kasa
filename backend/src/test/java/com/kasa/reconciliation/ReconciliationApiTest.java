package com.kasa.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.kasa.bank.SimulatedBank;
import com.kasa.settlement.SettlementService;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

/** The whole platform shares the bank and these runs, so assertions look only at this test's own references. */
class ReconciliationApiTest extends PostgresIntegrationTest {

    private static final String ADMIN_TOKEN = "test-admin-token";

    @Value("${local.server.port}")
    int port;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    SettlementService settlement;

    @Autowired
    SimulatedBank bank;

    TestMerchant merchant;
    LocalDate today;

    @BeforeEach
    void setUp() {
        merchant = TestMerchant.register(port);
        today = LocalDate.now(ZoneOffset.UTC);
    }

    @Test
    void capturesRefundsAndPayoutsThatAgreeProduceNoDiscrepancy() {
        UUID payment = merchant.capturedPayment(10_000);
        merchant.refund(payment, 1_000);
        settlement.settleAll(Instant.now().plusSeconds(1));

        ReconciliationService.RunDetail detail = reconciliation.reconcile(today);

        assertThat(detail.run().status()).isEqualTo("COMPLETED");
        assertThat(detail.run().matchedCount()).isGreaterThanOrEqualTo(3);
        assertThat(ofMerchant(detail)).isEmpty();
    }

    @Test
    void aMovementTheBankDidNotBookIsReported() {
        String reference = captureReference(merchant.capturedPayment(10_000));
        bank.simulateLineMissing(reference);

        ReconciliationService.DiscrepancyView found = onlyFor(reference);

        assertThat(found.kind()).isEqualTo("MISSING_IN_BANK");
        assertThat(found.tenantId()).isEqualTo(merchant.tenantId());
        assertThat(found.expectedAmount()).isEqualTo(10_000);
    }

    @Test
    void aBankLineWeHaveNoRecordOfIsReported() {
        String reference = "bank_unknown_" + UUID.randomUUID();
        bank.simulateUnknownLine(today, reference, 500);

        ReconciliationService.DiscrepancyView found = onlyFor(reference);

        assertThat(found.kind()).isEqualTo("MISSING_IN_KASA");
        assertThat(found.actualAmount()).isEqualTo(500);
    }

    @Test
    void aDifferentAmountIsReportedWithBothValues() {
        String reference = captureReference(merchant.capturedPayment(10_000));
        bank.simulateAmountChange(reference, 9_999);

        ReconciliationService.DiscrepancyView found = onlyFor(reference);

        assertThat(found.kind()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(found.expectedAmount()).isEqualTo(10_000);
        assertThat(found.actualAmount()).isEqualTo(9_999);
    }

    @Test
    void aDifferentCurrencyIsReported() {
        String reference = captureReference(merchant.capturedPayment(10_000));
        bank.simulateCurrencyChange(reference, "USD");

        assertThat(onlyFor(reference).kind()).isEqualTo("DETAILS_MISMATCH");
    }

    @Test
    void aDuplicatedBankLineIsReported() {
        String reference = captureReference(merchant.capturedPayment(10_000));
        bank.simulateDuplicateLine(reference);

        assertThat(onlyFor(reference).kind()).isEqualTo("DUPLICATE_IN_BANK");
    }

    @Test
    void aMovementBookedOnTheNeighbouringDayIsTimingAndNotAnError() {
        String reference = captureReference(merchant.capturedPayment(10_000));
        bank.simulateBookedOn(reference, today.plusDays(1));

        ReconciliationService.RunDetail detail = reconciliation.reconcile(today);

        assertThat(detail.discrepancies()).noneMatch(d -> reference.equals(d.bankReference()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not,the,right,header",
            "reference,type,amount,currency,booked_at\nbank_1,CAPTURE,abc,TRY,2026-01-01T00:00:00Z",
            "reference,type,amount,currency,booked_at\nbank_1,CAPTURE,-5,TRY,2026-01-01T00:00:00Z",
            "reference,type,amount,currency,booked_at\nbank_1,TELEPORT,5,TRY,2026-01-01T00:00:00Z",
            "reference,type,amount,currency,booked_at\nbank_1,CAPTURE,5"
    })
    void aMalformedStatementFailsTheRunWithoutAnyPartialResult(String content) {
        LocalDate date = LocalDate.of(2020, 1, 15);
        bank.simulateMalformedStatement(date, content);
        try {
            ReconciliationService.RunDetail detail = reconciliation.reconcile(date);

            assertThat(detail.run().status()).isEqualTo("FAILED");
            assertThat(detail.run().error()).isNotBlank();
            assertThat(detail.discrepancies()).isEmpty();
        } finally {
            bank.clearMalformedStatement(date);
        }
    }

    @Test
    void aBalanceThatDriftedFromItsPostingsIsReported() {
        merchant.capturedPayment(1_000);
        owner.sql("UPDATE ledger_accounts SET balance = balance + 1 WHERE tenant_id = :t AND code = 'merchant_payable'")
                .param("t", merchant.tenantId())
                .update();

        ReconciliationService.RunDetail detail = reconciliation.reconcile(today);

        assertThat(ofMerchant(detail)).extracting(ReconciliationService.DiscrepancyView::kind).contains("LEDGER_DRIFT");
        owner.sql("UPDATE ledger_accounts SET balance = balance - 1 WHERE tenant_id = :t AND code = 'merchant_payable'")
                .param("t", merchant.tenantId())
                .update();
    }

    @Test
    void theDailyJobDoesNothingOnceADateHasACompletedRun() {
        LocalDate date = LocalDate.of(2020, 3, 10);

        reconciliation.reconcileIfMissing(date);
        reconciliation.reconcileIfMissing(date);

        assertThat(runCount(date)).isEqualTo(1);
    }

    @Test
    void aManualRerunAddsToTheHistory() {
        LocalDate date = LocalDate.of(2020, 3, 11);

        reconciliation.reconcile(date);
        reconciliation.reconcile(date);

        assertThat(runCount(date)).isEqualTo(2);
    }

    @Test
    void theAdminApiRunsAndShowsReconciliation() {
        ResponseEntity<Map> created = admin().post().uri("/api/v1/admin/reconciliation/runs?date=2020-05-01")
                .header("X-Admin-Token", ADMIN_TOKEN)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
        Map<String, Object> run = (Map<String, Object>) created.getBody().get("run");

        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(run.get("statementDate")).isEqualTo("2020-05-01");

        ResponseEntity<Map> fetched = admin().get().uri("/api/v1/admin/reconciliation/runs/" + run.get("id"))
                .header("X-Admin-Token", ADMIN_TOKEN)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void theAdminApiCanSettleOnDemand() throws Exception {
        merchant.capturedPayment(1_000);
        // The endpoint's cutoff comes from the JVM clock and the capture's time from the database clock.
        Thread.sleep(50);

        Map<String, Object> result = admin().post().uri("/api/v1/admin/settlements/run")
                .header("X-Admin-Token", ADMIN_TOKEN)
                .retrieve().body(Map.class);

        assertThat(((Number) result.get("payoutsCreated")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theAdminApiIsClosedToEveryoneElse() {
        String path = "/api/v1/admin/reconciliation/runs";

        assertThat(status(admin().get().uri(path))).isEqualTo(401);
        assertThat(status(admin().get().uri(path).header("X-Admin-Token", "wrong"))).isEqualTo(401);
        assertThat(status(admin().get().uri(path).header("Authorization", "Bearer " + merchant.token()))).isEqualTo(403);
        assertThat(status(admin().get().uri(path).header("X-Api-Key", merchant.apiKey()))).isEqualTo(403);
        assertThat(status(admin().get().uri(path).header("X-Admin-Token", ADMIN_TOKEN))).isEqualTo(200);
    }

    @Test
    void anAdminTokenOpensOnlyTheAdminApiBecauseItHasNoTenant() {
        assertThat(status(admin().get().uri("/api/v1/payments").header("X-Admin-Token", ADMIN_TOKEN))).isEqualTo(403);
        assertThat(status(admin().get().uri("/api/v1/tenant").header("X-Admin-Token", ADMIN_TOKEN))).isEqualTo(403);
    }

    @Test
    void theDiscrepancyRecordsAreStoredWithTheRun() {
        String reference = "bank_unknown_" + UUID.randomUUID();
        bank.simulateUnknownLine(today, reference, 500);

        ReconciliationService.RunDetail detail = reconciliation.reconcile(today);

        assertThat(detail.run().discrepancyCount()).isEqualTo(detail.discrepancies().size());
        assertThat(owner.sql("SELECT count(*) FROM reconciliation_discrepancies WHERE run_id = :r AND bank_reference = :b")
                .param("r", detail.run().id())
                .param("b", reference)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void reconciliationResultsCannotBeRewrittenByTheApplication() {
        assertThatThrownBy(() -> applicationJdbc.sql("DELETE FROM reconciliation_runs").update())
                .hasStackTraceContaining("permission denied");
    }

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient applicationJdbc;

    private static int status(RestClient.RequestHeadersSpec<?> request) {
        return request.retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value();
    }

    private RestClient admin() {
        return RestClient.create("http://localhost:" + port);
    }

    private ReconciliationService.DiscrepancyView onlyFor(String reference) {
        List<ReconciliationService.DiscrepancyView> found = reconciliation.reconcile(today).discrepancies().stream()
                .filter(d -> reference.equals(d.bankReference()))
                .toList();
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private List<ReconciliationService.DiscrepancyView> ofMerchant(ReconciliationService.RunDetail detail) {
        return detail.discrepancies().stream().filter(d -> merchant.tenantId().equals(d.tenantId())).toList();
    }

    private String captureReference(UUID paymentId) {
        return owner.sql("SELECT bank_reference FROM payment_operations WHERE payment_id = :p AND type = 'CAPTURE'")
                .param("p", paymentId)
                .query(String.class)
                .single();
    }

    private int runCount(LocalDate date) {
        return owner.sql("SELECT count(*) FROM reconciliation_runs WHERE statement_date = :d")
                .param("d", date)
                .query(Integer.class)
                .single();
    }
}
