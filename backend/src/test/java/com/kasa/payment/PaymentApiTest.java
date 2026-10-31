package com.kasa.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.kasa.ledger.LedgerService;
import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;

/** Exercises payments over real HTTP as a merchant's server would, including every bank failure mode. */
class PaymentApiTest extends PostgresIntegrationTest {

    private record Merchant(String apiKey, UUID tenantId) {
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    PaymentService payments;

    @Autowired
    LedgerService ledger;

    Merchant merchant;

    @BeforeEach
    void registerMerchant() {
        merchant = newMerchant();
    }

    @Test
    void authorizeThenCaptureSplitsMoneyInTheLedger() {
        String id = authorized(10_000, "tok_success");

        ResponseEntity<Map> captured = capture(merchant, id, key(), null);

        assertThat(captured.getStatusCode().value()).isEqualTo(200);
        assertThat(payment(captured).get("status")).isEqualTo("CAPTURED");
        assertThat(number(payment(captured), "feeAmount")).isEqualTo(250);
        assertThat(balance("psp_clearing")).isEqualTo(10_000);
        assertThat(balance("merchant_payable")).isEqualTo(9_750);
        assertThat(balance("fee_revenue")).isEqualTo(250);
        assertBooksBalance();
    }

    @Test
    void authorizingMovesNoMoney() {
        authorized(10_000, "tok_success");

        assertThat(ledgerAccountCount()).isZero();
    }

    @Test
    void partialCaptureChargesTheFeeOnTheCapturedAmountRoundingHalfUp() {
        String id = authorized(10_000, "tok_success");

        ResponseEntity<Map> captured = capture(merchant, id, key(), 101L);

        assertThat(number(payment(captured), "capturedAmount")).isEqualTo(101);
        assertThat(number(payment(captured), "feeAmount")).isEqualTo(3);
        assertThat(balance("merchant_payable")).isEqualTo(98);
    }

    @Test
    void aDeclinedCardIsRecordedAndMovesNoMoney() {
        ResponseEntity<Map> response = create(merchant, key(), 5_000, "tok_insufficient_funds");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(payment(response).get("status")).isEqualTo("DECLINED");
        assertThat(payment(response).get("failureCode")).isEqualTo("insufficient_funds");
        assertThat(ledgerAccountCount()).isZero();
    }

