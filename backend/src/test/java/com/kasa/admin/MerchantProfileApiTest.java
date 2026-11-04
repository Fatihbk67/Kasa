package com.kasa.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.kasa.auth.AuthService;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class MerchantProfileApiTest extends PostgresIntegrationTest {

    private static final String ADMIN = "test-admin-token";
    private static final String IBAN = "TR330006100519786457841326";

    /** A sole proprietor with a published sample identity number and IBAN, both of which pass their check digits. */
    static Map<String, Object> validApplication() {
        Map<String, Object> body = new HashMap<>();
        body.put("businessType", "SOLE_PROPRIETOR");
        body.put("legalName", "Ali Usta Fırıncılık");
        body.put("taxId", "10000000146");
        body.put("taxOffice", "Beşiktaş");
        body.put("sector", "FOOD");
        body.put("website", "");
        body.put("phone", "+90 212 555 01 02");
        body.put("address", "Sinanpaşa Mah. Çarşı Sk. No: 4");
        body.put("city", "İstanbul");
        body.put("iban", "tr33 0006 1005 1978 6457 8413 26");
        return body;
    }

    @Autowired
    AuthService authService;

    @Value("${local.server.port}")
    int port;

    RestClient client;
    String token;
    UUID tenantId;

    @BeforeEach
    void registerWaitingMerchant() {
        ReflectionTestUtils.setField(authService, "requireReview", true);
        client = RestClient.create("http://localhost:" + port);
        Map<String, Object> registered = client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("businessName", "Fırın", "email", "firin-" + UUID.randomUUID() + "@example.com", "password", "correct-horse-battery"))
                .retrieve().body(Map.class);
        token = (String) registered.get("accessToken");
        tenantId = UUID.fromString((String) registered.get("tenantId"));
    }

    @AfterEach
    void stopReviewing() {
        ReflectionTestUtils.setField(authService, "requireReview", false);
    }

    @Test
    void anApplicationIsSavedWithTheIbanMaskedAndEncrypted() {
        assertThat(get().getStatusCode().value()).isEqualTo(204);

        Map<String, Object> saved = put(validApplication()).getBody();

        assertThat(saved).containsEntry("legalName", "Ali Usta Fırıncılık").containsEntry("bankCode", "00061")
                .containsEntry("editable", true).containsEntry("website", null);
        assertThat((String) saved.get("iban")).endsWith("13 26").doesNotContain("0519786457");
        String stored = owner.sql("SELECT iban FROM merchant_profiles WHERE tenant_id = :t").param("t", tenantId).query(String.class).single();
        assertThat(stored).startsWith("enc1:").doesNotContain("7841326");
        assertThat(get().getBody()).containsEntry("taxId", "10000000146");
    }

    @Test
    void theAdministratorSeesTheFullIbanAndTheLookIsRecorded() {
        put(validApplication());

        Map<String, Object> application = client.get().uri("/api/v1/admin/merchants/" + tenantId + "/application")
                .header("X-Admin-Token", ADMIN).retrieve().body(Map.class);

        assertThat(application).containsEntry("iban", IBAN).containsEntry("editable", false);
        assertThat(owner.sql("SELECT COUNT(*) FROM admin_audit_log WHERE action = 'MERCHANT_APPLICATION_VIEWED' AND target = 'Ali Usta Fırıncılık'")
                .query(Long.class).single()).isPositive();
    }

    @Test
    void aMissingApplicationIsNotFoundForTheAdministrator() {
        int status = TestMerchant.statusOf(client.get().uri("/api/v1/admin/merchants/" + tenantId + "/application").header("X-Admin-Token", ADMIN));

        assertThat(status).isEqualTo(404);
    }

    @Test
    void numbersWithWrongCheckDigitsAreRejected() {
        assertThat(statusOf(with("taxId", "10000000145"))).isEqualTo(422);
        assertThat(statusOf(with("iban", "TR330006100519786457841327"))).isEqualTo(422);
        assertThat(statusOf(with("iban", "DE89370400440532013000"))).isEqualTo(422);
        assertThat(statusOf(with("sector", "CASINO"))).isEqualTo(422);
        assertThat(statusOf(with("legalName", " "))).isEqualTo(400);
        assertThat(owner.sql("SELECT COUNT(*) FROM merchant_profiles WHERE tenant_id = :t").param("t", tenantId).query(Long.class).single()).isZero();
    }

    @Test
    void aCompanyNeedsATaxNumberNotAnIdentityNumber() {
        Map<String, Object> company = with("businessType", "COMPANY");

        assertThat(statusOf(company)).isEqualTo(422);
        company.put("taxId", "1234567890");
        assertThat(statusOf(company)).isEqualTo(200);
    }

    @Test
    void theApplicationCanBeCorrectedUntilApprovalAndIsFrozenAfter() {
        put(validApplication());
        assertThat(statusOf(with("city", "Ankara"))).isEqualTo(200);
        assertThat(get().getBody()).containsEntry("city", "Ankara");

        admin("approve");

        assertThat(get().getBody()).containsEntry("editable", false);
        assertThat(statusOf(with("city", "İzmir"))).isEqualTo(409);
        assertThat(owner.sql("SELECT city FROM merchant_profiles WHERE tenant_id = :t").param("t", tenantId).query(String.class).single())
                .isEqualTo("Ankara");
    }

    @Test
    void aSuspendedMerchantCanFixTheApplication() {
        put(validApplication());
        admin("approve");
        client.post().uri("/api/v1/admin/merchants/" + tenantId + "/suspend").header("X-Admin-Token", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "IBAN sahibi uyuşmuyor")).retrieve().toBodilessEntity();

        assertThat(statusOf(with("legalName", "Ali Usta"))).isEqualTo(200);
    }

    @Test
    void submittingIsAuditedWithoutThePersonalData() {
        put(validApplication());

        String metadata = owner.sql("SELECT metadata::text FROM audit_log WHERE tenant_id = :t AND action = 'BUSINESS_PROFILE_SUBMITTED'")
                .param("t", tenantId).query(String.class).single();
        assertThat(metadata).doesNotContain("10000000146").doesNotContain("7841326");
    }

    @Test
    void onlyTheOwnerSendsTheApplication() {
        Map<String, Object> key = client.post().uri("/api/v1/api-keys").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "server")).retrieve().body(Map.class);

        int status = TestMerchant.statusOf(client.put().uri("/api/v1/settings/business-profile").header("X-Api-Key", (String) key.get("key"))
                .contentType(MediaType.APPLICATION_JSON).body(validApplication()));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void anotherBusinessCannotSeeTheApplication() {
        put(validApplication());
        TestMerchant other = TestMerchant.register(port);

        int status = TestMerchant.statusOf(client.get().uri("/api/v1/settings/business-profile").header("Authorization", "Bearer " + other.token()));

        assertThat(status).isEqualTo(204);
    }

    private Map<String, Object> with(String field, Object value) {
        Map<String, Object> body = validApplication();
        body.put(field, value);
        return body;
    }

    private void admin(String action) {
        client.post().uri("/api/v1/admin/merchants/" + tenantId + "/" + action).header("X-Admin-Token", ADMIN).retrieve().toBodilessEntity();
    }

    private org.springframework.http.ResponseEntity<Map> get() {
        return client.get().uri("/api/v1/settings/business-profile").header("Authorization", "Bearer " + token).retrieve().toEntity(Map.class);
    }

    private org.springframework.http.ResponseEntity<Map> put(Map<String, Object> body) {
        return client.put().uri("/api/v1/settings/business-profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(Map.class);
    }

    private int statusOf(Map<String, Object> body) {
        return TestMerchant.statusOf(client.put().uri("/api/v1/settings/business-profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(body));
    }
}
