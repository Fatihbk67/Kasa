package com.kasa.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.kasa.support.PostgresIntegrationTest;

class DemoAccountApiTest extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() { };

    @DynamicPropertySource
    static void demo(DynamicPropertyRegistry registry) {
        registry.add("kasa.demo.enabled", () -> true);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    DemoAccountService demo;

    RestClient client;
    String token;

    @BeforeEach
    void signIn() {
        client = RestClient.create("http://localhost:" + port);
        token = (String) client.post().uri("/api/v1/auth/demo").retrieve().body(JSON).get("accessToken");
    }

    @Test
    void theSignInPageOffersTheDemoAndItOpensAFilledBusinessAsAViewer() {
        assertThat(client.get().uri("/api/v1/auth/providers").retrieve().body(JSON)).containsEntry("demo", true);

        Map<String, Object> me = get("/api/v1/me");
        assertThat(me).containsEntry("role", "VIEWER").containsEntry("tenantName", "Demo Kahve");

        List<?> payments = (List<?>) get("/api/v1/payments?limit=100").get("items");
        assertThat(payments).hasSizeGreaterThan(50);
        Map<String, Object> totals = (Map<String, Object>) get("/api/v1/analytics/summary?days=30").get("totals");
        assertThat(((Number) totals.get("captured")).longValue()).isPositive();
        List<Map<String, Object>> daily = (List<Map<String, Object>>) get("/api/v1/analytics/summary?days=30").get("daily");
        assertThat(daily.stream().filter(day -> ((Number) day.get("captured")).longValue() > 0)).hasSizeGreaterThan(20);
        List<?> payouts = client.get().uri("/api/v1/payouts").header("Authorization", "Bearer " + token).retrieve().body(List.class);
        assertThat(payouts).isNotEmpty();
    }

    @Test
    void everyVisitorSharesTheAccountSoNothingAboutItCanBeChanged() {
        assertThat(post("/api/v1/payments", Map.of("amount", 1_000, "currency", "TRY", "cardToken", "tok_success"))).isEqualTo(403);
        assertThat(post("/api/v1/settings/password", Map.of("currentPassword", "x", "newPassword", "yeni-parola-12345"))).isEqualTo(403);
        assertThat(post("/api/v1/settings/two-factor/setup", Map.of("password", "x"))).isEqualTo(403);
        assertThat(post("/api/v1/settings/role-request", Map.of("role", "ADMIN", "reason", "deneme"))).isEqualTo(403);
        assertThat(post("/api/v1/settings/email-verification", Map.of())).isEqualTo(403);
        // Reading a notification changes nothing anyone else would notice.
        assertThat(post("/api/v1/notifications/read-all", Map.of())).isLessThan(300);
    }

    @Test
    void theDemoSendsNoEmailAndIsCreatedOnlyOnce() {
        demo.prepare();

        assertThat(owner.sql("SELECT count(*) FROM email_outbox WHERE recipient LIKE '%.invalid'").query(Long.class).single()).isZero();
        assertThat(owner.sql("SELECT count(*) FROM users WHERE email IN (:emails)")
                .param("emails", List.of(DemoAccountService.OWNER_EMAIL, DemoAccountService.VISITOR_EMAIL))
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void nobodyCanSignInToTheDemoWithAPassword() {
        int status = client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", DemoAccountService.VISITOR_EMAIL, "password", "demo-parola-12345"))
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value();
        assertThat(status).isEqualTo(401);
    }

    private Map<String, Object> get(String uri) {
        return client.get().uri(uri).header("Authorization", "Bearer " + token).retrieve().body(JSON);
    }

    private int post(String uri, Map<String, Object> body) {
        return client.post().uri(uri).header("Authorization", "Bearer " + token).header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value();
    }
}
