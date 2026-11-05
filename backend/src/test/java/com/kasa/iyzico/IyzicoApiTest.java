package com.kasa.iyzico;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * Pays through a stand-in for iyzico's API that checks our request signatures and signs its answers the way
 * iyzico does, so the whole round trip runs: payment page, callback, verification, capture and refunds.
 */
class IyzicoApiTest extends PostgresIntegrationTest {

    private static final String API_KEY = "sandbox-test-api-key";
    private static final String SECRET = "sandbox-test-secret-key";
    private static final FakeIyzico IYZICO = new FakeIyzico();

    @DynamicPropertySource
    static void iyzico(DynamicPropertyRegistry registry) {
        registry.add("kasa.iyzico.api-key", () -> API_KEY);
        registry.add("kasa.iyzico.secret-key", () -> SECRET);
        registry.add("kasa.iyzico.base-url", IYZICO::url);
        registry.add("kasa.public-url", () -> "https://kasa.example");
    }

    @Value("${local.server.port}")
    int port;

    RestClient client;
    TestMerchant merchant;

    @BeforeEach
    void setUp() {
        // Our callback answers with a redirect, which the tests read rather than follow.
        client = RestClient.builder().baseUrl("http://localhost:" + port)
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()))
                .build();
        merchant = TestMerchant.register(port);
        IYZICO.reset();
    }

    @Test
    void aCardPaidOnIyzicosPageIsRecordedAndCollected() {
        String session = newSession(25_000);

        String pageUrl = start(session);

        Map<String, Object> sale = IYZICO.lastSale();
        assertThat(sale).containsEntry("price", "250.00").containsEntry("paidPrice", "250.00").containsEntry("currency", "TRY")
                .containsEntry("callbackUrl", "https://kasa.example/api/v1/public/checkout/" + session + "/iyzico/callback")
                .containsEntry("enabledInstallments", List.of(1, 2, 3, 6, 9, 12));
        assertThat(get(session)).containsEntry("status", "AUTHENTICATING").containsEntry("challengeUrl", pageUrl);

        assertThat(callback(session, IYZICO.tokenFor(pageUrl))).isEqualTo("/pay/" + session);

        assertThat(get(session)).containsEntry("status", "COMPLETED").containsEntry("paymentStatus", "CAPTURED");
        assertThat(onlyPayment(session)).containsEntry("processor", "IYZICO").containsEntry("cardLabel", "Mastercard •••• 0008")
                .containsEntry("capturedAmount", 25_000).containsEntry("status", "CAPTURED");
    }

    @Test
    void aSmallPaymentIsOfferedInOneInstalmentOnly() {
        start(newSession(5_000));

        assertThat(IYZICO.lastSale()).containsEntry("price", "50.00").containsEntry("enabledInstallments", List.of(1));
    }

    @Test
    void theCallbackCountsOnceHoweverOftenItArrives() throws Exception {
        String session = newSession(25_000);
        String token = IYZICO.tokenFor(start(session));

        List<String> redirects = Concurrency.run(5, index -> () -> callback(session, token));

        assertThat(redirects).containsOnly("/pay/" + session);
        assertThat(paymentsOf(session)).isEqualTo(1);
        // A replay must not mistake the payment it already recorded for a stray charge and give the money back.
        assertThat(IYZICO.cancelled).isEmpty();
        assertThat(get(session)).containsEntry("paymentStatus", "CAPTURED");
        assertThat(owner.sql("SELECT COUNT(*) FROM payments WHERE tenant_id = :t").param("t", merchant.tenantId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aResultWithoutIyzicosSignatureIsNotTrusted() {
        String session = newSession(25_000);
        String token = IYZICO.tokenFor(start(session));
        IYZICO.forgeSignatures = true;

        assertThat(callback(session, token)).isEqualTo("/pay/" + session + "?iyzico=error");

        assertThat(paymentsOf(session)).isZero();
        assertThat(get(session)).containsEntry("status", "AUTHENTICATING");
    }

    @Test
    void aSignedResultForAnotherAmountIsRefusedAndTheMoneyGoesBack() {
        String session = newSession(25_000);
        String token = IYZICO.tokenFor(start(session));
        IYZICO.chargedPrice = "1.0";

        callback(session, token);

        assertThat(paymentsOf(session)).isZero();
        assertThat(get(session)).containsEntry("status", "OPEN").containsEntry("failureCode", "iyzico_declined");
        assertThat(IYZICO.cancelled).hasSize(1);
    }

    @Test
    void aFailedPaymentLetsTheCustomerTryAgain() {
        String session = newSession(25_000);
        String token = IYZICO.tokenFor(start(session));
        IYZICO.declineNext = true;

        callback(session, token);

        assertThat(get(session)).containsEntry("status", "OPEN").containsEntry("failureCode", "iyzico_declined");
        assertThat(paymentsOf(session)).isZero();
        assertThat(client.post().uri("/api/v1/public/checkout/" + session + "/pay").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cardToken", "tok_success")).retrieve().body(Map.class)).containsEntry("status", "COMPLETED");
    }

    @Test
    void aTokenFromAnotherSessionPaysNothing() {
        String first = newSession(25_000);
        String token = IYZICO.tokenFor(start(first));
        String second = newSession(25_000);

        assertThat(callback(second, token)).isEqualTo("/pay/" + second + "?iyzico=error");
        assertThat(callback(second, "made-up-token")).isEqualTo("/pay/" + second + "?iyzico=error");

        assertThat(paymentsOf(first)).isZero();
        assertThat(paymentsOf(second)).isZero();
    }

    @Test
    void refundsGoBackToTheCardThroughIyzico() {
        String session = newSession(25_000);
        callback(session, IYZICO.tokenFor(start(session)));
        UUID payment = UUID.fromString((String) onlyPayment(session).get("id"));

        merchant.refund(payment, 10_000);

        assertThat(IYZICO.refunds).containsExactly(Map.of("paymentId", IYZICO.lastPaymentId(), "price", "100.00"));
        assertThat(onlyPayment(session)).containsEntry("status", "PARTIALLY_REFUNDED").containsEntry("refundedAmount", 10_000);

        merchant.refund(payment, 15_000);

        assertThat(onlyPayment(session)).containsEntry("status", "REFUNDED");
        assertThat(IYZICO.refunds).hasSize(2);
    }

    @Test
    void aRefundIyzicoRefusesMovesNoMoney() {
        String session = newSession(25_000);
        callback(session, IYZICO.tokenFor(start(session)));
        UUID payment = UUID.fromString((String) onlyPayment(session).get("id"));
        IYZICO.refuseRefunds = true;

        Map<String, Object> result = merchant.post("/api/v1/payments/" + payment + "/refunds", Map.of("amount", 10_000));

        assertThat((Map<String, Object>) result.get("operation")).containsEntry("status", "FAILED");
        assertThat(onlyPayment(session)).containsEntry("status", "CAPTURED").containsEntry("refundedAmount", 0);
    }

    @Test
    void aPaymentOurFraudRulesBlockIsCancelledAtIyzico() {
        Map<String, Object> rule = new HashMap<>(Map.of("kind", "AMOUNT_ABOVE", "threshold", 10_000, "currency", "TRY", "action", "BLOCK"));
        client.post().uri("/api/v1/risk/rules").header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(rule).retrieve().toBodilessEntity();
        String session = newSession(25_000);

        callback(session, IYZICO.tokenFor(start(session)));

        assertThat(onlyPayment(session)).containsEntry("status", "DECLINED").containsEntry("failureCode", "risk_blocked");
        assertThat(IYZICO.cancelled).containsExactly(IYZICO.lastPaymentId());
    }

    @Test
    void aHeldPaymentRejectedOnReviewIsGivenBack() {
        Map<String, Object> rule = new HashMap<>(Map.of("kind", "AMOUNT_ABOVE", "threshold", 10_000, "currency", "TRY", "action", "REVIEW"));
        client.post().uri("/api/v1/risk/rules").header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(rule).retrieve().toBodilessEntity();
        String session = newSession(25_000);
        callback(session, IYZICO.tokenFor(start(session)));
        Map<String, Object> held = onlyPayment(session);
        assertThat(held).containsEntry("status", "AUTHORIZED").containsEntry("reviewStatus", "OPEN");

        client.method(HttpMethod.POST).uri("/api/v1/risk/reviews/" + held.get("id") + "/reject")
                .header("Authorization", "Bearer " + merchant.token()).retrieve().toBodilessEntity();

        assertThat(onlyPayment(session)).containsEntry("status", "VOIDED");
        assertThat(IYZICO.cancelled).containsExactly(IYZICO.lastPaymentId());
    }

    @Test
    void oneIyzicoChargeCanBackOnlyOnePayment() {
        String session = newSession(25_000);
        callback(session, IYZICO.tokenFor(start(session)));
        String token = IyzicoGateway.cardToken("5528790008", IYZICO.lastPaymentId());

        assertThat(gateway.authorize(UUID.randomUUID(), token, 25_000, "TRY").approved()).isFalse();
    }

    @Test
    void ourRequestsCarryAValidIyzicoSignature() {
        start(newSession(25_000));

        assertThat(IYZICO.badSignatures).isZero();
        assertThat(IYZICO.requests.get()).isPositive();
    }

    @Autowired
    IyzicoGateway gateway;

    private String newSession(long amount) {
        Map<String, Object> created = client.post().uri("/api/v1/checkout-sessions").header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", amount, "currency", "TRY")).retrieve().body(Map.class);
        return (String) created.get("id");
    }

    private String start(String session) {
        return (String) client.post().uri("/api/v1/public/checkout/" + session + "/iyzico").retrieve().body(Map.class).get("pageUrl");
    }

    /** Posts the form iyzico's page submits, returning where our callback sends the browser next. */
    private String callback(String session, String token) {
        return client.post().uri("/api/v1/public/checkout/" + session + "/iyzico/callback")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("token=" + token)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(303);
                    return response.getHeaders().getLocation().toString();
                });
    }

    private Map<String, Object> get(String session) {
        return client.get().uri("/api/v1/public/checkout/" + session).retrieve().body(Map.class);
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

    /** Behaves like iyzico's sandbox for the calls we make, with switches to make it misbehave. */
    static final class FakeIyzico {

        private final JsonMapper json = JsonMapper.builder().build();
        private final HttpServer server;
        private final Map<String, Map<String, Object>> sales = new ConcurrentHashMap<>();
        private final Map<String, String> paymentIds = new ConcurrentHashMap<>();
        private final AtomicInteger counter = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        volatile int badSignatures;
        volatile Map<String, Object> lastSale;
        volatile String lastPaymentId;
        volatile boolean forgeSignatures;
        volatile boolean declineNext;
        volatile boolean refuseRefunds;
        volatile String chargedPrice;
        final List<Map<String, Object>> refunds = new CopyOnWriteArrayList<>();
        final List<String> cancelled = new CopyOnWriteArrayList<>();

        FakeIyzico() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            forgeSignatures = false;
            declineNext = false;
            refuseRefunds = false;
            chargedPrice = null;
            refunds.clear();
            cancelled.clear();
            badSignatures = 0;
            requests.set(0);
        }

        Map<String, Object> lastSale() {
            return lastSale;
        }

        String lastPaymentId() {
            return lastPaymentId;
        }

        String tokenFor(String pageUrl) {
            return pageUrl.substring(pageUrl.indexOf("token=") + "token=".length());
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.incrementAndGet();
            if (!signedCorrectly(exchange, path, body)) {
                badSignatures++;
                respond(exchange, Map.of("status", "failure", "errorCode", "1000", "errorMessage", "Geçersiz imza"));
                return;
            }
            Map<String, Object> request = json.readValue(body, Map.class);
            switch (path) {
                case IyzicoClient.INITIALIZE_PATH -> initialize(exchange, request);
                case IyzicoClient.RETRIEVE_PATH -> retrieve(exchange, request);
                case IyzicoClient.REFUND_PATH -> {
                    if (refuseRefunds) {
                        respond(exchange, Map.of("status", "failure", "errorCode", "5115", "errorMessage", "İade yapılamadı"));
                        return;
                    }
                    refunds.add(Map.of("paymentId", request.get("paymentId"), "price", request.get("price")));
                    respond(exchange, Map.of("status", "success", "paymentId", request.get("paymentId")));
                }
                case IyzicoClient.CANCEL_PATH -> {
                    cancelled.add((String) request.get("paymentId"));
                    respond(exchange, Map.of("status", "success", "paymentId", request.get("paymentId")));
                }
                default -> {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                }
            }
        }

        private void initialize(HttpExchange exchange, Map<String, Object> request) throws IOException {
            String token = "tok-" + UUID.randomUUID();
            sales.put(token, request);
            lastSale = request;
            String conversationId = (String) request.get("conversationId");
            respond(exchange, Map.of("status", "success", "conversationId", conversationId, "token", token,
                    "paymentPageUrl", "https://sandbox-cpp.iyzipay.com?token=" + token,
                    "signature", sign(conversationId + ":" + token)));
        }

        private void retrieve(HttpExchange exchange, Map<String, Object> request) throws IOException {
            String token = (String) request.get("token");
            Map<String, Object> sale = sales.get(token);
            if (sale == null) {
                respond(exchange, Map.of("status", "failure", "errorCode", "5097", "errorMessage", "Token bulunamadı"));
                return;
            }
            String conversationId = (String) sale.get("conversationId");
            String paymentId = paymentIds.computeIfAbsent(token, key -> String.valueOf(20_000_000 + counter.incrementAndGet()));
            lastPaymentId = paymentId;
            boolean declined = declineNext;
            String price = chargedPrice != null ? chargedPrice : (String) sale.get("price");
            // iyzico answers prices as numbers and signs them without trailing zeros: 250.00 is signed as "250".
            String signedPrice = new java.math.BigDecimal(price).stripTrailingZeros().toPlainString();
            String status = declined ? "FAILURE" : "SUCCESS";
            String signature = sign(String.join(":", status, paymentId, "TRY", conversationId, conversationId,
                    signedPrice, signedPrice, token));
            Map<String, Object> answer = new HashMap<>();
            answer.put("status", "success");
            answer.put("paymentStatus", status);
            answer.put("paymentId", paymentId);
            answer.put("currency", "TRY");
            answer.put("basketId", conversationId);
            answer.put("conversationId", conversationId);
            answer.put("price", Double.parseDouble(price));
            answer.put("paidPrice", Double.parseDouble(price));
            answer.put("installment", 1);
            answer.put("cardAssociation", "MASTER_CARD");
            answer.put("binNumber", "552879");
            answer.put("lastFourDigits", "0008");
            answer.put("token", token);
            answer.put("signature", forgeSignatures ? "0".repeat(64) : signature);
            respond(exchange, answer);
        }

        /** Checks the IYZWSv2 header independently of the code under test. */
        private boolean signedCorrectly(HttpExchange exchange, String path, String body) {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header == null || !header.startsWith("IYZWSv2 ")) {
                return false;
            }
            String decoded = new String(Base64.getDecoder().decode(header.substring("IYZWSv2 ".length())), StandardCharsets.UTF_8);
            Map<String, String> parts = new HashMap<>();
            for (String part : decoded.split("&")) {
                int colon = part.indexOf(':');
                parts.put(part.substring(0, colon), part.substring(colon + 1));
            }
            String randomKey = parts.get("randomKey");
            return API_KEY.equals(parts.get("apiKey"))
                    && randomKey.equals(exchange.getRequestHeaders().getFirst("x-iyzi-rnd"))
                    && sign(randomKey + path + body).equals(parts.get("signature"));
        }

        private static String sign(String data) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                StringBuilder hex = new StringBuilder();
                for (byte b : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) {
                    hex.append(String.format("%02x", b));
                }
                return hex.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private void respond(HttpExchange exchange, Object answer) throws IOException {
            if (answer instanceof Map<?, ?> map && map.get("paymentStatus") != null && declineNext) {
                declineNext = false;
            }
            byte[] bytes = json.writeValueAsBytes(answer);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
