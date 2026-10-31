package com.kasa.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.kasa.payment.PaymentService;
import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

/** The public endpoints are called with no credentials at all, as a customer's browser does. */
class CheckoutApiTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    PaymentService payments;

    TestMerchant merchant;

    @BeforeEach
    void registerMerchant() {
        merchant = TestMerchant.register(port);
    }

    @Test
    void aCustomerCanSeeTheSessionWithoutCredentialsButNotTheTenant() {
        String session = createSession(12_500);

        ResponseEntity<Map> response = view(session);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("merchantName", "Shop").containsEntry("status", "OPEN");
        assertThat(response.getBody()).doesNotContainKeys("tenantId");
        assertThat(((Number) response.getBody().get("amount")).longValue()).isEqualTo(12_500);
    }

    @Test
    void payingAuthorizesAPaymentForTheMerchant() {
        String session = createSession(5_000);

        ResponseEntity<Map> paid = pay(session, "tok_success");

        assertThat(paid.getBody()).containsEntry("status", "COMPLETED").containsEntry("paymentStatus", "AUTHORIZED");
        assertThat(paymentsOfMerchant()).hasSize(1);
    }

    @Test
    void aDeclinedCardLeavesTheSessionOpenForAnotherTry() {
        String session = createSession(5_000);

        ResponseEntity<Map> declined = pay(session, "tok_insufficient_funds");
        assertThat(declined.getBody()).containsEntry("status", "OPEN").containsEntry("failureCode", "insufficient_funds");

        ResponseEntity<Map> retried = pay(session, "tok_success");
        assertThat(retried.getBody()).containsEntry("status", "COMPLETED");
        assertThat(paymentsOfMerchant()).hasSize(2);
    }

    @Test
    void payingACompletedSessionAgainDoesNotChargeTwice() {
        String session = createSession(5_000);

        pay(session, "tok_success");
        ResponseEntity<Map> again = pay(session, "tok_success");

        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(paymentsOfMerchant()).hasSize(1);
    }

    @Test
    void concurrentPaymentsOfASessionChargeOnce() throws Exception {
        String session = createSession(5_000);

        List<ResponseEntity<Map>> responses = Concurrency.run(20, i -> () -> pay(session, "tok_success"));

        assertThat(responses).allMatch(r -> r.getStatusCode().value() == 200 || r.getStatusCode().value() == 409);
        assertThat(paymentsOfMerchant()).hasSize(1);
    }

    @Test
    void anUnknownBankOutcomeShowsAsPendingThenResolves() {
        String session = createSession(5_000);

        ResponseEntity<Map> paid = pay(session, "tok_timeout_processed");
        assertThat(paid.getBody()).containsEntry("paymentStatus", "PENDING");

        payments.recoverStale(Duration.ZERO);

        assertThat(view(session).getBody()).containsEntry("paymentStatus", "AUTHORIZED");
    }

    @Test
    void anExpiredSessionCannotBePaid() {
        String session = createSession(5_000);
        owner.sql("UPDATE checkout_sessions SET expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", UUID.fromString(session))
                .update();

        assertThat(view(session).getBody()).containsEntry("status", "EXPIRED");
        assertThat(pay(session, "tok_success").getStatusCode().value()).isEqualTo(410);
        assertThat(paymentsOfMerchant()).isEmpty();
    }

    @Test
    void anUnknownSessionIsNotFound() {
        assertThat(view(UUID.randomUUID().toString()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aRawCardNumberIsRejected() {
        assertThat(pay(createSession(5_000), "4111111111111111").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void creatingASessionRequiresCredentials() {
        ResponseEntity<Map> response = anonymous().post().uri("/api/v1/checkout-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", 100, "currency", "TRY"))
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void theMerchantsBalanceReflectsCapturedPaymentsOnly() {
        merchant.capturedPayment(10_000);
        merchant.authorized(5_000, "tok_success");

        List<Map<String, Object>> balances = merchant.client().get().uri("/api/v1/balances")
                .header("X-Api-Key", merchant.apiKey())
                .retrieve().body(List.class);

        assertThat(balances).hasSize(1);
        assertThat(balances.getFirst()).containsEntry("currency", "TRY").containsEntry("available", 9_750);
        assertThat(balances.getFirst()).containsEntry("inTransit", 0);
    }

    @Test
    void aCaptureOnAnAuthorizationTheBankForgotIsDeclinedNotAServerError() {
        UUID payment = merchant.authorized(5_000, "tok_success");
        owner.sql("UPDATE payments SET bank_reference = 'bank_forgotten' WHERE id = :id").param("id", payment).update();

        Map<String, Object> result = merchant.post("/api/v1/payments/" + payment + "/capture", null);

        assertThat((Map<String, Object>) result.get("operation")).containsEntry("failureCode", "unknown_authorization");
        assertThat((Map<String, Object>) result.get("payment")).containsEntry("status", "AUTHORIZED");
    }

    private String createSession(long amount) {
        Map<String, Object> created = merchant.client().post().uri("/api/v1/checkout-sessions")
                .header("X-Api-Key", merchant.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amount, "currency", "TRY", "description", "Test"))
                .retrieve().body(Map.class);
        return (String) created.get("id");
    }

    private ResponseEntity<Map> view(String session) {
        return anonymous().get().uri("/api/v1/public/checkout/" + session)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
    }

    private ResponseEntity<Map> pay(String session, String token) {
        return anonymous().post().uri("/api/v1/public/checkout/" + session + "/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cardToken", token))
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
    }

    private List<?> paymentsOfMerchant() {
        Map<String, Object> page = merchant.client().get().uri("/api/v1/payments")
                .header("X-Api-Key", merchant.apiKey())
                .retrieve().body(Map.class);
        return (List<?>) page.get("items");
    }

    private RestClient anonymous() {
        return RestClient.create("http://localhost:" + port);
    }
}
