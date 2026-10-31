package com.kasa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.kasa.support.PostgresIntegrationTest;

/** Exercises the API over real HTTP, through the whole security filter chain. */
class AuthApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Value("${local.server.port}")
    int port;

    @Test
    void registeringReturnsATokenThatOpensTheTenant() {
        String token = register("Fırın A.Ş.", uniqueEmail(), PASSWORD);

        ResponseEntity<Map> tenant = get("/api/v1/tenant", bearer(token));

        assertThat(tenant.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tenant.getBody()).containsEntry("name", "Fırın A.Ş.");
    }

    @Test
    void loginSucceedsWithEmailInAnyCase() {
        String email = uniqueEmail();
        register("Shop", email, PASSWORD);

        ResponseEntity<Map> login = login(email.toUpperCase(java.util.Locale.ROOT), PASSWORD);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).containsKey("accessToken");
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() {
        String email = uniqueEmail();
        register("Shop", email, PASSWORD);

        ResponseEntity<Map> wrongPassword = login(email, "not-the-right-password");
        ResponseEntity<Map> unknownEmail = login(uniqueEmail(), PASSWORD);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().get("detail")).isEqualTo(unknownEmail.getBody().get("detail"));
    }

    @Test
    void anEmailCanRegisterOnlyOnceAndLeavesNoOrphanTenant() {
        String email = uniqueEmail();
        register("First", email, PASSWORD);
        int tenantsBefore = owner.sql("SELECT count(*) FROM tenants").query(Integer.class).single();

        ResponseEntity<Map> second = post("/api/v1/auth/register",
                Map.of("businessName", "Second", "email", email.toUpperCase(java.util.Locale.ROOT), "password", PASSWORD),
                headers -> { });

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(owner.sql("SELECT count(*) FROM tenants").query(Integer.class).single()).isEqualTo(tenantsBefore);
    }

    @Test
    void weakOrOverlongPasswordsAreRejected() {
        ResponseEntity<Map> tooShort = post("/api/v1/auth/register",
                Map.of("businessName", "Shop", "email", uniqueEmail(), "password", "short"), headers -> { });
        ResponseEntity<Map> tooManyBytes = post("/api/v1/auth/register",
                Map.of("businessName", "Shop", "email", uniqueEmail(), "password", "ş".repeat(40)), headers -> { });

        assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooManyBytes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void passwordsAreStoredOnlyAsBcryptHashes() {
        String email = uniqueEmail();
        register("Shop", email, PASSWORD);

        String stored = owner.sql("SELECT password_hash FROM users WHERE email = :e")
                .param("e", email)
                .query(String.class)
                .single();

        assertThat(stored).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void protectedEndpointsRejectMissingOrTamperedTokens() {
        String token = register("Shop", uniqueEmail(), PASSWORD);

        assertThat(get("/api/v1/tenant", headers -> { }).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/tenant", bearer(token + "x")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthIsPublic() {
        assertThat(get("/actuator/health", headers -> { }).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anApiKeyAuthenticatesAsItsTenantAndItsSecretIsShownOnce() {
        String token = register("Key Shop", uniqueEmail(), PASSWORD);

        ResponseEntity<Map> created = post("/api/v1/api-keys", Map.of("name", "server"), bearer(token));
        String key = (String) created.getBody().get("key");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(key).startsWith("kasa_test_");
        assertThat(get("/api/v1/tenant", apiKey(key)).getBody()).containsEntry("name", "Key Shop");

        ResponseEntity<List> listed = client().get().uri("/api/v1/api-keys").headers(bearer(token))
                .retrieve().onStatus(status -> true, (request, response) -> { }).toEntity(List.class);
        assertThat(listed.getBody()).hasSize(1);
        assertThat((Map<String, Object>) listed.getBody().get(0)).doesNotContainKeys("key", "keyHash");

        String storedHash = owner.sql("SELECT key_hash FROM api_keys WHERE id = :id")
                .param("id", UUID.fromString((String) created.getBody().get("id")))
                .query(String.class)
                .single();
        assertThat(storedHash).isNotEqualTo(key).doesNotContain(key);
    }

    @Test
    void invalidApiKeysAreRejected() {
        String token = register("Shop", uniqueEmail(), PASSWORD);
        String key = createKey(token);
        String forged = key.substring(0, key.length() - 1) + (key.endsWith("0") ? "1" : "0");

        assertThat(get("/api/v1/tenant", apiKey("nonsense")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/tenant", apiKey(forged)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anApiKeyCannotManageApiKeys() {
        String key = createKey(register("Shop", uniqueEmail(), PASSWORD));

        assertThat(post("/api/v1/api-keys", Map.of("name", "another"), apiKey(key)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aRevokedApiKeyStopsWorking() {
        String token = register("Shop", uniqueEmail(), PASSWORD);
        ResponseEntity<Map> created = post("/api/v1/api-keys", Map.of("name", "server"), bearer(token));
        String key = (String) created.getBody().get("key");

        ResponseEntity<Map> revoked = delete("/api/v1/api-keys/" + created.getBody().get("id"), bearer(token));

        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/v1/tenant", apiKey(key)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTenantCannotSeeOrRevokeAnotherTenantsApiKey() {
        String tokenA = register("A", uniqueEmail(), PASSWORD);
        String tokenB = register("B", uniqueEmail(), PASSWORD);
        ResponseEntity<Map> keyOfA = post("/api/v1/api-keys", Map.of("name", "a-server"), bearer(tokenA));

        ResponseEntity<List> listedByB = client().get().uri("/api/v1/api-keys").headers(bearer(tokenB))
                .retrieve().onStatus(status -> true, (request, response) -> { }).toEntity(List.class);
        ResponseEntity<Map> revokedByB = delete("/api/v1/api-keys/" + keyOfA.getBody().get("id"), bearer(tokenB));

        assertThat(listedByB.getBody()).isEmpty();
        assertThat(revokedByB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/tenant", apiKey((String) keyOfA.getBody().get("key"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private String register(String businessName, String email, String password) {
        ResponseEntity<Map> response = post("/api/v1/auth/register",
                Map.of("businessName", businessName, "email", email, "password", password), headers -> { });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("accessToken");
    }

    private ResponseEntity<Map> login(String email, String password) {
        return post("/api/v1/auth/login", Map.of("email", email, "password", password), headers -> { });
    }

    private String createKey(String token) {
        return (String) post("/api/v1/api-keys", Map.of("name", "server"), bearer(token)).getBody().get("key");
    }

    private ResponseEntity<Map> get(String path, Consumer<HttpHeaders> headers) {
        return send(HttpMethod.GET, path, null, headers);
    }

    private ResponseEntity<Map> post(String path, Object body, Consumer<HttpHeaders> headers) {
        return send(HttpMethod.POST, path, body, headers);
    }

    private ResponseEntity<Map> delete(String path, Consumer<HttpHeaders> headers) {
        return send(HttpMethod.DELETE, path, null, headers);
    }

    private ResponseEntity<Map> send(HttpMethod method, String path, Object body, Consumer<HttpHeaders> headers) {
        RestClient.RequestBodySpec request = client().method(method).uri(path).headers(headers);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().onStatus(status -> true, (req, res) -> { }).toEntity(Map.class);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static Consumer<HttpHeaders> bearer(String token) {
        return headers -> headers.setBearerAuth(token);
    }

    private static Consumer<HttpHeaders> apiKey(String key) {
        return headers -> headers.set("X-Api-Key", key);
    }

    // Contains an "I" on purpose: the test JVM may run under a Turkish locale.
    private static String uniqueEmail() {
        return "merchant-i-" + UUID.randomUUID() + "@example.com";
    }
}
