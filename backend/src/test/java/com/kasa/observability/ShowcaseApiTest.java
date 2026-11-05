package com.kasa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class ShowcaseApiTest extends PostgresIntegrationTest {

    private static final String ADMIN_TOKEN = "test-admin-token";

    @Value("${local.server.port}")
    int port;

    @Autowired
    BusinessMetrics metrics;

    TestMerchant merchant;

    @BeforeEach
    void registerMerchant() {
        merchant = TestMerchant.register(port);
    }

    @Test
    void repeatedWrongPasswordsAreRateLimitedPerEmail() {
        String email = "target-" + UUID.randomUUID() + "@example.com";
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(login(email).getStatusCode().value()).isEqualTo(401);
        }

        ResponseEntity<Map> blocked = login(email);

        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(login("other-" + UUID.randomUUID() + "@example.com").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void metricsAreOpenOnlyToTheOperator() {
        assertThat(metricsStatus(client().get())).isEqualTo(401);
        assertThat(metricsStatus(client().get().header("Authorization", "Bearer " + merchant.token()))).isEqualTo(403);
        assertThat(metricsStatus(client().get().header("X-Api-Key", merchant.apiKey()))).isEqualTo(403);
        assertThat(metricsStatus(client().get().header("X-Admin-Token", ADMIN_TOKEN))).isEqualTo(200);
    }

    @Test
    void businessActivityAppearsInTheMetrics() {
        merchant.capturedPayment(1_000);
        metrics.refreshBacklog();

        String scraped = client().get().uri("/actuator/prometheus").header("X-Admin-Token", ADMIN_TOKEN)
                .retrieve().body(String.class);

        assertThat(scraped)
                .contains("kasa_payment_operations_total")
                .contains("type=\"CAPTURE\"")
                .contains("kasa_backlog_payment_operations")
                .contains("http_server_requests_seconds_bucket");
        assertThat(scraped).doesNotContain(merchant.tenantId().toString());
    }

    @Test
    void otherActuatorEndpointsStayClosed() {
        assertThat(client().get().uri("/actuator/env").header("X-Admin-Token", ADMIN_TOKEN)
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value()).isEqualTo(404);
        assertThat(client().get().uri("/actuator/env")
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void unknownApiPathsStillRequireCredentialsWhileWebPathsAreOpen() {
        assertThat(client().get().uri("/api/v1/does-not-exist")
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value()).isEqualTo(401);
        assertThat(client().get().uri("/does-not-exist")
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void responsesCarrySecurityHeaders() {
        ResponseEntity<Void> response = client().get().uri("/actuator/health").retrieve().toBodilessEntity();

        String policy = response.getHeaders().getFirst("Content-Security-Policy");
        assertThat(policy).contains("default-src 'self'", "frame-ancestors 'none'", "object-src 'none'");
        // Google's sign-in script is the only outside code allowed to run, and nothing may run inline.
        assertThat(policy).contains("script-src 'self' https://accounts.google.com/gsi/client;");
        assertThat(policy.split("script-src")[1].split(";")[0]).doesNotContain("unsafe");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        // Google sign-in needs the origin; a path (which may hold a reset token) must still never leave the site.
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    }

    private ResponseEntity<Map> login(String email) {
        return client().post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "not-the-right-password"))
                .retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
    }

    private int metricsStatus(RestClient.RequestHeadersUriSpec<?> request) {
        return metricsStatus((RestClient.RequestHeadersSpec<?>) request);
    }

    private int metricsStatus(RestClient.RequestHeadersSpec<?> request) {
        return ((RestClient.RequestHeadersUriSpec<?>) request).uri("/actuator/prometheus")
                .retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value();
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }
}
