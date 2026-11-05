package com.kasa.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import com.kasa.bank.SimulatedBank;
import com.kasa.reconciliation.ReconciliationService;
import com.kasa.settlement.SettlementService;
import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class DisputeApiTest extends PostgresIntegrationTest {

    private static final long LOSS_FEE = 15_000;

    @Value("${local.server.port}")
    int port;

    @Autowired
    DisputeService disputes;

    @Autowired
    SettlementService settlement;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    SimulatedBank bank;

    TestMerchant merchant;

    @BeforeEach
    void registerMerchant() {
        merchant = TestMerchant.register(port);
    }

    @Test
    void openingADisputeTakesTheMoneyBackAtOnce() {
        UUID payment = merchant.capturedPayment(10_000);

        Map<String, Object> dispute = open(payment, null);

        assertThat(dispute).containsEntry("status", "NEEDS_RESPONSE").containsEntry("reason", "FRAUDULENT");
        assertThat(number(dispute, "amount")).isEqualTo(10_000);
        assertThat(number(paymentOf(payment), "disputedAmount")).isEqualTo(10_000);
        assertThat(balance("merchant_payable")).isEqualTo(-250);
        assertThat(balance("psp_clearing")).isZero();
        assertThat(operationTypes(payment)).contains("DISPUTE_WITHDRAWAL");
        assertBooksBalance();
    }

    @Test
    void winningReturnsTheMoneyWithoutAFee() {
        UUID payment = merchant.capturedPayment(10_000);
        String dispute = id(open(payment, null));

        submitEvidence(dispute, "KARGO-123");
        assertThat(statusOf(dispute)).isEqualTo("UNDER_REVIEW");
        disputes.resolveDue(Duration.ZERO);

        assertThat(statusOf(dispute)).isEqualTo("WON");
        assertThat(number(paymentOf(payment), "disputedAmount")).isZero();
        assertThat(balance("merchant_payable")).isEqualTo(9_750);
        assertThat(balance("psp_clearing")).isEqualTo(10_000);
        assertThat(balance("fee_revenue")).isEqualTo(250);
        assertThat(operationTypes(payment)).contains("DISPUTE_REVERSAL").doesNotContain("DISPUTE_FEE");
        assertBooksBalance();
    }

    @Test
    void losingKeepsTheMoneyAwayAndAddsTheFee() {
        UUID payment = merchant.capturedPayment(10_000);
        String dispute = id(open(payment, null));

        submitEvidence(dispute, null);
        disputes.resolveDue(Duration.ZERO);

        Map<String, Object> resolved = get(dispute);
        assertThat(resolved).containsEntry("status", "LOST");
        assertThat(number(resolved, "feeAmount")).isEqualTo(LOSS_FEE);
        assertThat(number(paymentOf(payment), "disputedAmount")).isEqualTo(10_000);
        assertThat(balance("merchant_payable")).isEqualTo(-250 - LOSS_FEE);
        assertThat(balance("fee_revenue")).isEqualTo(250 + LOSS_FEE);
        assertThat(balance("psp_clearing")).isZero();
        assertBooksBalance();
    }

    @Test
    void acceptingEndsTheDisputeAsLost() {
        UUID payment = merchant.capturedPayment(10_000);
        String dispute = id(open(payment, null));

        assertThat(post("/api/v1/disputes/" + dispute + "/accept", null)).isEqualTo(200);

        assertThat(statusOf(dispute)).isEqualTo("LOST");
        assertThat(post("/api/v1/disputes/" + dispute + "/accept", null)).isEqualTo(409);
        assertThat(post("/api/v1/disputes/" + dispute + "/evidence", Map.of("explanation", "too late"))).isEqualTo(409);
    }

    @Test
    void missingTheDeadlineLosesTheDispute() {
        String dispute = id(open(merchant.capturedPayment(10_000), null));
        expireDeadline(dispute);

        disputes.resolveDue(Duration.ofDays(1));

        assertThat(statusOf(dispute)).isEqualTo("LOST");
    }

    @Test
    void evidenceAfterTheDeadlineIsRefusedEvenBeforeTheJobRuns() {
        String dispute = id(open(merchant.capturedPayment(10_000), null));
        expireDeadline(dispute);

        assertThat(post("/api/v1/disputes/" + dispute + "/evidence", Map.of("explanation", "late"))).isEqualTo(409);
        assertThat(statusOf(dispute)).isEqualTo("NEEDS_RESPONSE");
    }

    @Test
    void evidenceInTimeIsNotDecidedBeforeTheReviewPeriod() {
        String dispute = id(open(merchant.capturedPayment(10_000), null));
        submitEvidence(dispute, "KARGO-123");

        disputes.resolveDue(Duration.ofDays(1));

        assertThat(statusOf(dispute)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void evidenceArrivingAfterTheJobListedTheDisputeIsNotOverruled() {
        String dispute = id(open(merchant.capturedPayment(10_000), null));
        submitEvidence(dispute, "KARGO-123");

        boolean resolved = disputes.resolveIfStill(merchant.tenantId(), UUID.fromString(dispute), "NEEDS_RESPONSE", false);

        assertThat(resolved).isFalse();
        assertThat(statusOf(dispute)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void manyJobsResolvingAtOnceMoveTheMoneyOnce() throws Exception {
        UUID payment = merchant.capturedPayment(10_000);
        String dispute = id(open(payment, null));
        submitEvidence(dispute, "KARGO-123");

        Concurrency.run(8, index -> () -> disputes.resolveDue(Duration.ZERO));

        assertThat(statusOf(dispute)).isEqualTo("WON");
        assertThat(operationTypes(payment).stream().filter("DISPUTE_REVERSAL"::equals)).hasSize(1);
        assertThat(balance("merchant_payable")).isEqualTo(9_750);
        assertBooksBalance();
    }

    @Test
    void disputedMoneyCannotAlsoBeRefunded() {
        UUID payment = merchant.capturedPayment(10_000);
        open(payment, 6_000L);

        assertThat(post("/api/v1/payments/" + payment + "/refunds", Map.of("amount", 4_001))).isEqualTo(422);
        assertThat(post("/api/v1/payments/" + payment + "/refunds", Map.of("amount", 4_000))).isEqualTo(200);
        assertThat(post("/api/v1/payments/" + payment + "/refunds", null)).isEqualTo(409);
        assertThat(number(paymentOf(payment), "refundedAmount")).isEqualTo(4_000);
    }

    @Test
    void refundingIsAllowedAgainAfterAWin() {
        UUID payment = merchant.capturedPayment(10_000);
        String dispute = id(open(payment, null));
        assertThat(post("/api/v1/payments/" + payment + "/refunds", null)).isEqualTo(409);

        submitEvidence(dispute, "KARGO-123");
        disputes.resolveDue(Duration.ZERO);

        assertThat(post("/api/v1/payments/" + payment + "/refunds", null)).isEqualTo(200);
        assertThat(number(paymentOf(payment), "refundedAmount")).isEqualTo(10_000);
    }

    @Test
    void onlyTheRefundableRestCanBeDisputed() {
        UUID payment = merchant.capturedPayment(10_000);
        merchant.refund(payment, 7_000);

        assertThat(post("/api/v1/disputes/simulate", request(payment, 3_001L))).isEqualTo(422);
        Map<String, Object> dispute = open(payment, null);

        assertThat(number(dispute, "amount")).isEqualTo(3_000);
    }

    @Test
    void onlyAFullyCapturedAndUndisputedPaymentCanBeDisputed() {
        UUID authorized = merchant.authorized(10_000, "tok_success");
        assertThat(post("/api/v1/disputes/simulate", request(authorized, null))).isEqualTo(409);

        UUID refunded = merchant.capturedPayment(10_000);
        merchant.refund(refunded, 10_000);
        assertThat(post("/api/v1/disputes/simulate", request(refunded, null))).isEqualTo(409);

        UUID disputed = merchant.capturedPayment(10_000);
        open(disputed, null);
        assertThat(post("/api/v1/disputes/simulate", request(disputed, null))).isEqualTo(409);
    }

    @Test
    void aRefundInFlightBlocksADispute() {
        UUID payment = merchant.authorized(10_000, "tok_refund_timeout");
        merchant.post("/api/v1/payments/" + payment + "/capture", null);
        merchant.refund(payment, 5_000);

        assertThat(post("/api/v1/disputes/simulate", request(payment, null))).isEqualTo(409);
    }

    @Test
    void refundsAndDisputesRacingNeverGiveBackMoreThanWasCaptured() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID payment = merchant.capturedPayment(10_000);
            Concurrency.run(8, index -> () -> index % 2 == 0
                    ? post("/api/v1/disputes/simulate", request(payment, 6_000L))
                    : post("/api/v1/payments/" + payment + "/refunds", Map.of("amount", 6_000)));

            Map<String, Object> result = paymentOf(payment);
            assertThat(number(result, "refundedAmount") + number(result, "disputedAmount")).isLessThanOrEqualTo(10_000);
        }
        assertBooksBalance();
    }

    @Test
    void aLostDisputeIsTakenFromTheNextPayoutInsteadOfBlockingIt() {
        UUID payment = merchant.capturedPayment(10_000);
        settlement.settleAll(soon());
        assertThat(payouts()).hasSize(1);

        String dispute = id(open(payment, null));
        submitEvidence(dispute, null);
        disputes.resolveDue(Duration.ZERO);
        merchant.capturedPayment(40_000);
        settlement.settleAll(soon().plus(1, ChronoUnit.DAYS));

        assertThat(payouts()).hasSize(2);
        Map<String, Object> second = payouts().getFirst();
        assertThat(number(second, "amount")).isEqualTo(39_000 - 10_000 - LOSS_FEE);
        assertThat(balance("merchant_payable")).isZero();
        assertThat(itemTypes(second)).containsExactlyInAnyOrder("CAPTURE", "DISPUTE_WITHDRAWAL", "DISPUTE_FEE");
        assertBooksBalance();
    }

    @Test
    void aDisputeWonBeforeTheNextPayoutLeavesItUnchanged() {
        UUID payment = merchant.capturedPayment(10_000);
        String dispute = id(open(payment, null));
        submitEvidence(dispute, "KARGO-123");
        disputes.resolveDue(Duration.ZERO);

        settlement.settleAll(soon());

        assertThat(number(payouts().getFirst(), "amount")).isEqualTo(9_750);
        assertThat(balance("merchant_payable")).isZero();
        assertBooksBalance();
    }

    @Test
    void aMerchantWhoAcceptedADisputeIsStillPaidTheRest() {
        merchant.capturedPayment(50_000);
        UUID lost = merchant.capturedPayment(15_000);
        assertThat(post("/api/v1/disputes/" + id(open(lost, null)) + "/accept", null)).isEqualTo(200);

        settlement.settleAll(soon());

        assertThat(payouts()).hasSize(1);
        assertThat(number(payouts().getFirst(), "amount")).isEqualTo(48_750 + 14_625 - 15_000 - LOSS_FEE);
        assertThat(balance("merchant_payable")).isZero();
        assertBooksBalance();
    }

    /**
     * A loss that lands after the cutoff has already left the merchant's balance but is not an item of this payout.
     * Paying the items would overdraw the balance, so the whole payout waits a day, and then everything is paid.
     */
    @Test
    void aLossAfterTheCutoffDelaysThePayoutByOneRunAndIsNotLost() throws InterruptedException {
        merchant.capturedPayment(50_000);
        UUID lost = merchant.capturedPayment(15_000);
        Instant cutoff = soon();
        Thread.sleep(1_100);
        assertThat(post("/api/v1/disputes/" + id(open(lost, null)) + "/accept", null)).isEqualTo(200);

        settlement.settleAll(cutoff);
        assertThat(payouts()).isEmpty();

        settlement.settleAll(soon().plus(1, ChronoUnit.DAYS));
        assertThat(payouts()).hasSize(1);
        assertThat(number(payouts().getFirst(), "amount")).isEqualTo(48_750 + 14_625 - 15_000 - LOSS_FEE);
        assertThat(balance("merchant_payable")).isZero();
        assertBooksBalance();
    }

    @Test
    void aBalancePushedNegativeCarriesForward() {
        UUID payment = merchant.capturedPayment(10_000);
        settlement.settleAll(soon());
        open(payment, null);

        settlement.settleAll(soon().plus(1, ChronoUnit.DAYS));

        assertThat(payouts()).hasSize(1);
        assertThat(balance("merchant_payable")).isEqualTo(-10_000);
    }

    @Test
    void disputeMovementsMatchTheBankStatement() {
        UUID payment = merchant.capturedPayment(10_000);
        String dispute = id(open(payment, null));
        submitEvidence(dispute, "KARGO-123");
        disputes.resolveDue(Duration.ZERO);

        ReconciliationService.RunDetail run = reconciliation.reconcile(LocalDate.now(ZoneOffset.UTC));

        assertThat(run.discrepancies()).noneMatch(found -> merchant.tenantId().equals(found.tenantId()));
        assertThat(run.discrepancies()).noneMatch(found -> found.kind().equals("MISSING_IN_KASA")
                && reference(payment, "DISPUTE_WITHDRAWAL").equals(found.bankReference()));
    }

    @Test
    void aDisputeMovementMissingFromTheBankIsReported() {
        UUID payment = merchant.capturedPayment(10_000);
        open(payment, null);
        String reference = reference(payment, "DISPUTE_WITHDRAWAL");
        bank.simulateLineMissing(reference);

        ReconciliationService.RunDetail run = reconciliation.reconcile(LocalDate.now(ZoneOffset.UTC));

        assertThat(run.discrepancies()).anyMatch(found -> found.kind().equals("MISSING_IN_BANK")
                && reference.equals(found.bankReference()));
    }

    @Test
    void theFeeNeverReachesTheBank() {
        UUID payment = merchant.capturedPayment(10_000);
        post("/api/v1/disputes/" + id(open(payment, null)) + "/accept", null);

        assertThat(owner.sql("SELECT COUNT(*) FROM payment_operations WHERE payment_id = :id AND type = 'DISPUTE_FEE' AND bank_reference IS NULL")
                .param("id", payment).query(Long.class).single()).isEqualTo(1);
        assertThat(owner.sql("SELECT COUNT(*) FROM bank_movements WHERE tenant_id = :t AND type LIKE 'DISPUTE%'")
                .param("t", merchant.tenantId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void analyticsNetCountsWhatDisputesTook() {
        UUID payment = merchant.capturedPayment(10_000);
        post("/api/v1/disputes/" + id(open(payment, null)) + "/accept", null);

        Map<String, Object> summary = merchant.client().get().uri("/api/v1/analytics/summary?days=7")
                .header("Authorization", "Bearer " + merchant.token())
                .retrieve().body(Map.class);
        Map<String, Object> totals = (Map<String, Object>) summary.get("totals");

        assertThat(number(totals, "disputed")).isEqualTo(10_000);
        assertThat(number(totals, "disputeFees")).isEqualTo(LOSS_FEE);
        assertThat(number(totals, "net")).isEqualTo(10_000 - 250 - 10_000 - LOSS_FEE);
    }

    @Test
    void anotherMerchantCanNeitherSeeNorTouchADispute() {
        UUID payment = merchant.capturedPayment(10_000);
        String dispute = id(open(payment, null));
        TestMerchant other = TestMerchant.register(port);

        assertThat(status(other, "/api/v1/disputes/" + dispute + "/accept", null)).isEqualTo(404);
        assertThat(status(other, "/api/v1/disputes/" + dispute + "/evidence", Map.of("explanation", "x"))).isEqualTo(404);
        assertThat(status(other, "/api/v1/disputes/simulate", request(payment, null))).isEqualTo(404);
        assertThat(other.client().get().uri("/api/v1/disputes").header("X-Api-Key", other.apiKey())
                .retrieve().body(Map.class).get("items")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        assertThat(statusOf(dispute)).isEqualTo("NEEDS_RESPONSE");
    }

    @Test
    void theListFiltersAndPages() {
        UUID first = merchant.capturedPayment(1_000);
        String accepted = id(open(first, null));
        post("/api/v1/disputes/" + accepted + "/accept", null);
        UUID second = merchant.capturedPayment(2_000);
        open(second, null);
        open(merchant.capturedPayment(3_000), null);

        assertThat(list("?status=LOST")).hasSize(1);
        assertThat(list("?paymentId=" + second)).hasSize(1);

        Map<String, Object> page = merchant.client().get().uri("/api/v1/disputes?limit=2").header("X-Api-Key", merchant.apiKey())
                .retrieve().body(Map.class);
        assertThat((List<?>) page.get("items")).hasSize(2);
        assertThat(list("?before=" + page.get("nextCursor"))).hasSize(1);
    }

    @Test
    void anUnknownStatusIsRejectedRatherThanIgnored() {
        Integer status = merchant.client().get().uri("/api/v1/disputes?status=NOPE").header("X-Api-Key", merchant.apiKey())
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(400);
    }

    @Test
    void theDatabaseRefusesReopeningAFinishedDispute() {
        String dispute = id(open(merchant.capturedPayment(10_000), null));
        post("/api/v1/disputes/" + dispute + "/accept", null);

        assertThatThrownBy(() -> owner.sql("UPDATE disputes SET status = 'UNDER_REVIEW' WHERE id = :id")
                .param("id", UUID.fromString(dispute)).update())
                .hasMessageContaining("illegal dispute transition");
    }

    @Test
    void theAuditLogRecordsTheStepsButNotTheEvidenceText() {
        String dispute = id(open(merchant.capturedPayment(10_000), null));
        post("/api/v1/disputes/" + dispute + "/evidence", Map.of("explanation", "gizli-musteri-adi"));
        disputes.resolveDue(Duration.ZERO);

        List<String> actions = owner.sql("SELECT action FROM audit_log WHERE tenant_id = :t").param("t", merchant.tenantId())
                .query(String.class).list();
        assertThat(actions).contains("DISPUTE_OPENED", "DISPUTE_EVIDENCE_SUBMITTED", "DISPUTE_LOST");
        assertThat(owner.sql("SELECT COUNT(*) FROM audit_log WHERE tenant_id = :t AND metadata::text LIKE '%gizli-musteri-adi%'")
                .param("t", merchant.tenantId()).query(Long.class).single()).isZero();
        Map<String, Object> report = merchant.client().get().uri("/api/v1/audit-log/verify")
                .header("Authorization", "Bearer " + merchant.token()).retrieve().body(Map.class);
        assertThat(report).containsEntry("valid", true);
    }

    private Map<String, Object> open(UUID payment, Long amount) {
        return merchant.post("/api/v1/disputes/simulate", request(payment, amount));
    }

    private static Map<String, Object> request(UUID payment, Long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("paymentId", payment);
        body.put("reason", "FRAUDULENT");
        body.put("amount", amount);
        return body;
    }

    private void submitEvidence(String dispute, String tracking) {
        Map<String, Object> body = new HashMap<>();
        body.put("explanation", "The customer received the order.");
        body.put("tracking", tracking);
        assertThat(post("/api/v1/disputes/" + dispute + "/evidence", body)).isEqualTo(200);
    }

    private void expireDeadline(String dispute) {
        owner.sql("UPDATE disputes SET respond_by = now() - interval '1 minute' WHERE id = :id")
                .param("id", UUID.fromString(dispute)).update();
    }

    private int post(String path, Object body) {
        return status(merchant, path, body);
    }

    private static int status(TestMerchant actor, String path, Object body) {
        var request = actor.client().post().uri(path)
                .header("X-Api-Key", actor.apiKey())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((sent, response) -> response.getStatusCode().value());
    }

    private Map<String, Object> get(String dispute) {
        return merchant.client().get().uri("/api/v1/disputes/" + dispute).header("X-Api-Key", merchant.apiKey())
                .retrieve().body(Map.class);
    }

    private String statusOf(String dispute) {
        return (String) get(dispute).get("status");
    }

    private List<Map<String, Object>> list(String query) {
        return (List<Map<String, Object>>) merchant.client().get().uri("/api/v1/disputes" + query)
                .header("X-Api-Key", merchant.apiKey()).retrieve().body(Map.class).get("items");
    }

    private Map<String, Object> paymentOf(UUID payment) {
        return (Map<String, Object>) detail(payment).get("payment");
    }

    private Map<String, Object> detail(UUID payment) {
        return merchant.client().get().uri("/api/v1/payments/" + payment).header("X-Api-Key", merchant.apiKey())
                .retrieve().body(Map.class);
    }

    private List<String> operationTypes(UUID payment) {
        return ((List<Map<String, Object>>) detail(payment).get("operations")).stream()
                .map(operation -> (String) operation.get("type")).toList();
    }

    private String reference(UUID payment, String type) {
        return owner.sql("SELECT bank_reference FROM payment_operations WHERE payment_id = :id AND type = :type")
                .param("id", payment).param("type", type).query(String.class).single();
    }

    private List<Map<String, Object>> payouts() {
        return merchant.client().get().uri("/api/v1/payouts").header("Authorization", "Bearer " + merchant.token())
                .retrieve().body(List.class);
    }

    private List<String> itemTypes(Map<String, Object> payout) {
        Map<String, Object> detail = merchant.client().get().uri("/api/v1/payouts/" + payout.get("id"))
                .header("Authorization", "Bearer " + merchant.token()).retrieve().body(Map.class);
        return ((List<Map<String, Object>>) detail.get("items")).stream().map(item -> (String) item.get("type")).toList();
    }

    private long balance(String code) {
        return owner.sql("SELECT COALESCE(SUM(balance), 0) FROM ledger_accounts WHERE tenant_id = :t AND code = :code")
                .param("t", merchant.tenantId()).param("code", code).query(Long.class).single();
    }

    /** What we hold must always equal what we owe the merchant plus what we earned. */
    private void assertBooksBalance() {
        assertThat(balance("psp_clearing"))
                .isEqualTo(balance("merchant_payable") + balance("payout_in_transit") + balance("fee_revenue"));
    }

    private static Instant soon() {
        return Instant.now().plusSeconds(1);
    }

    private static String id(Map<String, Object> body) {
        return (String) body.get("id");
    }

    private static long number(Map<String, Object> body, String key) {
        return ((Number) body.get(key)).longValue();
    }
}
