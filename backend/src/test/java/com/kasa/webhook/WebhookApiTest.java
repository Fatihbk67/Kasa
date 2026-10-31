package com.kasa.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.kasa.payment.PaymentService;
import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.tenant.TenantScope;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** Delivers to a real HTTP server that plays the merchant's receiver, and can be told to misbehave. */
class WebhookApiTest extends PostgresIntegrationTest {

    private record Received(String eventId, String eventType, String signature, String body) {
    }

    private record Merchant(String token, String apiKey, UUID tenantId) {
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    WebhookDispatcher dispatcher;

    @Autowired
    PaymentService payments;

    @Autowired
    WebhookEvents events;

    @Autowired
    TenantScope tenantScope;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JsonMapper jsonMapper;

    HttpServer receiver;
    List<Received> received;
    volatile Function<Received, Integer> respondWith;
    Merchant merchant;

    @BeforeEach
    void startReceiver() throws IOException {
        received = new CopyOnWriteArrayList<>();
        respondWith = request -> 200;
        receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        receiver.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Received request = new Received(
                    exchange.getRequestHeaders().getFirst("X-Kasa-Event-Id"),
                    exchange.getRequestHeaders().getFirst("X-Kasa-Event-Type"),
                    exchange.getRequestHeaders().getFirst("X-Kasa-Signature"),
                    body);
            received.add(request);
            exchange.sendResponseHeaders(respondWith.apply(request), -1);
            exchange.close();
        });
        receiver.start();
        merchant = newMerchant();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    @Test
    void aCaptureDeliversSignedEventsToTheMerchant() throws Exception {
        Map<String, Object> endpoint = createEndpoint(merchant);
        String paymentId = authorize(merchant, 10_000, "tok_success");
        capture(merchant, paymentId);

        dispatcher.dispatchDue();

        assertThat(received).extracting(Received::eventType)
                .containsExactlyInAnyOrder("payment.authorized", "payment.captured");
        Received capture = received.stream().filter(r -> r.eventType().equals("payment.captured")).findFirst().orElseThrow();
        assertSignatureValid(capture, (String) endpoint.get("secret"));

        Map<String, Object> envelope = jsonMapper.readValue(capture.body(), Map.class);
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        Map<String, Object> payment = (Map<String, Object>) data.get("payment");
        assertThat(envelope.get("id")).isEqualTo(capture.eventId());
        assertThat(payment.get("id")).isEqualTo(paymentId);
        assertThat(payment.get("status")).isEqualTo("CAPTURED");
        assertThat(capture.body()).doesNotContain("bankReference", "whsec_");
    }

    @Test
    void aSignatureFailsVerificationAgainstAnotherSecretOrAChangedBody() throws Exception {
        Map<String, Object> endpoint = createEndpoint(merchant);
        authorize(merchant, 1_000, "tok_success");
        dispatcher.dispatchDue();
        Received request = received.getFirst();

        assertThatThrownBy(() -> assertSignatureValid(request, "whsec_wrong")).isInstanceOf(AssertionError.class);
        Received tampered = new Received(request.eventId(), request.eventType(), request.signature(), request.body() + " ");
        assertThatThrownBy(() -> assertSignatureValid(tampered, (String) endpoint.get("secret")))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void theSecretIsOnlyShownAtCreation() {
        createEndpoint(merchant);

        List<Map<String, Object>> listed = client().get().uri("/api/v1/webhook-endpoints")
                .header("Authorization", "Bearer " + merchant.token())
                .retrieve().body(List.class);

        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst()).doesNotContainKey("secret");
    }

