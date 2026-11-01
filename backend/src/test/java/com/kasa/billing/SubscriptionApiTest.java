package com.kasa.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import com.kasa.payment.PaymentService;
import com.kasa.settlement.SettlementService;
import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class SubscriptionApiTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    BillingService billing;

    @Autowired
    PaymentService payments;

    @Autowired
    SettlementService settlement;

    TestMerchant merchant;
    String customer;
    String basic;
    String pro;

    @BeforeEach
    void setUp() {
        merchant = TestMerchant.register(port);
        customer = (String) merchant.post("/api/v1/customers", Map.of("email", "abone@example.com")).get("id");
        basic = plan("Temel", 10_000, "MONTH");
        pro = plan("Pro", 30_000, "MONTH");
    }

    @Test
    void subscribingChargesTheFirstPeriodAtOnce() {
        Map<String, Object> detail = subscribe(basic, "tok_success");

        assertThat(subscription(detail)).containsEntry("status", "ACTIVE").containsEntry("planName", "Temel");
        Map<String, Object> invoice = invoices(detail).getFirst();
        assertThat(invoice).containsEntry("status", "PAID").containsEntry("kind", "FIRST");
        assertThat(number(invoice, "amount")).isEqualTo(10_000);
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(capturedTotal()).isEqualTo(10_000);
    }

    @Test
    void theCardTokenIsNeverShown() {
        Map<String, Object> detail = subscribe(basic, "tok_success");

        assertThat(detail.toString()).doesNotContain("tok_success");
    }

    @Test
    void aDeclinedFirstPaymentEndsTheSignUpWithoutRetries() {
        Map<String, Object> detail = subscribe(basic, "tok_declined");

        assertThat(subscription(detail)).containsEntry("status", "CANCELED").containsEntry("cancelReason", "FIRST_PAYMENT_FAILED");
        assertThat(invoices(detail).getFirst()).containsEntry("status", "VOID").containsEntry("failureCode", "card_declined");
    }

    @Test
    void aFirstPaymentWithAnUnknownOutcomeActivatesOnceTheBankAnswers() {
        Map<String, Object> detail = subscribe(basic, "tok_timeout_processed");
        String id = id(detail);
        assertThat(subscription(detail)).containsEntry("status", "INCOMPLETE");

        payments.recoverStale(Duration.ZERO);
        makeRetryDue(id);
        billing.runDue();

        assertThat(subscription(get(id))).containsEntry("status", "ACTIVE");
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void aCustomerCannotHoldTheSamePlanTwiceButCanComeBackAfterCanceling() {
        String id = id(subscribe(basic, "tok_success"));

        assertThat(status(HttpMethod.POST, "/api/v1/subscriptions", subscribeBody(basic, "tok_success"))).isEqualTo(409);
        post("/api/v1/subscriptions/" + id + "/cancel", Map.of("atPeriodEnd", false));

        assertThat(subscription(subscribe(basic, "tok_success"))).containsEntry("status", "ACTIVE");
    }

    @Test
    void manySimultaneousSignUpsChargeOnce() throws Exception {
        List<Integer> results = Concurrency.run(8, index -> () -> status(HttpMethod.POST, "/api/v1/subscriptions", subscribeBody(basic, "tok_success")));

        assertThat(results.stream().filter(code -> code == 201)).hasSize(1);
        assertThat(results.stream().filter(code -> code == 409)).hasSize(7);
        assertThat(capturedTotal()).isEqualTo(10_000);
    }

    @Test
    void anArchivedPlanTakesNoNewSubscribersButKeepsBillingOldOnes() {
        String id = id(subscribe(basic, "tok_success"));
        merchant.post("/api/v1/plans/" + basic + "/archive", null);

        String other = (String) merchant.post("/api/v1/customers", Map.of("email", "yeni@example.com")).get("id");
        assertThat(status(HttpMethod.POST, "/api/v1/subscriptions",
                Map.of("customerId", other, "planId", basic, "cardToken", "tok_success"))).isEqualTo(422);

        expirePeriod(id);
        billing.runDue();

        assertThat(invoices(get(id))).hasSize(2);
    }

    @Test
    void aDuePeriodRenewsOnceAndKeepsTheAnchor() {
        String id = id(subscribe(basic, "tok_success"));
        expirePeriod(id);

        billing.runDue();
        billing.runDue();

        Map<String, Object> detail = get(id);
        assertThat(invoices(detail)).hasSize(2);
        assertThat(invoices(detail).getFirst()).containsEntry("kind", "RENEWAL").containsEntry("status", "PAID");
        assertThat(capturedTotal()).isEqualTo(20_000);
    }

    @Test
    void manyJobsAtOnceBillAPeriodOnce() throws Exception {
        String id = id(subscribe(basic, "tok_success"));
        expirePeriod(id);

        Concurrency.run(8, index -> () -> billing.runDue());

        assertThat(invoices(get(id))).hasSize(2);
        assertThat(capturedTotal()).isEqualTo(20_000);
        assertThat(paymentCount()).isEqualTo(2);
    }

    @Test
    void aSecondInvoiceForTheSamePeriodIsRefusedByTheDatabase() {
        String id = id(subscribe(basic, "tok_success"));

        assertThatThrownBy(() -> owner.sql("""
                        INSERT INTO invoices (id, tenant_id, subscription_id, kind, status, period_start, period_end, subtotal, amount, currency, description)
                        SELECT gen_random_uuid(), tenant_id, subscription_id, 'RENEWAL', 'OPEN', period_start, period_end, subtotal, amount, currency, description
                        FROM invoices WHERE subscription_id = :id
                        """).param("id", UUID.fromString(id)).update())
                .hasMessageContaining("invoices_one_per_period");
    }

    @Test
    void aFailedRenewalRetriesOnScheduleThenEndsTheSubscription() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_declined");
        expirePeriod(id);

        billing.runDue();
        Map<String, Object> afterFirst = get(id);
        assertThat(subscription(afterFirst)).containsEntry("status", "PAST_DUE");
        assertThat(invoices(afterFirst).getFirst()).containsEntry("status", "OPEN").containsEntry("failureCode", "card_declined");
        assertThat(hoursUntilRetry(id)).isBetween(23L, 24L);

        billing.runDue();
        assertThat(number(invoices(get(id)).getFirst(), "attemptCount")).as("not due yet").isEqualTo(1);

        makeRetryDue(id);
        billing.runDue();
        assertThat(hoursUntilRetry(id)).isBetween(71L, 72L);
        makeRetryDue(id);
        billing.runDue();
        assertThat(hoursUntilRetry(id)).isBetween(119L, 120L);
        makeRetryDue(id);
        billing.runDue();

        Map<String, Object> end = get(id);
        assertThat(subscription(end)).containsEntry("status", "CANCELED").containsEntry("cancelReason", "PAYMENT_FAILED");
        assertThat(invoices(end).getFirst()).containsEntry("status", "UNCOLLECTIBLE");
        assertThat(number(invoices(end).getFirst(), "attemptCount")).isEqualTo(4);
        assertThat(capturedTotal()).isEqualTo(10_000);
    }

    @Test
    void aNewCardRecoversAPastDueSubscriptionAtOnce() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_declined");
        expirePeriod(id);
        billing.runDue();

        Map<String, Object> detail = replaceCard(id, "tok_success");

        assertThat(subscription(detail)).containsEntry("status", "ACTIVE");
        assertThat(invoices(detail).getFirst()).containsEntry("status", "PAID");
        assertThat(capturedTotal()).isEqualTo(20_000);
    }

    @Test
    void aPastDueSubscriptionDoesNotPileUpInvoices() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_declined");
        expirePeriod(id);
        billing.runDue();
        expirePeriod(id);

        billing.runDue();

        assertThat(invoices(get(id))).hasSize(2);
    }

    @Test
    void aBankTimeoutWaitsForTheOutcomeAndNeverChargesTwice() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_timeout_processed");
        expirePeriod(id);

        billing.runDue();
        Map<String, Object> waiting = invoices(get(id)).getFirst();
        assertThat(waiting).containsEntry("status", "OPEN");
        assertThat(subscription(get(id))).containsEntry("status", "ACTIVE");
        assertThat(paymentCount()).isEqualTo(2);

        payments.recoverStale(Duration.ZERO);
        makeRetryDue(id);
        billing.runDue();

        assertThat(invoices(get(id)).getFirst()).containsEntry("status", "PAID");
        assertThat(number(invoices(get(id)).getFirst(), "attemptCount")).isEqualTo(1);
        assertThat(paymentCount()).isEqualTo(2);
        assertThat(capturedTotal()).isEqualTo(20_000);
    }

    @Test
    void aNewCardDoesNotDisturbAnAttemptStillWaitingForTheBank() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_timeout_processed");
        expirePeriod(id);
        billing.runDue();

        replaceCard(id, "tok_success");
        payments.recoverStale(Duration.ZERO);
        makeRetryDue(id);
        billing.runDue();

        assertThat(invoices(get(id)).getFirst()).containsEntry("status", "PAID");
        assertThat(paymentCount()).isEqualTo(2);
    }

    @Test
    void anAttemptIsHeldForItsLeaseSoOtherJobsLeaveItAlone() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_timeout_processed");
        expirePeriod(id);

        billing.runDue();

        assertThat(owner.sql("SELECT next_attempt_at > now() + interval '1 minute' FROM invoices WHERE subscription_id = :id AND status = 'OPEN'")
                .param("id", UUID.fromString(id)).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aJobThatOutlivedItsLeaseCannotOverwriteASettledInvoice() {
        String id = id(subscribe(basic, "tok_success"));
        UUID invoice = UUID.fromString((String) invoices(get(id)).getFirst().get("id"));

        billing.reportLateFailure(merchant.tenantId(), invoice, 1);

        assertThat(invoices(get(id)).getFirst()).containsEntry("status", "PAID");
        assertThat(subscription(get(id))).containsEntry("status", "ACTIVE");
    }

    @Test
    void aJobReportingAnOlderAttemptCannotTouchTheCurrentOne() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_declined");
        expirePeriod(id);
        billing.runDue();
        makeRetryDue(id);
        billing.runDue();
        Map<String, Object> before = invoices(get(id)).getFirst();
        UUID invoice = UUID.fromString((String) before.get("id"));
        assertThat(number(before, "attemptCount")).isEqualTo(2);
        owner.sql("UPDATE invoices SET attempt_in_flight = true WHERE id = :id").param("id", invoice).update();

        billing.reportLateFailure(merchant.tenantId(), invoice, 1);

        assertThat(invoices(get(id)).getFirst()).containsEntry("status", "OPEN").containsEntry("failureCode", "card_declined");
    }

    @Test
    void aBankThatLostTheRequestCountsAsAFailedAttempt() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_timeout_lost");
        expirePeriod(id);
        billing.runDue();

        payments.recoverStale(Duration.ZERO);
        makeRetryDue(id);
        billing.runDue();

        assertThat(subscription(get(id))).containsEntry("status", "PAST_DUE");
        assertThat(number(invoices(get(id)).getFirst(), "attemptCount")).isEqualTo(1);
    }

    @Test
    void aCrashAfterThePaymentButBeforeTheInvoiceIsUpdatedDoesNotChargeAgain() {
        String id = id(subscribe(basic, "tok_success"));

        // Renew and then stop dead: the attempt is claimed and its payment made, but nothing recorded the result.
        owner.sql("""
                        UPDATE subscriptions SET period_index = period_index + 1, current_period_start = current_period_end,
                                                 current_period_end = current_period_end + interval '1 month'
                        WHERE id = :id
                        """).param("id", UUID.fromString(id)).update();
        UUID invoice = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO invoices (id, tenant_id, subscription_id, kind, status, period_start, period_end, subtotal, amount,
                                              currency, description, attempt_count, attempt_token, attempt_in_flight, next_attempt_at)
                        VALUES (:invoice, :tenant, :id, 'RENEWAL', 'OPEN', now(), now() + interval '1 month', 10000, 10000, 'TRY',
                                'Abonelik: Temel', 1, 'tok_success', true, now() - interval '1 minute')
                        """)
                .param("invoice", invoice)
                .param("tenant", merchant.tenantId())
                .param("id", UUID.fromString(id))
                .update();
        payments.create(merchant.tenantId(), "invoice:" + invoice + ":1", 10_000, "TRY", "tok_success", "Abonelik: Temel", UUID.fromString(customer));
        assertThat(paymentCount()).isEqualTo(2);

        billing.runDue();

        assertThat(paymentCount()).isEqualTo(2);
        assertThat(capturedTotal()).isEqualTo(20_000);
        assertThat(invoices(get(id)).getFirst()).containsEntry("status", "PAID");
    }

    @Test
    void aFailedCaptureReleasesTheHoldAndCountsAsAFailedAttempt() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_capture_declined");
        expirePeriod(id);

        billing.runDue();

        assertThat(subscription(get(id))).containsEntry("status", "PAST_DUE");
        assertThat(invoices(get(id)).getFirst()).containsEntry("failureCode", "capture_rejected");
        assertThat(owner.sql("SELECT COUNT(*) FROM payments WHERE tenant_id = :t AND status = 'VOIDED'")
                .param("t", merchant.tenantId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void upgradingChargesTheDifferenceForTheTimeLeft() {
        String id = id(subscribe(basic, "tok_success"));
        halfwayThroughPeriod(id);

        Map<String, Object> detail = post("/api/v1/subscriptions/" + id + "/change-plan", Map.of("planId", pro));

        assertThat(subscription(detail)).containsEntry("planName", "Pro").containsEntry("status", "ACTIVE");
        Map<String, Object> invoice = invoices(detail).getFirst();
        assertThat(invoice).containsEntry("kind", "PLAN_CHANGE").containsEntry("status", "PAID");
        assertThat(number(invoice, "amount")).isBetween(9_990L, 10_010L);
        assertThat(lines(invoice)).extracting(line -> line.get("kind")).containsExactly("UNUSED_TIME", "REMAINING_TIME");
        assertThat(lines(invoice).stream().mapToLong(line -> number(line, "amount")).sum()).isEqualTo(number(invoice, "subtotal"));
        assertThat(capturedTotal()).isEqualTo(10_000 + number(invoice, "amount"));
    }

    @Test
    void downgradingKeepsTheDifferenceAsCreditForTheNextInvoice() {
        String id = id(subscribe(pro, "tok_success"));
        halfwayThroughPeriod(id);

        Map<String, Object> changed = post("/api/v1/subscriptions/" + id + "/change-plan", Map.of("planId", basic));

        long credit = number(subscription(changed), "creditBalance");
        assertThat(credit).isBetween(9_990L, 10_010L);
        Map<String, Object> invoice = invoices(changed).getFirst();
        assertThat(invoice).containsEntry("status", "PAID");
        assertThat(number(invoice, "amount")).isZero();
        assertThat(capturedTotal()).isEqualTo(30_000);

        expirePeriod(id);
        billing.runDue();

        Map<String, Object> renewed = get(id);
        Map<String, Object> next = invoices(renewed).getFirst();
        long spent = number(next, "creditApplied");
        assertThat(spent).isEqualTo(Math.min(credit, 10_000));
        assertThat(number(next, "amount")).isEqualTo(10_000 - spent);
        assertThat(number(subscription(renewed), "creditBalance")).isEqualTo(credit - spent);
        assertThat(capturedTotal()).isEqualTo(30_000 + 10_000 - spent);
    }

    @Test
    void creditLargerThanAnInvoiceCarriesOverWithoutAPayment() {
        String id = id(subscribe(basic, "tok_success"));
        owner.sql("UPDATE subscriptions SET credit_balance = 25000 WHERE id = :id").param("id", UUID.fromString(id)).update();
        expirePeriod(id);

        billing.runDue();

        Map<String, Object> detail = get(id);
        assertThat(number(subscription(detail), "creditBalance")).isEqualTo(15_000);
        assertThat(invoices(detail).getFirst()).containsEntry("status", "PAID");
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void aPlanChangeNeedsTheSameCurrencyAndInterval() {
        String id = id(subscribe(basic, "tok_success"));
        String yearly = plan("Yıllık", 100_000, "YEAR");

        assertThat(status(HttpMethod.POST, "/api/v1/subscriptions/" + id + "/change-plan", Map.of("planId", yearly))).isEqualTo(422);
        assertThat(status(HttpMethod.POST, "/api/v1/subscriptions/" + id + "/change-plan", Map.of("planId", basic))).isEqualTo(409);
    }

    @Test
    void cancelingAtPeriodEndKeepsServiceUntilThenAndBillsNoMore() {
        String id = id(subscribe(basic, "tok_success"));

        Map<String, Object> scheduled = post("/api/v1/subscriptions/" + id + "/cancel", Map.of("atPeriodEnd", true));
        assertThat(subscription(scheduled)).containsEntry("status", "ACTIVE").containsEntry("cancelAtPeriodEnd", true);

        expirePeriod(id);
        billing.runDue();

        Map<String, Object> end = get(id);
        assertThat(subscription(end)).containsEntry("status", "CANCELED");
        assertThat(invoices(end)).hasSize(1);
    }

    @Test
    void aScheduledCancelCanBeTakenBack() {
        String id = id(subscribe(basic, "tok_success"));
        post("/api/v1/subscriptions/" + id + "/cancel", Map.of("atPeriodEnd", true));

        post("/api/v1/subscriptions/" + id + "/resume", null);
        expirePeriod(id);
        billing.runDue();

        assertThat(subscription(get(id))).containsEntry("status", "ACTIVE");
        assertThat(invoices(get(id))).hasSize(2);
    }

    @Test
    void cancelingNowVoidsWhatIsUnpaidAndStopsRetries() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_declined");
        expirePeriod(id);
        billing.runDue();

        post("/api/v1/subscriptions/" + id + "/cancel", Map.of("atPeriodEnd", false));

        assertThat(invoices(get(id)).getFirst()).containsEntry("status", "VOID");
        makeRetryDue(id);
        assertThat(capturedTotal()).isEqualTo(10_000);
    }

    @Test
    void aChargeWithAnUnknownOutcomeBlocksCancelingNow() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_timeout_processed");
        expirePeriod(id);
        billing.runDue();

        assertThat(status(HttpMethod.POST, "/api/v1/subscriptions/" + id + "/cancel", Map.of("atPeriodEnd", false))).isEqualTo(409);
    }

    @Test
    void aCanceledSubscriptionStaysCanceled() {
        String id = id(subscribe(basic, "tok_success"));
        post("/api/v1/subscriptions/" + id + "/cancel", Map.of("atPeriodEnd", false));

        assertThat(status(HttpMethod.POST, "/api/v1/subscriptions/" + id + "/cancel", Map.of("atPeriodEnd", false))).isEqualTo(409);
        assertThat(status(HttpMethod.PUT, "/api/v1/subscriptions/" + id + "/card", Map.of("cardToken", "tok_success"))).isEqualTo(409);
        assertThat(status(HttpMethod.POST, "/api/v1/subscriptions/" + id + "/simulate-renewal", null)).isEqualTo(409);
        assertThatThrownBy(() -> owner.sql("UPDATE subscriptions SET status = 'ACTIVE' WHERE id = :id")
                .param("id", UUID.fromString(id)).update())
                .hasMessageContaining("illegal subscription transition");
    }

    @Test
    void aSimulatedRenewalBillsTheNextPeriodNow() {
        String id = id(subscribe(basic, "tok_success"));

        Map<String, Object> detail = post("/api/v1/subscriptions/" + id + "/simulate-renewal", null);

        assertThat(invoices(detail)).hasSize(2);
        assertThat(capturedTotal()).isEqualTo(20_000);
    }

    @Test
    void subscriptionMoneyReachesTheNextPayout() {
        String id = id(subscribe(basic, "tok_success"));
        post("/api/v1/subscriptions/" + id + "/simulate-renewal", null);

        settlement.settleAll(Instant.now().plusSeconds(1));

        assertThat(balance("merchant_payable")).isZero();
        assertThat(balance("fee_revenue")).isEqualTo(500);
    }

    @Test
    void anotherMerchantCanNeitherSeeNorTouchASubscription() {
        String id = id(subscribe(basic, "tok_success"));
        TestMerchant other = TestMerchant.register(port);
        String theirCustomer = (String) other.post("/api/v1/customers", Map.of("email", "x@example.com")).get("id");

        assertThat(TestMerchant.statusOf(other.client().get().uri("/api/v1/subscriptions/" + id).header("X-Api-Key", other.apiKey()))).isEqualTo(404);
        assertThat(TestMerchant.statusOf(other.client().post().uri("/api/v1/subscriptions/" + id + "/simulate-renewal").header("X-Api-Key", other.apiKey()))).isEqualTo(404);
        assertThat(TestMerchant.statusOf(other.client().post().uri("/api/v1/subscriptions").header("X-Api-Key", other.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("customerId", theirCustomer, "planId", basic, "cardToken", "tok_success")))).isEqualTo(422);
        assertThat(TestMerchant.statusOf(other.client().post().uri("/api/v1/subscriptions").header("X-Api-Key", other.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("customerId", customer, "planId", basic, "cardToken", "tok_success")))).isEqualTo(422);
    }

    @Test
    void theSummaryCountsRecurringRevenueByCurrencyAndStatus() {
        subscribe(basic, "tok_success");
        String yearly = plan("Yıllık", 120_000, "YEAR");
        subscribe(yearly, "tok_success");
        String usd = plan("Dolar", 500, "MONTH");
        owner.sql("UPDATE plans SET currency = 'USD' WHERE id = :id").param("id", UUID.fromString(usd)).update();
        subscribe(usd, "tok_success");

        Map<String, Object> summary = merchant.client().get().uri("/api/v1/billing/summary").header("X-Api-Key", merchant.apiKey()).retrieve().body(Map.class);

        assertThat(number(summary, "active")).isEqualTo(3);
        List<Map<String, Object>> revenue = (List<Map<String, Object>>) summary.get("revenue");
        assertThat(revenue).hasSize(2);
        assertThat(revenue.stream().filter(row -> row.get("currency").equals("TRY")).mapToLong(row -> number(row, "monthlyRecurring")).sum()).isEqualTo(20_000);
    }

    @Test
    void aLiveSubscriptionHoldsBackErasingTheCustomer() {
        String id = id(subscribe(basic, "tok_success"));

        assertThat(status(HttpMethod.DELETE, "/api/v1/customers/" + customer, null)).isEqualTo(409);
        post("/api/v1/subscriptions/" + id + "/cancel", Map.of("atPeriodEnd", false));
        assertThat(status(HttpMethod.DELETE, "/api/v1/customers/" + customer, null)).isEqualTo(204);
    }

    @Test
    void theAuditChainStaysIntactAndHoldsNoCardToken() {
        String id = id(subscribe(basic, "tok_success"));
        replaceCard(id, "tok_declined");
        post("/api/v1/subscriptions/" + id + "/cancel", Map.of("atPeriodEnd", false));

        assertThat(owner.sql("SELECT action FROM audit_log WHERE tenant_id = :t").param("t", merchant.tenantId()).query(String.class).list())
                .contains("PLAN_CREATED", "SUBSCRIPTION_CREATED", "SUBSCRIPTION_CARD_UPDATED", "SUBSCRIPTION_CANCELED");
        assertThat(owner.sql("SELECT COUNT(*) FROM audit_log WHERE tenant_id = :t AND metadata::text LIKE '%tok_%'")
                .param("t", merchant.tenantId()).query(Long.class).single()).isZero();
        Map<String, Object> report = merchant.client().get().uri("/api/v1/audit-log/verify")
                .header("Authorization", "Bearer " + merchant.token()).retrieve().body(Map.class);
        assertThat(report).containsEntry("valid", true);
    }

    private String plan(String name, long amount, String interval) {
        return (String) merchant.post("/api/v1/plans", Map.of("name", name, "amount", amount, "currency", "TRY", "billingInterval", interval)).get("id");
    }

    private Map<String, Object> subscribeBody(String plan, String token) {
        return Map.of("customerId", customer, "planId", plan, "cardToken", token);
    }

    private Map<String, Object> subscribe(String plan, String token) {
        return merchant.post("/api/v1/subscriptions", subscribeBody(plan, token));
    }

    private Map<String, Object> post(String path, Object body) {
        return merchant.post(path, body);
    }

    private Map<String, Object> replaceCard(String id, String token) {
        return merchant.client().put().uri("/api/v1/subscriptions/" + id + "/card").header("X-Api-Key", merchant.apiKey())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("cardToken", token)).retrieve().body(Map.class);
    }

    private Map<String, Object> get(String id) {
        return merchant.client().get().uri("/api/v1/subscriptions/" + id).header("X-Api-Key", merchant.apiKey()).retrieve().body(Map.class);
    }

    private int status(HttpMethod method, String path, Object body) {
        var request = merchant.client().method(method).uri(path).header("X-Api-Key", merchant.apiKey());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return TestMerchant.statusOf(request);
    }

    private void expirePeriod(String id) {
        owner.sql("UPDATE subscriptions SET current_period_end = now() - interval '1 second', current_period_start = now() - interval '30 days' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();
    }

    private void halfwayThroughPeriod(String id) {
        owner.sql("UPDATE subscriptions SET current_period_start = now() - interval '15 days', current_period_end = now() + interval '15 days' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();
    }

    private void makeRetryDue(String id) {
        owner.sql("UPDATE invoices SET next_attempt_at = now() - interval '1 second' WHERE subscription_id = :id AND status = 'OPEN'")
                .param("id", UUID.fromString(id)).update();
    }

    private long hoursUntilRetry(String id) {
        return owner.sql("SELECT FLOOR(EXTRACT(EPOCH FROM next_attempt_at - now()) / 3600)::bigint FROM invoices WHERE subscription_id = :id AND status = 'OPEN'")
                .param("id", UUID.fromString(id)).query(Long.class).single();
    }

    private long paymentCount() {
        return owner.sql("SELECT COUNT(*) FROM payments WHERE tenant_id = :t").param("t", merchant.tenantId()).query(Long.class).single();
    }

    private long capturedTotal() {
        return owner.sql("SELECT COALESCE(SUM(captured_amount), 0) FROM payments WHERE tenant_id = :t").param("t", merchant.tenantId()).query(Long.class).single();
    }

    private long balance(String code) {
        return owner.sql("SELECT COALESCE(SUM(balance), 0) FROM ledger_accounts WHERE tenant_id = :t AND code = :code")
                .param("t", merchant.tenantId()).param("code", code).query(Long.class).single();
    }

    private static Map<String, Object> subscription(Map<String, Object> detail) {
        return (Map<String, Object>) detail.get("subscription");
    }

    private static List<Map<String, Object>> invoices(Map<String, Object> detail) {
        return (List<Map<String, Object>>) detail.get("invoices");
    }

    private static List<Map<String, Object>> lines(Map<String, Object> invoice) {
        return (List<Map<String, Object>>) invoice.get("lines");
    }

    private static String id(Map<String, Object> detail) {
        return (String) subscription(detail).get("id");
    }

    private static long number(Map<String, Object> body, String key) {
        return ((Number) body.get(key)).longValue();
    }
}
