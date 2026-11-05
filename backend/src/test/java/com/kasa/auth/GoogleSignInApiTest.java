package com.kasa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.kasa.support.PostgresIntegrationTest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * Stands in for Google: a key server publishing one signing key, and ID tokens signed with it. Each test then changes
 * one thing Google would never sign, or one thing an attacker would try.
 */
class GoogleSignInApiTest extends PostgresIntegrationTest {

    private static final String CLIENT_ID = "kasa-test.apps.googleusercontent.com";
    private static final RSAKey GOOGLE_KEY = generateKey("google-key");
    private static final RSAKey OTHER_KEY = generateKey("google-key");
    private static final HttpServer KEY_SERVER = startKeyServer();

    private record Response(int status, Map<String, Object> body) {
    }

    @DynamicPropertySource
    static void google(DynamicPropertyRegistry registry) {
        registry.add("kasa.google.client-id", () -> CLIENT_ID);
        registry.add("kasa.google.jwks-url", () -> "http://localhost:" + KEY_SERVER.getAddress().getPort() + "/certs");
    }

    @Value("${local.server.port}")
    int port;

    RestClient client;
    String email;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
        email = "google-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void theSignInPageLearnsTheClientId() {
        Map<String, Object> providers = client.get().uri("/api/v1/auth/providers").retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() { });
        assertThat(providers).containsEntry("googleClientId", CLIENT_ID);
    }

    @Test
    void aNewPersonGivesABusinessNameAndGetsAVerifiedAccount() {
        Map<String, Object> first = google(idToken(claims -> claims.claim("name", "Ayşe Yılmaz"))).body();
        assertThat(first).containsEntry("registrationRequired", true).containsEntry("email", email)
                .containsEntry("suggestedName", "Ayşe Yılmaz").containsEntry("accessToken", null);
        assertThat(count("SELECT count(*) FROM users WHERE email = :email")).isZero();

        Response created = send("/api/v1/auth/google/register", null,
                Map.of("signupToken", first.get("signupToken"), "businessName", "  Ayşe'nin Dükkanı "));
        assertThat(created.status()).isEqualTo(201);
        String token = (String) created.body().get("accessToken");
        Map<String, Object> me = get("/api/v1/me", token).body();
        assertThat(me).containsEntry("email", email).containsEntry("tenantName", "Ayşe'nin Dükkanı")
                .containsEntry("role", "OWNER").containsEntry("emailVerified", true);
        // Google already proved the address, so no verification mail goes out.
        assertThat(count("SELECT count(*) FROM email_outbox WHERE recipient = :email")).isZero();

        // From now on the same Google account signs straight in.
        Map<String, Object> again = google(idToken(claims -> { })).body();
        assertThat(again).containsEntry("registrationRequired", false);
        assertThat(get("/api/v1/me", (String) again.get("accessToken")).body()).containsEntry("email", email);
    }

    @Test
    void anExistingAccountSignsInAndItsAddressCountsAsVerified() {
        Map<String, Object> registered = send("/api/v1/auth/register", null,
                Map.of("businessName", "Shop", "email", email, "password", "correct-horse-battery")).body();
        assertThat(get("/api/v1/me", (String) registered.get("accessToken")).body()).containsEntry("emailVerified", false);

        Response signIn = google(idToken(claims -> claims.claim("email", email.toUpperCase())));

        assertThat(signIn.status()).isEqualTo(200);
        assertThat(signIn.body()).containsEntry("registrationRequired", false).containsEntry("twoFactorRequired", false);
        Map<String, Object> me = get("/api/v1/me", (String) signIn.body().get("accessToken")).body();
        assertThat(me).containsEntry("tenantId", registered.get("tenantId")).containsEntry("emailVerified", true);
    }

    @Test
    void googleDoesNotSkipTheSecondFactor() {
        send("/api/v1/auth/register", null, Map.of("businessName", "Shop", "email", email, "password", "correct-horse-battery"));
        owner.sql("UPDATE users SET totp_secret = 'stored-secret', totp_enabled_at = now() WHERE email = :email").param("email", email).update();

        Map<String, Object> signIn = google(idToken(claims -> { })).body();

        assertThat(signIn).containsEntry("twoFactorRequired", true).containsEntry("accessToken", null);
        assertThat(get("/api/v1/me", (String) signIn.get("challengeToken")).status()).isEqualTo(401);
    }

    @Test
    void tokensGoogleWouldNotHaveIssuedForUsAreRefused() {
        Map<String, Consumer<JWTClaimsSet.Builder>> forgeries = Map.of(
                "another app's token", claims -> claims.audience("someone-else.apps.googleusercontent.com"),
                "another issuer", claims -> claims.issuer("https://evil.example"),
                "expired", claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(600))),
                "unconfirmed address", claims -> claims.claim("email_verified", false),
                "no address", claims -> claims.claim("email", null));
        forgeries.forEach((name, change) ->
                assertThat(google(idToken(change)).status()).as(name).isEqualTo(401));

        assertThat(google(idToken(OTHER_KEY, claims -> { })).status()).as("signed with a key Google never published").isEqualTo(401);
        assertThat(google("not-a-token").status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM users WHERE email = :email")).isZero();
    }

    @Test
    void theSignupTicketOpensNothingAndCannotBeAltered() {
        String ticket = (String) google(idToken(claims -> { })).body().get("signupToken");

        assertThat(get("/api/v1/me", ticket).status()).as("a ticket is not an access token").isEqualTo(401);

        String[] parts = ticket.split("\\.");
        String otherEmail = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace(email, "victim@example.com").getBytes(StandardCharsets.UTF_8));
        String altered = parts[0] + "." + otherEmail + "." + parts[2];
        assertThat(send("/api/v1/auth/google/register", null, Map.of("signupToken", altered, "businessName", "X")).status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM users WHERE email = 'victim@example.com'")).isZero();
    }

    @Test
    void aTicketForAnAddressTakenMeanwhileIsRefused() {
        String ticket = (String) google(idToken(claims -> { })).body().get("signupToken");
        send("/api/v1/auth/register", null, Map.of("businessName", "Shop", "email", email, "password", "correct-horse-battery"));

        assertThat(send("/api/v1/auth/google/register", null, Map.of("signupToken", ticket, "businessName", "X")).status()).isEqualTo(409);
    }

    private String idToken(Consumer<JWTClaimsSet.Builder> change) {
        return idToken(GOOGLE_KEY, change);
    }

    private String idToken(RSAKey key, Consumer<JWTClaimsSet.Builder> change) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(List.of(CLIENT_ID))
                .subject("1098" + Math.abs(email.hashCode()))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("email", email)
                .claim("email_verified", true);
        change.accept(claims);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private Response google(String credential) {
        return send("/api/v1/auth/google", null, Map.of("credential", credential));
    }

    private long count(String sql) {
        return owner.sql(sql).param("email", email).query(Long.class).single();
    }

    private Response get(String path, String token) {
        return client.get().uri(path)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                })
                .exchange((request, response) -> new Response(response.getStatusCode().value(), body(response.getBody().readAllBytes())));
    }

    private Response send(String path, String token, Map<String, ?> body) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                })
                .body(body)
                .exchange((request, response) -> new Response(response.getStatusCode().value(), body(response.getBody().readAllBytes())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(byte[] bytes) {
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            return new HashMap<>(new tools.jackson.databind.ObjectMapper().readValue(bytes, Map.class));
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startKeyServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] keys = new JWKSet(GOOGLE_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, keys.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(keys);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
