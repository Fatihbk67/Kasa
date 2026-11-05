package com.kasa.iyzico;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.kasa.bank.BankTimeoutException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * iyzico's REST API, called directly over HTTPS so every step is visible. Requests are signed with the IYZWSv2
 * scheme; the payment result is accepted only when iyzico's own signature over it checks out.
 */
@Component
public class IyzicoClient {

    private static final Logger log = LoggerFactory.getLogger(IyzicoClient.class);

    static final String INITIALIZE_PATH = "/payment/iyzipos/checkoutform/initialize/auth/ecom";
    static final String RETRIEVE_PATH = "/payment/iyzipos/checkoutform/auth/ecom/detail";
    static final String REFUND_PATH = "/v2/payment/refund";
    static final String CANCEL_PATH = "/payment/cancel";

    /** What the hosted page needs to know about the sale. iyzico requires a buyer and a basket on every payment. */
    public record Sale(String conversationId, BigDecimal price, String currency, String callbackUrl,
                       List<Integer> installments, String itemName, String buyerEmail, String buyerIp) {
    }

    public record Started(String token, String pageUrl) {
    }

    /** The outcome iyzico reports for a token, after its signature has been verified. */
    public record Result(boolean success, String paymentId, String basketId, String conversationId, BigDecimal price,
                         String currency, int installment, String cardAssociation, String lastFourDigits,
                         String binNumber, String errorCode, String errorMessage) {
    }

    /** iyzico answered and said no. Distinct from a timeout, after which the outcome is unknown. */
    public static class Refused extends RuntimeException {
        private final String code;

