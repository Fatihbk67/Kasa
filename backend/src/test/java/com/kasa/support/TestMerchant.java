package com.kasa.support;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** A registered merchant, driving the API over real HTTP as its server would. */
public record TestMerchant(RestClient client, String token, String apiKey, UUID tenantId) {

    public static TestMerchant register(int port) {
        RestClient client = RestClient.create("http://localhost:" + port);
        Map<String, Object> registered = client.post().uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("businessName", "Shop", "email", "merchant-" + UUID.randomUUID() + "@example.com",
                        "password", "correct-horse-battery"))
                .retrieve().body(Map.class);
        String token = (String) registered.get("accessToken");
        Map<String, Object> key = client.post().uri("/api/v1/api-keys")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "server"))
                .retrieve().body(Map.class);
        return new TestMerchant(client, token, (String) key.get("key"), UUID.fromString((String) registered.get("tenantId")));
    }

    /** Authorizes and captures a payment, returning the payment id. */
    public UUID capturedPayment(long amount) {
        UUID id = authorized(amount, "tok_success");
        post("/api/v1/payments/" + id + "/capture", null);
        return id;
    }

    public UUID authorized(long amount, String cardToken) {
        Map<String, Object> result = post("/api/v1/payments",
                Map.of("amount", amount, "currency", "TRY", "cardToken", cardToken));
        return UUID.fromString((String) ((Map<String, Object>) result.get("payment")).get("id"));
    }

    public void refund(UUID paymentId, long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        post("/api/v1/payments/" + paymentId + "/refunds", body);
    }

    public Map<String, Object> post(String path, Object body) {
        RestClient.RequestBodySpec request = client.post().uri(path)
                .header("X-Api-Key", apiKey)
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().body(Map.class);
    }
}
