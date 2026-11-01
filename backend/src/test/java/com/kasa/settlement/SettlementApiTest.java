package com.kasa.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;

import com.kasa.bank.SimulatedBank;
import com.kasa.bank.SimulatedBank.PayoutBehavior;
import com.kasa.ledger.LedgerService;
import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class SettlementApiTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    SettlementService settlement;

    @Autowired
    SimulatedBank bank;

    @Autowired
    LedgerService ledger;

    TestMerchant merchant;

    @BeforeEach
    void registerMerchant() {
        merchant = TestMerchant.register(port);
    }

    @Test
    void aPayoutPaysTheNetOfFeesAndClearsTheMerchantBalance() {
        merchant.capturedPayment(10_000);

        assertThat(settlement.settleAll(soon())).isGreaterThanOrEqualTo(1);

        Map<String, Object> payout = onlyPayout();
        assertThat(payout.get("status")).isEqualTo("PAID");
        assertThat(number(payout, "amount")).isEqualTo(9_750);
        assertThat(balance("merchant_payable")).isZero();
        assertThat(balance("payout_in_transit")).isZero();
        assertThat(balance("psp_clearing")).isEqualTo(250);
        assertThat(balance("fee_revenue")).isEqualTo(250);
        assertBooksBalance();
    }

    @Test
    void aPayoutListsTheOperationsItCovers() {
        UUID payment = merchant.capturedPayment(10_000);
        merchant.refund(payment, 4_000);

        settlement.settleAll(soon());

        Map<String, Object> payout = onlyPayout();
        assertThat(number(payout, "amount")).isEqualTo(5_750);
        Map<String, Object> detail = merchant.client().get().uri("/api/v1/payouts/" + payout.get("id"))
                .header("Authorization", "Bearer " + merchant.token())
                .retrieve().body(Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        assertThat(items).extracting(item -> item.get("type")).containsExactlyInAnyOrder("CAPTURE", "REFUND");
        assertThat(items.stream().mapToLong(item -> number(item, "netAmount")).sum()).isEqualTo(5_750);
    }

    @Test
    void operationsAfterTheCutoffWaitForALaterPayout() throws Exception {
        merchant.capturedPayment(1_000);
        Instant cutoff = databaseNow();
        Thread.sleep(5);
        merchant.capturedPayment(2_000);

        settlement.settleAll(cutoff);

        assertThat(number(onlyPayout(), "amount")).isEqualTo(975);
        assertThat(balance("merchant_payable")).isEqualTo(1_950);
    }

    @Test
    void runningTwiceOnTheSameDayDoesNotPayTwice() {
        merchant.capturedPayment(10_000);

        settlement.settleAll(soon());
        merchant.capturedPayment(1_000);
        settlement.settleAll(soon());

        assertThat(payouts()).hasSize(1);
    }

    @Test
    void aLaterDayPaysOnlyWhatIsNew() {
        merchant.capturedPayment(10_000);
        settlement.settleAll(soon());

        merchant.capturedPayment(2_000);
        settlement.settleAll(soon().plus(1, ChronoUnit.DAYS));

        assertThat(payouts()).extracting(p -> number(p, "amount")).containsExactlyInAnyOrder(9_750L, 1_950L);
        assertThat(balance("merchant_payable")).isZero();
        assertBooksBalance();
    }

    @Test
    void aNegativeNetCarriesForwardAndIsOffsetByLaterCaptures() {
        UUID payment = merchant.capturedPayment(10_000);
        merchant.refund(payment, 10_000);

        settlement.settleAll(soon());
        assertThat(payouts()).isEmpty();

        merchant.capturedPayment(10_000);
        settlement.settleAll(soon().plus(1, ChronoUnit.DAYS));

        assertThat(number(onlyPayout(), "amount")).isEqualTo(9_500);
        assertThat(balance("merchant_payable")).isZero();
        assertBooksBalance();
    }

    @Test
    void aRefundAfterTheCutoffStopsAPayoutTheMerchantCouldNotCover() throws Exception {
        UUID payment = merchant.capturedPayment(10_000);
        Instant cutoff = databaseNow();
        Thread.sleep(5);
        merchant.refund(payment, 10_000);

        settlement.settleAll(cutoff);

        assertThat(payouts()).isEmpty();
        assertBooksBalance();
    }

    @Test
    void aBankRejectionReversesTheMoneyAndALaterRunCanRetry() {
        merchant.capturedPayment(10_000);
        bank.setPayoutBehavior(merchant.tenantId(), PayoutBehavior.REJECT);

        settlement.settleAll(soon());

        Map<String, Object> failed = onlyPayout();
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("failureCode")).isEqualTo("account_closed");
        assertThat(balance("merchant_payable")).isEqualTo(9_750);
        assertThat(balance("payout_in_transit")).isZero();
        assertBooksBalance();

        bank.setPayoutBehavior(merchant.tenantId(), PayoutBehavior.APPROVE);
        settlement.settleAll(soon());

        assertThat(payouts()).extracting(p -> p.get("status")).containsExactlyInAnyOrder("FAILED", "PAID");
        assertThat(balance("merchant_payable")).isZero();
        assertThat(balance("psp_clearing")).isEqualTo(250);
        assertBooksBalance();
    }

    @Test
    void aPayoutTheBankProcessedButNeverAnsweredIsRecoveredOnce() {
        merchant.capturedPayment(10_000);
        bank.setPayoutBehavior(merchant.tenantId(), PayoutBehavior.TIMEOUT_PROCESSED);

        settlement.settleAll(soon());

        assertThat(onlyPayout().get("status")).isEqualTo("PENDING");
        assertThat(balance("merchant_payable")).isZero();
        assertThat(balance("payout_in_transit")).isEqualTo(9_750);

        settlement.recoverStale(Duration.ZERO);
        settlement.recoverStale(Duration.ZERO);

        assertThat(onlyPayout().get("status")).isEqualTo("PAID");
        assertThat(balance("payout_in_transit")).isZero();
        assertThat(balance("psp_clearing")).isEqualTo(250);
        assertBooksBalance();
    }

    @Test
    void concurrentRecoveryAppliesAPayoutOutcomeOnce() throws Exception {
        merchant.capturedPayment(10_000);
        bank.setPayoutBehavior(merchant.tenantId(), PayoutBehavior.TIMEOUT_PROCESSED);
        settlement.settleAll(soon());

        Concurrency.run(10, i -> () -> settlement.recoverStale(Duration.ZERO));

        assertThat(onlyPayout().get("status")).isEqualTo("PAID");
        assertThat(owner.sql("SELECT count(*) FROM webhook_events WHERE tenant_id = :t AND type = 'payout.paid'")
                .param("t", merchant.tenantId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(balance("payout_in_transit")).isZero();
        assertBooksBalance();
    }

    @Test
    void aPayoutTheBankNeverReceivedIsReversedByRecovery() {
        merchant.capturedPayment(10_000);
        bank.setPayoutBehavior(merchant.tenantId(), PayoutBehavior.TIMEOUT_LOST);

        settlement.settleAll(soon());
        settlement.recoverStale(Duration.ZERO);

        assertThat(onlyPayout().get("status")).isEqualTo("FAILED");
        assertThat(balance("merchant_payable")).isEqualTo(9_750);
        assertBooksBalance();
    }

    @Test
    void concurrentJobRunsCreateOnePayout() throws Exception {
        merchant.capturedPayment(10_000);

        Concurrency.run(10, i -> () -> settlement.settleAll(soon()));

        assertThat(number(onlyPayout(), "amount")).isEqualTo(9_750);
        assertThat(balance("merchant_payable")).isZero();
        assertBooksBalance();
    }

    @Test
    void oneMerchantsBankFailureDoesNotAffectAnother() {
        TestMerchant healthy = TestMerchant.register(port);
        merchant.capturedPayment(1_000);
        healthy.capturedPayment(2_000);
        bank.setPayoutBehavior(merchant.tenantId(), PayoutBehavior.REJECT);

        settlement.settleAll(soon());

        assertThat(onlyPayout().get("status")).isEqualTo("FAILED");
        assertThat(payoutsOf(healthy)).extracting(p -> p.get("status")).containsExactly("PAID");
    }

    @Test
    void payoutsOfAnotherMerchantAreInvisible() {
        TestMerchant other = TestMerchant.register(port);
        merchant.capturedPayment(1_000);
        settlement.settleAll(soon());
        Object payoutId = onlyPayout().get("id");

        ResponseEntity<Void> response = other.client().get().uri("/api/v1/payouts/" + payoutId)
                .header("Authorization", "Bearer " + other.token())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(payoutsOf(other)).isEmpty();
    }

    @Test
    void aPayoutEmitsAWebhookEvent() {
        merchant.capturedPayment(1_000);

        settlement.settleAll(soon());

        assertThat(owner.sql("SELECT type FROM webhook_events WHERE tenant_id = :t")
                .param("t", merchant.tenantId()).query(String.class).list()).contains("payout.paid");
    }

    @Test
    void theDatabaseStopsAnOperationBeingPaidTwiceAndIllegalTransitions() {
        merchant.capturedPayment(1_000);
        settlement.settleAll(soon());
        UUID payoutId = UUID.fromString((String) onlyPayout().get("id"));

        assertThatThrownBy(() -> owner.sql("""
                        INSERT INTO payout_items (tenant_id, payout_id, operation_id, payment_id, type,
                                                  gross_amount, fee_amount, net_amount)
                        SELECT tenant_id, payout_id, operation_id, payment_id, type, gross_amount, fee_amount, net_amount
                        FROM payout_items WHERE payout_id = :id
                        """).param("id", payoutId).update()).hasStackTraceContaining("payout_items_settled_once");
        assertThatThrownBy(() -> owner.sql("UPDATE payouts SET status = 'PENDING' WHERE id = :id")
                .param("id", payoutId).update()).hasStackTraceContaining("illegal payout transition");
    }

    /** Operations are timestamped by the database, so a cutoff between two of them must come from the same clock. */
    private static Instant databaseNow() {
        return owner.sql("SELECT clock_timestamp()")
                .query((rs, rowNum) -> rs.getObject(1, java.time.OffsetDateTime.class).toInstant())
                .single();
    }

    private static Instant soon() {
        return Instant.now().plusSeconds(1);
    }

    private Map<String, Object> onlyPayout() {
        List<Map<String, Object>> payouts = payouts();
        assertThat(payouts).hasSize(1);
        return payouts.getFirst();
    }

    private List<Map<String, Object>> payouts() {
        return payoutsOf(merchant);
    }

    private static List<Map<String, Object>> payoutsOf(TestMerchant who) {
        return who.client().get().uri("/api/v1/payouts")
                .header("X-Api-Key", who.apiKey())
                .retrieve().body(List.class);
    }

    private long balance(String code) {
        return owner.sql("SELECT COALESCE(SUM(balance), 0) FROM ledger_accounts WHERE tenant_id = :t AND code = :c")
                .param("t", merchant.tenantId())
                .param("c", code)
                .query(Long.class)
                .single();
    }

    private void assertBooksBalance() {
        assertThat(ledger.findDriftedAccounts(merchant.tenantId())).isEmpty();
    }

    private static long number(Map<String, Object> map, String field) {
        return ((Number) map.get(field)).longValue();
    }
}
