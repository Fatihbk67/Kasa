package com.kasa.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.kasa.bank.ThreeDsSigner;
import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class ThreeDSecureApiTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ThreeDsSigner signer;

    RestClient client;
    TestMerchant merchant;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
        merchant = TestMerchant.register(port);
    }

    @Test
    void aCardTheBankTrustsIsChargedWithoutLeavingThePage() {
        String session = newSession(25_000);

        Map<String, Object> paid = pay(session, "tok_success");

        assertThat(paid).containsEntry("status", "COMPLETED").containsEntry("challengeUrl", null)
                // No iyzico keys are configured here, so the page offers the simulated bank only.
                .containsEntry("iyzicoAvailable", false);
        assertThat(onlyPayment(session)).containsEntry("threeDsMethod", "FRICTIONLESS").containsEntry("threeDsEci", "05");
    }

    @Test
    void aChallengedCardIsChargedOnlyAfterTheCodeIsEntered() {
        String session = newSession(25_000);

        Map<String, Object> waiting = pay(session, "tok_3ds");

        assertThat(waiting).containsEntry("status", "AUTHENTICATING");
        String challengeUrl = (String) waiting.get("challengeUrl");
        assertThat(challengeUrl).startsWith("/bank/3ds/");
        assertThat(paymentsOf(session)).isZero();

        UUID challenge = UUID.fromString(challengeUrl.substring("/bank/3ds/".length()));
        Map<String, Object> page = bankPage(challenge);
        assertThat(page).containsEntry("merchantName", "Shop").containsEntry("amount", 25_000).containsEntry("status", "PENDING");

        Map<String, Object> answer = answer(challenge, (String) page.get("testCode"));
        assertThat(answer).containsEntry("status", "Y").containsEntry("returnUrl", "/api/v1/public/checkout/" + session + "/3ds-callback");

        Map<String, Object> done = callback(session, (Map<String, Object>) answer.get("result"));
        assertThat(done).containsEntry("status", "COMPLETED").containsEntry("paymentStatus", "AUTHORIZED");
        assertThat(onlyPayment(session)).containsEntry("threeDsMethod", "CHALLENGE").containsEntry("threeDsEci", "05");
    }

    @Test
    void threeWrongCodesFailTheVerificationAndTheCustomerCanTryAgain() {
        String session = newSession(25_000);
        UUID challenge = challengeOf(pay(session, "tok_3ds"));

        assertThat(answer(challenge, "000000")).containsEntry("status", "PENDING").containsEntry("attemptsLeft", 2);
        assertThat(answer(challenge, "000000")).containsEntry("attemptsLeft", 1);
        Map<String, Object> failed = answer(challenge, "000000");
        assertThat(failed).containsEntry("status", "N");

        Map<String, Object> back = callback(session, (Map<String, Object>) failed.get("result"));
        assertThat(back).containsEntry("status", "OPEN").containsEntry("failureCode", "3ds_failed");
        assertThat(paymentsOf(session)).isZero();
        assertThat(pay(session, "tok_success")).containsEntry("status", "COMPLETED");
    }

    @Test
    void guessingInParallelStillAllowsOnlyThreeTries() throws Exception {
        UUID challenge = challengeOf(pay(newSession(25_000), "tok_3ds"));

        List<String> answers = Concurrency.run(20, index -> () -> client.post().uri("/api/v1/public/bank/3ds/" + challenge)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "000000"))
                .exchange((request, response) -> response.getStatusCode().value() == 200
                        ? (String) response.bodyTo(Map.class).get("status") : "CLOSED"));

        // Exactly two guesses may be told to try again and exactly one ends it; every later one finds it closed.
        assertThat(answers.stream().filter("PENDING"::equals).count()).isEqualTo(2);
        assertThat(answers.stream().filter("N"::equals).count()).isEqualTo(1);
        assertThat(owner.sql("SELECT attempts FROM sim_acs_challenges WHERE id = :id").param("id", challenge).query(Integer.class).single())
                .isEqualTo(3);
        assertThat(bankPage(challenge)).containsEntry("status", "N");
    }

    @Test
    void aVerdictRewrittenInTheBrowserIsRejected() {
        String session = newSession(25_000);
        UUID challenge = challengeOf(pay(session, "tok_3ds"));
        Map<String, Object> failed = answer(challenge, null, true);
        Map<String, Object> forged = new HashMap<>((Map<String, Object>) failed.get("result"));
        forged.put("status", "Y");
        forged.put("eci", "05");

        assertThat(callbackStatus(session, forged)).isEqualTo(400);
        assertThat(paymentsOf(session)).isZero();
        assertThat(get(session)).containsEntry("status", "AUTHENTICATING");
    }

    @Test
    void aVerdictCountsOnceEvenWhenSentFromManyTabs() throws Exception {
        String session = newSession(25_000);
        UUID challenge = challengeOf(pay(session, "tok_3ds"));
        Map<String, Object> passed = (Map<String, Object>) answer(challenge, (String) bankPage(challenge).get("testCode")).get("result");

        List<Integer> results = Concurrency.run(5, index -> () -> callbackStatus(session, passed));

        assertThat(results).containsOnlyOnce(200);
        assertThat(results.stream().filter(code -> code == 409).count()).isEqualTo(4);
        assertThat(paymentsOf(session)).isEqualTo(1);
    }

    @Test
    void oneSessionsVerdictCannotPayForAnother() {
        String first = newSession(25_000);
        UUID challenge = challengeOf(pay(first, "tok_3ds"));
        Map<String, Object> passed = (Map<String, Object>) answer(challenge, (String) bankPage(challenge).get("testCode")).get("result");
        String second = newSession(99_000);
        pay(second, "tok_3ds");

        assertThat(callbackStatus(second, passed)).isEqualTo(404);
        assertThat(paymentsOf(second)).isZero();
    }

    @Test
    void anOldVerdictIsRefusedEvenWithAValidSignature() {
        String session = newSession(25_000);
        UUID challenge = challengeOf(pay(session, "tok_3ds"));
        ThreeDsSigner.SignedResult old = signer.sign(challenge, "Y", "05", Instant.now().minusSeconds(3_600).getEpochSecond());

        assertThat(callbackStatus(session, Map.of("authenticationId", old.authenticationId(), "status", "Y", "eci", "05",
                "issuedAt", old.issuedAt(), "signature", old.signature()))).isEqualTo(400);
    }

    @Test
    void aCustomerWhoNeverReturnsCanStartAgainOnceTheVisitExpires() {
        String session = newSession(25_000);
        UUID challenge = challengeOf(pay(session, "tok_3ds"));
        Map<String, Object> passed = (Map<String, Object>) answer(challenge, (String) bankPage(challenge).get("testCode")).get("result");
        assertThat(TestMerchant.statusOf(client.post().uri("/api/v1/public/checkout/" + session + "/pay")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("cardToken", "tok_success")))).isEqualTo(409);

        owner.sql("UPDATE checkout_sessions SET three_ds_expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", UUID.fromString(session)).update();

        assertThat(get(session)).containsEntry("status", "OPEN").containsEntry("challengeUrl", null);
        assertThat(callbackStatus(session, passed)).isEqualTo(409);
        assertThat(pay(session, "tok_success")).containsEntry("status", "COMPLETED");
        assertThat(paymentsOf(session)).isEqualTo(1);
    }

    @Test
    void theCardWaitsEncryptedWhileTheCustomerIsAtTheBank() {
        UUID challenge = challengeOf(pay(newSession(25_000), "tok_3ds"));

        String stored = owner.sql("SELECT card_token FROM three_ds_authentications WHERE id = :id").param("id", challenge)
                .query(String.class).single();
        assertThat(stored).startsWith("enc1:").doesNotContain("tok_3ds");
    }

    @Test
    void aLinksLastPlaceIsHeldWhileTheBuyerIsAtTheBank() {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Son yer", "amount", 25_000, "currency", "TRY", "maxPayments", 1));
        Map<String, Object> link = merchant.post("/api/v1/payment-links", body);
        String first = linkSession(link, "ilk@example.com");
        assertThat(pay(first, "tok_3ds")).containsEntry("status", "AUTHENTICATING");

        int second = TestMerchant.statusOf(client.post().uri("/api/v1/public/links/" + link.get("slug") + "/sessions")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("email", "ikinci@example.com", "name", "İkinci")));

        assertThat(second).isEqualTo(410);
    }

    @Test
    void instalmentsCostTheBusinessAHigherCommissionNotTheCustomer() {
        String session = newSession(100_000);

        Map<String, Object> paid = client.post().uri("/api/v1/public/checkout/" + session + "/pay").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cardToken", "tok_success", "installments", 6)).retrieve().body(Map.class);

        assertThat(paid).containsEntry("installments", 6);
        Map<String, Object> payment = onlyPayment(session);
        assertThat(payment).containsEntry("installments", 6).containsEntry("amount", 100_000);
        merchant.post("/api/v1/payments/" + payment.get("id") + "/capture", null);
        // 2.5% for a single payment plus 4.5% for six instalments: 7% of 1.000,00.
        assertThat(onlyPayment(session)).containsEntry("feeAmount", 7_000).containsEntry("capturedAmount", 100_000);
    }

    @Test
    void instalmentsAreOfferedOnlyInStandardCountsAndAboveAMinimum() {
        assertThat(payStatus(newSession(100_000), Map.of("cardToken", "tok_success", "installments", 5))).isEqualTo(422);
        assertThat(payStatus(newSession(19_999), Map.of("cardToken", "tok_success", "installments", 3))).isEqualTo(422);
        assertThat(payStatus(newSession(19_999), Map.of("cardToken", "tok_success", "installments", 1))).isEqualTo(200);
    }

    @Test
    void theInstalmentChoiceSurvivesTheTripToTheBank() {
        String session = newSession(100_000);
        Map<String, Object> waiting = client.post().uri("/api/v1/public/checkout/" + session + "/pay").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cardToken", "tok_3ds", "installments", 3)).retrieve().body(Map.class);
        UUID challenge = challengeOf(waiting);

        callback(session, (Map<String, Object>) answer(challenge, (String) bankPage(challenge).get("testCode")).get("result"));

        assertThat(onlyPayment(session)).containsEntry("installments", 3).containsEntry("threeDsMethod", "CHALLENGE");
    }

    private int payStatus(String session, Map<String, Object> body) {
        return TestMerchant.statusOf(client.post().uri("/api/v1/public/checkout/" + session + "/pay")
                .contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private String newSession(long amount) {
        Map<String, Object> created = client.post().uri("/api/v1/checkout-sessions").header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", amount, "currency", "TRY")).retrieve().body(Map.class);
        return (String) created.get("id");
    }

    private String linkSession(Map<String, Object> link, String email) {
        return (String) client.post().uri("/api/v1/public/links/" + link.get("slug") + "/sessions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "name", "Alıcı")).retrieve().body(Map.class).get("id");
    }

    private Map<String, Object> pay(String session, String token) {
        return client.post().uri("/api/v1/public/checkout/" + session + "/pay").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cardToken", token)).retrieve().body(Map.class);
    }

    private Map<String, Object> get(String session) {
        return client.get().uri("/api/v1/public/checkout/" + session).retrieve().body(Map.class);
    }

    private static UUID challengeOf(Map<String, Object> session) {
        return UUID.fromString(((String) session.get("challengeUrl")).substring("/bank/3ds/".length()));
    }

    private Map<String, Object> bankPage(UUID challenge) {
        return client.get().uri("/api/v1/public/bank/3ds/" + challenge).retrieve().body(Map.class);
    }

    private Map<String, Object> answer(UUID challenge, String code) {
        return answer(challenge, code, false);
    }

    private Map<String, Object> answer(UUID challenge, String code, boolean cancel) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("cancel", cancel);
        return client.post().uri("/api/v1/public/bank/3ds/" + challenge).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(Map.class);
    }

    private Map<String, Object> callback(String session, Map<String, Object> result) {
        return client.post().uri("/api/v1/public/checkout/" + session + "/3ds-callback").contentType(MediaType.APPLICATION_JSON)
                .body(result).retrieve().body(Map.class);
    }

    private int callbackStatus(String session, Map<String, Object> result) {
        return TestMerchant.statusOf(client.post().uri("/api/v1/public/checkout/" + session + "/3ds-callback")
                .contentType(MediaType.APPLICATION_JSON).body(result));
    }

    private long paymentsOf(String session) {
        return owner.sql("SELECT COUNT(payment_id) FROM checkout_sessions WHERE id = :id").param("id", UUID.fromString(session))
                .query(Long.class).single();
    }

    private Map<String, Object> onlyPayment(String session) {
        String paymentId = owner.sql("SELECT payment_id::text FROM checkout_sessions WHERE id = :id").param("id", UUID.fromString(session))
                .query(String.class).single();
        return (Map<String, Object>) client.get().uri("/api/v1/payments/" + paymentId).header("X-Api-Key", merchant.apiKey())
                .retrieve().body(Map.class).get("payment");
    }
}