    @Test
    void aRawCardNumberIsRejectedBeforeReachingTheSystem() {
        assertThat(create(merchant, key(), 5_000, "4111111111111111").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void voidingReleasesAnAuthorizationAndBlocksCapture() {
        String id = authorized(5_000, "tok_success");

        assertThat(payment(voidPayment(merchant, id, key())).get("status")).isEqualTo("VOIDED");

        assertThat(capture(merchant, id, key(), null).getStatusCode().value()).isEqualTo(409);
        assertThat(ledgerAccountCount()).isZero();
    }

    @Test
    void illegalOperationsAreRefused() {
        String id = authorized(5_000, "tok_success");

        assertThat(refund(merchant, id, key(), null).getStatusCode().value()).isEqualTo(409);
        assertThat(capture(merchant, id, key(), 5_001L).getStatusCode().value()).isEqualTo(422);

        capture(merchant, id, key(), null);

        assertThat(voidPayment(merchant, id, key()).getStatusCode().value()).isEqualTo(409);
        assertThat(capture(merchant, id, key(), null).getStatusCode().value()).isEqualTo(409);
        assertThat(refund(merchant, id, key(), 5_001L).getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void aFailedBankCaptureLeavesThePaymentAuthorized() {
        String id = authorized(5_000, "tok_capture_declined");

        ResponseEntity<Map> response = capture(merchant, id, key(), null);

        assertThat(payment(response).get("status")).isEqualTo("AUTHORIZED");
        assertThat(operation(response).get("status")).isEqualTo("FAILED");
        assertThat(operation(response).get("failureCode")).isEqualTo("capture_rejected");
        assertThat(ledgerAccountCount()).isZero();
    }

    @Test
    void refundsAccumulateAndTheMerchantBearsTheFee() {
        String id = authorized(10_000, "tok_success");
        capture(merchant, id, key(), null);

        ResponseEntity<Map> partial = refund(merchant, id, key(), 4_000L);
        assertThat(payment(partial).get("status")).isEqualTo("PARTIALLY_REFUNDED");

        ResponseEntity<Map> rest = refund(merchant, id, key(), null);
        assertThat(payment(rest).get("status")).isEqualTo("REFUNDED");
        assertThat(number(payment(rest), "refundedAmount")).isEqualTo(10_000);

        assertThat(refund(merchant, id, key(), 1L).getStatusCode().value()).isEqualTo(409);
        assertThat(balance("psp_clearing")).isZero();
        assertThat(balance("merchant_payable")).isEqualTo(-250);
        assertThat(balance("fee_revenue")).isEqualTo(250);
        assertBooksBalance();
    }

    @Test
    void replayingACreateReturnsTheSamePaymentWithoutASecondBankCall() {
        String key = key();

        ResponseEntity<Map> first = create(merchant, key, 5_000, "tok_success");
        ResponseEntity<Map> second = create(merchant, key, 5_000, "tok_success");

        assertThat(payment(second).get("id")).isEqualTo(payment(first).get("id"));
        assertThat(operationCount(UUID.fromString((String) payment(first).get("id")))).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() {
        String key = key();
        create(merchant, key, 5_000, "tok_success");

        assertThat(create(merchant, key, 6_000, "tok_success").getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void replayingACaptureDoesNotMoveMoneyTwice() {
        String id = authorized(10_000, "tok_success");
        String key = key();

        capture(merchant, id, key, null);
        ResponseEntity<Map> replay = capture(merchant, id, key, null);

        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(balance("psp_clearing")).isEqualTo(10_000);
    }

    @Test
    void theIdempotencyKeyIsRequired() {
        ResponseEntity<Map> response = client().post().uri("/api/v1/payments")
                .header("X-Api-Key", merchant.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", 100, "currency", "TRY", "cardToken", "tok_success"))
                .retrieve().onStatus(status -> true, (req, res) -> { }).toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void theSameKeyFromTwoMerchantsCreatesTwoPayments() {
        String key = key();
        Merchant other = newMerchant();

        ResponseEntity<Map> mine = create(merchant, key, 5_000, "tok_success");
        ResponseEntity<Map> theirs = create(other, key, 5_000, "tok_success");

        assertThat(payment(theirs).get("id")).isNotEqualTo(payment(mine).get("id"));
    }

    @Test
    void anAuthorizationTheBankProcessedButNeverAnsweredIsRecovered() {
        ResponseEntity<Map> response = create(merchant, key(), 5_000, "tok_timeout_processed");
        String id = (String) payment(response).get("id");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(payment(response).get("status")).isEqualTo("PENDING");

        payments.recoverStale(Duration.ZERO);

        assertThat(status(id)).isEqualTo("AUTHORIZED");
        assertThat(capture(merchant, id, key(), null).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anAuthorizationTheBankNeverReceivedIsMarkedFailed() {
        ResponseEntity<Map> response = create(merchant, key(), 5_000, "tok_timeout_lost");
        String id = (String) payment(response).get("id");

        payments.recoverStale(Duration.ZERO);

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(ledgerAccountCount()).isZero();
    }

    @Test
    void aCaptureWithAnUnknownOutcomeBlocksOtherOperationsThenPostsExactlyOnce() {
        String id = authorized(10_000, "tok_capture_timeout");
        String key = key();

        ResponseEntity<Map> response = capture(merchant, id, key, null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(ledgerAccountCount()).isZero();
        assertThat(voidPayment(merchant, id, key()).getStatusCode().value()).isEqualTo(409);

        payments.recoverStale(Duration.ZERO);
        payments.recoverStale(Duration.ZERO);

        assertThat(status(id)).isEqualTo("CAPTURED");
        assertThat(balance("psp_clearing")).isEqualTo(10_000);
        assertThat(capture(merchant, id, key, null).getStatusCode().value()).isEqualTo(200);
        assertBooksBalance();
    }

    @Test
    void aRefundWithAnUnknownOutcomeIsRecovered() {
        String id = authorized(10_000, "tok_refund_timeout");
        capture(merchant, id, key(), null);

        assertThat(refund(merchant, id, key(), 1_000L).getStatusCode().value()).isEqualTo(202);
        assertThat(balance("psp_clearing")).isEqualTo(10_000);

        payments.recoverStale(Duration.ZERO);

        assertThat(status(id)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(balance("psp_clearing")).isEqualTo(9_000);
        assertBooksBalance();
    }

    @Test
    void concurrentRecoveryAppliesARefundOutcomeOnce() throws Exception {
        String id = authorized(10_000, "tok_refund_timeout");
        capture(merchant, id, key(), null);
        refund(merchant, id, key(), 1_000L);

        Concurrency.run(10, i -> () -> payments.recoverStale(Duration.ZERO));

        Map<String, Object> detail = send(merchant, HttpMethod.GET, "/api/v1/payments/" + id, null, null).getBody();
        assertThat(number((Map<String, Object>) detail.get("payment"), "refundedAmount")).isEqualTo(1_000);
        assertThat(balance("psp_clearing")).isEqualTo(9_000);
        assertThat(owner.sql("SELECT count(*) FROM webhook_events WHERE tenant_id = :t AND type = 'payment.refunded'")
                .param("t", merchant.tenantId()).query(Integer.class).single()).isEqualTo(1);
        assertBooksBalance();
    }

    @Test
    void concurrentDuplicateCreatesProduceOnePayment() throws Exception {
        String key = key();

        List<ResponseEntity<Map>> responses = Concurrency.run(20, i -> () -> create(merchant, key, 5_000, "tok_success"));

        assertThat(responses.stream().map(r -> payment(r).get("id")).distinct()).hasSize(1);
        assertThat(owner.sql("SELECT count(*) FROM payments WHERE tenant_id = :t")
                .param("t", merchant.tenantId()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentCapturesMoveMoneyOnce() throws Exception {
        String id = authorized(10_000, "tok_success");

        List<ResponseEntity<Map>> responses = Concurrency.run(20, i -> () -> capture(merchant, id, key(), null));

        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 200)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 409)).hasSize(19);
        assertThat(balance("psp_clearing")).isEqualTo(10_000);
        assertBooksBalance();
    }

    @Test
    void aConcurrentCaptureAndVoidCannotBothWin() throws Exception {
        for (int round = 0; round < 10; round++) {
            String id = authorized(1_000, "tok_success");

            List<ResponseEntity<Map>> responses = Concurrency.run(2, i -> () -> i == 0
                    ? capture(merchant, id, key(), null)
                    : voidPayment(merchant, id, key()));

            assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 200)).hasSize(1);
            assertThat(status(id)).isIn("CAPTURED", "VOIDED");
        }
        assertBooksBalance();
    }

    @Test
    void concurrentRefundsCannotExceedTheCapturedAmount() throws Exception {
        String id = authorized(1_000, "tok_success");
        capture(merchant, id, key(), null);

        List<ResponseEntity<Map>> responses = Concurrency.run(20, i -> () -> refund(merchant, id, key(), 400L));

        long succeeded = responses.stream().filter(r -> r.getStatusCode().value() == 200).count();
        assertThat(succeeded).isBetween(1L, 2L);
        assertThat(balance("psp_clearing")).isEqualTo(1_000 - succeeded * 400);
        assertBooksBalance();
    }

    @Test
    void anotherMerchantCannotSeeOrTouchAPayment() {
        String id = authorized(5_000, "tok_success");
        Merchant other = newMerchant();

        assertThat(send(other, HttpMethod.GET, "/api/v1/payments/" + id, null, null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(capture(other, id, key(), null).getStatusCode().value()).isEqualTo(404);
        assertThat((List<?>) send(other, HttpMethod.GET, "/api/v1/payments", null, null).getBody().get("items")).isEmpty();
        assertThat(status(id)).isEqualTo("AUTHORIZED");
    }

    @Test
    void paymentsRequireAuthentication() {
        ResponseEntity<Map> response = client().get().uri("/api/v1/payments")
                .retrieve().onStatus(status -> true, (req, res) -> { }).toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void listingPagesThroughPaymentsNewestFirstAndFiltersByStatus() {
        String oldest = authorized(100, "tok_success");
        String middle = authorized(200, "tok_success");
        String newest = authorized(300, "tok_success");
        create(merchant, key(), 400, "tok_declined");

        Map<String, Object> firstPage = send(merchant, HttpMethod.GET, "/api/v1/payments?status=AUTHORIZED&limit=2",
                null, null).getBody();
        Map<String, Object> secondPage = send(merchant, HttpMethod.GET,
                "/api/v1/payments?status=AUTHORIZED&limit=2&before=" + firstPage.get("nextCursor"), null, null).getBody();

        assertThat(ids(firstPage)).containsExactly(newest, middle);
        assertThat(ids(secondPage)).containsExactly(oldest);
        assertThat(secondPage.get("nextCursor")).isNull();
    }

    @Test
    void detailShowsTheOperationHistory() {
        String id = authorized(5_000, "tok_success");
        capture(merchant, id, key(), null);

        Map<String, Object> detail = send(merchant, HttpMethod.GET, "/api/v1/payments/" + id, null, null).getBody();
        List<Map<String, Object>> operations = (List<Map<String, Object>>) detail.get("operations");

        assertThat(operations).extracting(o -> o.get("type")).containsExactly("AUTHORIZE", "CAPTURE");
        assertThat(detail.toString()).doesNotContain("bankReference", "requestHash");
    }

    @Test
    void theDatabaseRejectsIllegalTransitionsAndAmountsEvenWithoutTheService() {
        String id = authorized(5_000, "tok_success");
        capture(merchant, id, key(), null);
        UUID paymentId = UUID.fromString(id);

        assertThatThrownBy(() -> owner.sql("UPDATE payments SET status = 'AUTHORIZED' WHERE id = :id")
                .param("id", paymentId).update()).hasStackTraceContaining("illegal payment transition");
        assertThatThrownBy(() -> owner.sql("UPDATE payments SET refunded_amount = 5001 WHERE id = :id")
                .param("id", paymentId).update()).hasStackTraceContaining("refunded_within_captured");
    }

    private String authorized(long amount, String token) {
        ResponseEntity<Map> response = create(merchant, key(), amount, token);
        assertThat(payment(response).get("status")).isEqualTo("AUTHORIZED");
        return (String) payment(response).get("id");
    }

    private ResponseEntity<Map> create(Merchant who, String key, long amount, String token) {
        return send(who, HttpMethod.POST, "/api/v1/payments", key,
                Map.of("amount", amount, "currency", "TRY", "cardToken", token, "description", "test"));
    }

    private ResponseEntity<Map> capture(Merchant who, String id, String key, Long amount) {
        return send(who, HttpMethod.POST, "/api/v1/payments/" + id + "/capture", key, amountBody(amount));
    }

    private ResponseEntity<Map> voidPayment(Merchant who, String id, String key) {
        return send(who, HttpMethod.POST, "/api/v1/payments/" + id + "/void", key, null);
    }

    private ResponseEntity<Map> refund(Merchant who, String id, String key, Long amount) {
        return send(who, HttpMethod.POST, "/api/v1/payments/" + id + "/refunds", key, amountBody(amount));
    }

    private static Map<String, Object> amountBody(Long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        return body;
    }

    private ResponseEntity<Map> send(Merchant who, HttpMethod method, String path, String key, Object body) {
        RestClient.RequestBodySpec request = client().method(method).uri(path).header("X-Api-Key", who.apiKey());
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().onStatus(status -> true, (req, res) -> { }).toEntity(Map.class);
    }

    private Merchant newMerchant() {
        String email = "merchant-" + UUID.randomUUID() + "@example.com";
        Map<String, Object> registered = client().post().uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("businessName", "Shop", "email", email, "password", "correct-horse-battery"))
                .retrieve().body(Map.class);
        Map<String, Object> created = client().post().uri("/api/v1/api-keys")
                .header("Authorization", "Bearer " + registered.get("accessToken"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "server"))
                .retrieve().body(Map.class);
        return new Merchant((String) created.get("key"), UUID.fromString((String) registered.get("tenantId")));
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private String status(String id) {
        Map<String, Object> detail = send(merchant, HttpMethod.GET, "/api/v1/payments/" + id, null, null).getBody();
        return (String) ((Map<String, Object>) detail.get("payment")).get("status");
    }

    private long balance(String code) {
        return owner.sql("SELECT balance FROM ledger_accounts WHERE tenant_id = :t AND code = :c")
                .param("t", merchant.tenantId())
                .param("c", code)
                .query(Long.class)
                .single();
    }

    private int ledgerAccountCount() {
        return owner.sql("SELECT count(*) FROM ledger_accounts WHERE tenant_id = :t")
                .param("t", merchant.tenantId())
                .query(Integer.class)
                .single();
    }

    private int operationCount(UUID paymentId) {
        return owner.sql("SELECT count(*) FROM payment_operations WHERE payment_id = :p")
                .param("p", paymentId)
                .query(Integer.class)
                .single();
    }

    private void assertBooksBalance() {
        assertThat(ledger.findDriftedAccounts(merchant.tenantId())).isEmpty();
    }

    private static List<Object> ids(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream().map(item -> item.get("id")).toList();
    }

    private static Map<String, Object> payment(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("payment");
    }

    private static Map<String, Object> operation(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("operation");
    }

    private static long number(Map<String, Object> map, String field) {
        return ((Number) map.get(field)).longValue();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