        Refused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IyzicoProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public IyzicoClient(IyzicoProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public Started initialize(Sale sale) {
        Map<String, Object> address = Map.of(
                "contactName", "Kasa Test Müşterisi",
                "city", "Istanbul",
                "country", "Turkey",
                "address", "Test adresi, Kadıköy");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locale", "tr");
        body.put("conversationId", sale.conversationId());
        body.put("price", price(sale.price()));
        body.put("paidPrice", price(sale.price()));
        body.put("currency", sale.currency());
        body.put("basketId", sale.conversationId());
        body.put("paymentGroup", "PRODUCT");
        body.put("callbackUrl", sale.callbackUrl());
        body.put("enabledInstallments", sale.installments());
        // The sandbox needs a buyer but has no way to check one, so a fixed test identity stands in. A live
        // integration would collect these from the customer, which this demo deliberately never does.
        body.put("buyer", Map.ofEntries(
                Map.entry("id", sale.conversationId()),
                Map.entry("name", "Kasa"),
                Map.entry("surname", "Test Müşterisi"),
                Map.entry("gsmNumber", "+905350000000"),
                Map.entry("email", sale.buyerEmail()),
                Map.entry("identityNumber", "74300864791"),
                Map.entry("registrationAddress", "Test adresi, Kadıköy"),
                Map.entry("ip", sale.buyerIp()),
                Map.entry("city", "Istanbul"),
                Map.entry("country", "Turkey")));
        body.put("billingAddress", address);
        body.put("shippingAddress", address);
        body.put("basketItems", List.of(Map.of(
                "id", sale.conversationId(),
                "name", sale.itemName(),
                "category1", "Genel",
                "itemType", "VIRTUAL",
                "price", price(sale.price()))));
        JsonNode response = post(INITIALIZE_PATH, body);
        requireSuccess(response);
        String token = text(response, "token");
        if (!signatureMatches(response, List.of(text(response, "conversationId"), token))) {
            throw new IllegalStateException("iyzico's signature on the new payment page does not match");
        }
        return new Started(token, text(response, "paymentPageUrl"));
    }

    public Result retrieve(String token, String conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locale", "tr");
        body.put("conversationId", conversationId);
        body.put("token", token);
        JsonNode response = post(RETRIEVE_PATH, body);
        if (!"success".equals(text(response, "status"))) {
            return new Result(false, null, null, text(response, "conversationId"), null, null, 1, null, null, null,
                    text(response, "errorCode"), text(response, "errorMessage"));
        }
        // Signed fields, in iyzico's order. Prices are compared without trailing zeros, as iyzico signs them.
        List<String> signed = List.of(text(response, "paymentStatus"), text(response, "paymentId"),
                text(response, "currency"), text(response, "basketId"), text(response, "conversationId"),
                plain(decimal(response, "paidPrice")), plain(decimal(response, "price")), text(response, "token"));
        if (!signatureMatches(response, signed)) {
            throw new IllegalStateException("iyzico's signature on the payment result does not match");
        }
        return new Result("SUCCESS".equals(text(response, "paymentStatus")), text(response, "paymentId"),
                text(response, "basketId"), text(response, "conversationId"), decimal(response, "price"),
                text(response, "currency"), response.path("installment").asInt(1), text(response, "cardAssociation"),
                text(response, "lastFourDigits"), text(response, "binNumber"), text(response, "errorCode"),
                text(response, "errorMessage"));
    }

    /** Returns part or all of a payment to the card. Usable on any day, unlike {@link #cancel}. */
    public void refund(String conversationId, String paymentId, BigDecimal amount, String currency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locale", "tr");
        body.put("conversationId", conversationId);
        body.put("paymentId", paymentId);
        body.put("price", price(amount));
        body.put("currency", currency);
        requireSuccess(post(REFUND_PATH, body));
    }

    /** Cancels a whole payment before it is settled, which normally means the same day. */
    public void cancel(String conversationId, String paymentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locale", "tr");
        body.put("conversationId", conversationId);
        body.put("paymentId", paymentId);
        requireSuccess(post(CANCEL_PATH, body));
    }

    private JsonNode post(String path, Map<String, Object> body) {
        if (!enabled()) {
            throw new IllegalStateException("iyzico keys are not configured");
        }
        // The signature covers these exact bytes, so the body is serialised once and sent as signed.
        String payload = json.writeValueAsString(body);
        String randomKey = System.currentTimeMillis() + String.valueOf(RANDOM.nextInt(1_000_000_000));
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", authorization(properties.apiKey().strip(), properties.secretKey().strip(),
                        randomKey, path, payload))
                .header("x-iyzi-rnd", randomKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("iyzico call to {} failed: {}", path, e.getClass().getSimpleName());
            throw new BankTimeoutException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BankTimeoutException();
        }
        if (response.statusCode() >= 500) {
            log.warn("iyzico answered {} on {}", response.statusCode(), path);
            throw new BankTimeoutException();
        }
        try {
            return json.readTree(response.body());
        } catch (RuntimeException e) {
            log.warn("iyzico answered {} on {} with a body that is not JSON", response.statusCode(), path);
            throw new BankTimeoutException();
        }
    }

    private static void requireSuccess(JsonNode response) {
        if (!"success".equals(text(response, "status"))) {
            throw new Refused(text(response, "errorCode"), text(response, "errorMessage"));
        }
    }

    /** IYZWSv2: an HMAC over the random key, the path and the body, wrapped with the API key in Base64. */
    static String authorization(String apiKey, String secretKey, String randomKey, String path, String payload) {
        String signature = hmacHex(secretKey, randomKey + path + payload);
        String plain = "apiKey:" + apiKey + "&randomKey:" + randomKey + "&signature:" + signature;
        return "IYZWSv2 " + Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** The signature iyzico puts on its responses: an HMAC over the given fields joined with colons. */
    static String responseSignature(String secretKey, List<String> fields) {
        return hmacHex(secretKey, fields.stream().map(field -> field == null ? "" : field).collect(Collectors.joining(":")));
    }

    private boolean signatureMatches(JsonNode response, List<String> fields) {
        String expected = responseSignature(properties.secretKey().strip(), fields);
        String actual = text(response, "signature");
        boolean matches = actual != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            log.warn("iyzico signature mismatch over fields {}", new ArrayList<>(fields));
        }
        return matches;
    }

    static String hmacHex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** iyzico takes prices as decimal strings, such as "149.90". */
    static String price(BigDecimal value) {
        return value.toPlainString();
    }

    static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : new BigDecimal(value);
    }
}