    @Test
    void anApiKeyCannotManageWebhooks() {
        ResponseEntity<Map> response = client().post().uri("/api/v1/webhook-endpoints")
                .header("X-Api-Key", merchant.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", receiverUrl()))
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void aPendingBankOperationSendsNothingUntilItIsResolved() {
        createEndpoint(merchant);

        create(merchant, "tok_timeout_processed", 1_000);
        dispatcher.dispatchDue();
        assertThat(received).isEmpty();

        payments.recoverStale(Duration.ZERO);
        dispatcher.dispatchDue();
        assertThat(received).extracting(Received::eventType).containsExactly("payment.authorized");
    }

    @Test
    void declinedAndFailedPaymentsAreReported() {
        createEndpoint(merchant);

        create(merchant, "tok_declined", 1_000);
        create(merchant, "tok_timeout_lost", 1_000);
        payments.recoverStale(Duration.ZERO);
        dispatcher.dispatchDue();

        assertThat(received).extracting(Received::eventType)
                .containsExactlyInAnyOrder("payment.declined", "payment.failed");
    }

    @Test
    void anEventIsDiscardedIfItsTransactionRollsBack() {
        createEndpoint(merchant);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            tenantScope.apply(merchant.tenantId());
            events.publish(merchant.tenantId(), "payment.captured", Map.of());
            throw new IllegalStateException("simulated failure after publishing");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(deliveries("PENDING")).isZero();
        assertThat(owner.sql("SELECT count(*) FROM webhook_events WHERE tenant_id = :t")
                .param("t", merchant.tenantId()).query(Integer.class).single()).isZero();
    }

    @Test
    void aFailedDeliveryIsRetriedWithBackoffAndTheSameEventId() {
        createEndpoint(merchant);
        authorize(merchant, 1_000, "tok_success");
        respondWith = request -> 500;

        dispatcher.dispatchDue();

        assertThat(dispatcher.dispatchDue()).as("not due until backoff passes").isZero();
        assertThat(deliveryColumn("last_status_code")).isEqualTo("500");
        assertThat(owner.sql("SELECT next_attempt_at > now() FROM webhook_deliveries WHERE tenant_id = :t")
                .param("t", merchant.tenantId()).query(Boolean.class).single()).isTrue();

        respondWith = request -> 204;
        makeDue();
        dispatcher.dispatchDue();

        assertThat(received).hasSize(2);
        assertThat(received.get(1).eventId()).isEqualTo(received.get(0).eventId());
        assertThat(received.get(1).body()).isEqualTo(received.get(0).body());
        assertThat(deliveries("DELIVERED")).isEqualTo(1);
    }

    @Test
    void redirectsAreNotFollowedAndCountAsFailure() {
        createEndpoint(merchant);
        authorize(merchant, 1_000, "tok_success");
        respondWith = request -> 302;

        dispatcher.dispatchDue();

        assertThat(received).hasSize(1);
        assertThat(deliveries("PENDING")).isEqualTo(1);
    }

    @Test
    void anUnreachableReceiverIsRetried() {
        createEndpoint(merchant);
        authorize(merchant, 1_000, "tok_success");
        receiver.stop(0);

        dispatcher.dispatchDue();

        assertThat(deliveryColumn("last_error")).isEqualTo("connection_failed");
        assertThat(deliveries("PENDING")).isEqualTo(1);
    }

    @Test
    void aSlowReceiverTimesOutWithoutHoldingUpOtherDeliveries() {
        Merchant slowMerchant = newMerchant();
        createEndpoint(slowMerchant, receiverUrl() + "?slow");
        createEndpoint(merchant);
        respondWith = request -> {
            if (request.eventType() != null && slowBody(request)) {
                sleep(Duration.ofSeconds(3));
            }
            return 200;
        };
        create(slowMerchant, "tok_declined", 777);
        create(merchant, "tok_declined", 1_000);

        dispatcher.dispatchDue();

        assertThat(deliveries("DELIVERED")).isEqualTo(1);
        assertThat(owner.sql("SELECT last_error FROM webhook_deliveries WHERE tenant_id = :t")
                .param("t", slowMerchant.tenantId()).query(String.class).single()).isEqualTo("timeout");
    }

    @Test
    void afterTheLastAttemptADeliveryFailsAndCanBeRetriedByHand() {
        Map<String, Object> endpoint = createEndpoint(merchant);
        authorize(merchant, 1_000, "tok_success");
        respondWith = request -> 500;

        for (int attempt = 0; attempt < 3; attempt++) {
            dispatcher.dispatchDue();
            if (attempt < 2) {
                makeDue();
            }
        }

        assertThat(deliveries("FAILED")).isEqualTo(1);
        makeDueIncludingFailed();
        dispatcher.dispatchDue();
        assertThat(received).as("a failed delivery is not sent again automatically").hasSize(3);

        respondWith = request -> 200;
        List<Map<String, Object>> history = deliveryHistory(merchant, endpoint);
        ResponseEntity<Void> retried = client().post()
                .uri("/api/v1/webhook-endpoints/deliveries/" + history.getFirst().get("id") + "/retry")
                .header("Authorization", "Bearer " + merchant.token())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity();
        dispatcher.dispatchDue();

        assertThat(retried.getStatusCode().value()).isEqualTo(202);
        assertThat(deliveries("DELIVERED")).isEqualTo(1);
    }

    @Test
    void aDispatcherThatCrashesAfterClaimingIsCoveredByTheLease() {
        createEndpoint(merchant);
        authorize(merchant, 1_000, "tok_success");

        // Claims as a crashed dispatcher would: the row is leased, but no send or result ever follows.
        owner.sql("SELECT * FROM claim_due_webhook_deliveries(1000, 3600)").query((rs, n) -> 1).list();
        assertThat(dispatcher.dispatchDue()).as("still under lease").isZero();

        makeDue();
        dispatcher.dispatchDue();

        assertThat(received).hasSize(1);
        assertThat(deliveries("DELIVERED")).isEqualTo(1);
        assertThat(deliveryColumn("attempts")).isEqualTo("2");
    }

    @Test
    void concurrentDispatchersSendEachDeliveryOnce() throws Exception {
        createEndpoint(merchant);
        for (int i = 0; i < 20; i++) {
            create(merchant, "tok_declined", 1_000 + i);
        }

        Concurrency.run(10, i -> () -> dispatcher.dispatchDue());

        assertThat(received).hasSize(20);
        assertThat(received.stream().map(Received::eventId).distinct()).hasSize(20);
        assertThat(deliveries("DELIVERED")).isEqualTo(20);
    }

    @Test
    void everyActiveEndpointGetsItsOwnDelivery() {
        createEndpoint(merchant);
        createEndpoint(merchant);

        create(merchant, "tok_declined", 1_000);
        dispatcher.dispatchDue();

        assertThat(received).hasSize(2);
        assertThat(received.get(0).eventId()).isEqualTo(received.get(1).eventId());
    }

    @Test
    void anotherMerchantsEndpointReceivesNothingAndCannotBeTouched() {
        Merchant other = newMerchant();
        Map<String, Object> endpointOfOther = createEndpoint(other);

        create(merchant, "tok_declined", 1_000);
        dispatcher.dispatchDue();
        assertThat(received).isEmpty();

        ResponseEntity<Void> disabled = client().delete()
                .uri("/api/v1/webhook-endpoints/" + endpointOfOther.get("id"))
                .header("Authorization", "Bearer " + merchant.token())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity();
        assertThat(disabled.getStatusCode().value()).isEqualTo(404);
        assertThat(deliveryHistory(merchant, endpointOfOther)).isEmpty();
    }

    @Test
    void disablingAnEndpointCancelsPendingDeliveriesAndStopsNewOnes() {
        Map<String, Object> endpoint = createEndpoint(merchant);
        create(merchant, "tok_declined", 1_000);

        ResponseEntity<Void> disabled = client().delete()
                .uri("/api/v1/webhook-endpoints/" + endpoint.get("id"))
                .header("Authorization", "Bearer " + merchant.token())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity();
        create(merchant, "tok_declined", 2_000);
        dispatcher.dispatchDue();

        assertThat(disabled.getStatusCode().value()).isEqualTo(204);
        assertThat(received).isEmpty();
        assertThat(deliveries("CANCELLED")).isEqualTo(1);
        assertThat(deliveries("PENDING")).isZero();
    }

    @Test
    void aFailedDeliveryCannotBeRetriedAfterItsEndpointIsDisabled() {
        Map<String, Object> endpoint = createEndpoint(merchant);
        authorize(merchant, 1_000, "tok_success");
        respondWith = request -> 500;
        for (int attempt = 0; attempt < 3; attempt++) {
            dispatcher.dispatchDue();
            makeDue();
        }
        Object deliveryId = deliveryHistory(merchant, endpoint).getFirst().get("id");
        client().delete().uri("/api/v1/webhook-endpoints/" + endpoint.get("id"))
                .header("Authorization", "Bearer " + merchant.token())
                .retrieve().toBodilessEntity();

        ResponseEntity<Void> retried = client().post()
                .uri("/api/v1/webhook-endpoints/deliveries/" + deliveryId + "/retry")
                .header("Authorization", "Bearer " + merchant.token())
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity();

        assertThat(retried.getStatusCode().value()).isEqualTo(404);
        assertThat(deliveries("FAILED")).isEqualTo(1);
    }

    @Test
    void publishingAndDisablingConcurrentlyLeavesNoDeliveryQueuedForADisabledEndpoint() throws Exception {
        for (int round = 0; round < 10; round++) {
            Map<String, Object> endpoint = createEndpoint(merchant);
            Concurrency.run(2, i -> () -> {
                if (i == 0) {
                    return create(merchant, "tok_declined", 1_000);
                }
                client().delete().uri("/api/v1/webhook-endpoints/" + endpoint.get("id"))
                        .header("Authorization", "Bearer " + merchant.token())
                        .retrieve().toBodilessEntity();
                return Map.of();
            });
        }

        assertThat(deliveries("PENDING")).isZero();
    }

    @Test
    void backoffGrowsAndStaysWithinTheCap() {
        assertThat(dispatcher.backoffAfter(1)).isBetween(Duration.ofSeconds(24), Duration.ofSeconds(36));
        assertThat(dispatcher.backoffAfter(3)).isBetween(Duration.ofSeconds(96), Duration.ofSeconds(144));
        assertThat(dispatcher.backoffAfter(40)).isBetween(Duration.ofMinutes(48), Duration.ofMinutes(72));
    }

    private static boolean slowBody(Received request) {
        return request.body().contains("\"amount\":777");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Verifies the signature the way a merchant would, independently of the production signer. */
    private static void assertSignatureValid(Received request, String secret) throws Exception {
        String[] parts = request.signature().split(",");
        String timestamp = parts[0].substring("t=".length());
        String signature = parts[1].substring("v1=".length());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((timestamp + "." + request.body()).getBytes(StandardCharsets.UTF_8));
        assertThat(signature).isEqualTo(HexFormat.of().formatHex(expected));
    }

    private void makeDue() {
        owner.sql("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE tenant_id = :t AND status = 'PENDING'")
                .param("t", merchant.tenantId()).update();
    }

    private void makeDueIncludingFailed() {
        owner.sql("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE tenant_id = :t")
                .param("t", merchant.tenantId()).update();
    }

    private int deliveries(String status) {
        return owner.sql("SELECT count(*) FROM webhook_deliveries WHERE tenant_id = :t AND status = :s")
                .param("t", merchant.tenantId())
                .param("s", status)
                .query(Integer.class)
                .single();
    }

    private String deliveryColumn(String column) {
        return owner.sql("SELECT " + column + "::text FROM webhook_deliveries WHERE tenant_id = :t")
                .param("t", merchant.tenantId())
                .query(String.class)
                .single();
    }

    private List<Map<String, Object>> deliveryHistory(Merchant who, Map<String, Object> endpoint) {
        return client().get().uri("/api/v1/webhook-endpoints/" + endpoint.get("id") + "/deliveries")
                .header("Authorization", "Bearer " + who.token())
                .retrieve().body(List.class);
    }

    private String receiverUrl() {
        return "http://localhost:" + receiver.getAddress().getPort() + "/hook";
    }

    private Map<String, Object> createEndpoint(Merchant who) {
        return createEndpoint(who, receiverUrl());
    }

    private Map<String, Object> createEndpoint(Merchant who, String url) {
        return client().post().uri("/api/v1/webhook-endpoints")
                .header("Authorization", "Bearer " + who.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("url", url))
                .retrieve().body(Map.class);
    }

    private String authorize(Merchant who, long amount, String token) {
        Map<String, Object> result = create(who, token, amount);
        return (String) ((Map<String, Object>) result.get("payment")).get("id");
    }

    private Map<String, Object> create(Merchant who, String token, long amount) {
        return client().post().uri("/api/v1/payments")
                .header("X-Api-Key", who.apiKey())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amount, "currency", "TRY", "cardToken", token))
                .retrieve().body(Map.class);
    }

    private void capture(Merchant who, String paymentId) {
        client().post().uri("/api/v1/payments/" + paymentId + "/capture")
                .header("X-Api-Key", who.apiKey())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .retrieve().toBodilessEntity();
    }

    private Merchant newMerchant() {
        Map<String, Object> registered = client().post().uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("businessName", "Shop", "email", "merchant-" + UUID.randomUUID() + "@example.com",
                        "password", "correct-horse-battery"))
                .retrieve().body(Map.class);
        String token = (String) registered.get("accessToken");
        Map<String, Object> key = client().post().uri("/api/v1/api-keys")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "server"))
                .retrieve().body(Map.class);
        return new Merchant(token, (String) key.get("key"), UUID.fromString((String) registered.get("tenantId")));
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }
}
