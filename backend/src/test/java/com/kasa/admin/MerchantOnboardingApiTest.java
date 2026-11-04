package com.kasa.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;

import com.kasa.auth.AuthService;
import org.springframework.web.client.RestClient;

import com.kasa.settlement.SettlementService;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class MerchantOnboardingApiTest extends PostgresIntegrationTest {

    @Autowired
    AuthService authService;

    // The application context is shared with tests that need approved merchants, so the review is switched on
    // only around these tests, as production has it.
    @AfterEach
    void stopReviewing() {
        ReflectionTestUtils.setField(authService, "requireReview", false);
    }

    private static final String ADMIN = "test-admin-token";

    @Value("${local.server.port}")
    int port;

    @Autowired
    SettlementService settlement;

    RestClient client;
    String token;
    UUID tenantId;

    @BeforeEach
    void registerWithoutApproval() {
        ReflectionTestUtils.setField(authService, "requireReview", true);
        client = RestClient.create("http://localhost:" + port);
        Map<String, Object> registered = client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("businessName", "Bekleyen", "email", "yeni-" + UUID.randomUUID() + "@example.com", "password", "correct-horse-battery"))
                .retrieve().body(Map.class);
        token = (String) registered.get("accessToken");
        tenantId = UUID.fromString((String) registered.get("tenantId"));
    }

    @Test
    void aNewMerchantCanLookAroundButNotTakeMoney() {
        assertThat(me()).containsEntry("tenantStatus", "PENDING");
        assertThat(status(client.get().uri("/api/v1/payments").header("Authorization", bearer()))).isEqualTo(200);

        assertThat(createPayment()).isEqualTo(403);
        assertThat(owner.sql("SELECT COUNT(*) FROM payments WHERE tenant_id = :t").param("t", tenantId).query(Long.class).single()).isZero();
    }

    @Test
    void aCustomerOfAWaitingMerchantIsTurnedAwayAndTheSessionStaysUsable() {
        Map<String, Object> session = client.post().uri("/api/v1/checkout-sessions").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", 1000, "currency", "TRY")).retrieve().body(Map.class);

        int paid = status(client.post().uri("/api/v1/public/checkout/" + session.get("id") + "/pay").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cardToken", "tok_success")));

        assertThat(paid).isEqualTo(403);
        assertThat(client.get().uri("/api/v1/public/checkout/" + session.get("id")).retrieve().body(Map.class)).containsEntry("status", "OPEN");
    }

    @Test
    void approvalOpensPayments() {
        submitApplication();
        assertThat(admin("approve", null)).isEqualTo(204);

        assertThat(me()).containsEntry("tenantStatus", "ACTIVE");
        assertThat(createPayment()).isEqualTo(201);
    }

    @Test
    void suspendingStopsNewPaymentsAndPayoutsButNotRefunds() {
        submitApplication();
        admin("approve", null);
        TestMerchant merchant = asMerchant();
        UUID payment = merchant.capturedPayment(10_000);

        assertThat(admin("suspend", Map.of("reason", "Belgeler eksik"))).isEqualTo(204);

        assertThat(me()).containsEntry("tenantStatus", "SUSPENDED").containsEntry("tenantStatusReason", "Belgeler eksik");
        assertThat(createPayment()).isEqualTo(403);
        merchant.refund(payment, 1_000);
        settlement.settleAll(Instant.now().plusSeconds(1));
        assertThat(owner.sql("SELECT COUNT(*) FROM payouts WHERE tenant_id = :t").param("t", tenantId).query(Long.class).single()).isZero();

        assertThat(admin("reactivate", null)).isEqualTo(204);
        assertThat(createPayment()).isEqualTo(201);
        settlement.settleAll(Instant.now().plusSeconds(1));
        assertThat(owner.sql("SELECT COUNT(*) FROM payouts WHERE tenant_id = :t").param("t", tenantId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aSuspensionNeedsAReason() {
        assertThat(admin("suspend", Map.of("reason", " "))).isEqualTo(400);
    }

    @Test
    void onlyAllowedStepsAreAccepted() {
        assertThat(admin("reactivate", null)).isEqualTo(409);
        submitApplication();
        admin("approve", null);
        assertThat(admin("approve", null)).isEqualTo(409);
        assertThatThrownBy(() -> owner.sql("UPDATE tenants SET status = 'PENDING' WHERE id = :id").param("id", tenantId).update())
                .hasMessageContaining("illegal merchant transition");
    }

    @Test
    void aMerchantCannotApproveItself() {
        int status = status(client.post().uri("/api/v1/admin/merchants/" + tenantId + "/approve").header("Authorization", bearer()));

        assertThat(status).isEqualTo(403);
        assertThat(me()).containsEntry("tenantStatus", "PENDING");
    }

    @Test
    void theReviewListPutsWaitingMerchantsFirst() {
        List<Map<String, Object>> waiting = client.get().uri("/api/v1/admin/merchants?status=PENDING").header("X-Admin-Token", ADMIN)
                .retrieve().body(List.class);

        assertThat(waiting).anyMatch(merchant -> tenantId.toString().equals(merchant.get("id")) && "Bekleyen".equals(merchant.get("name")));
        assertThat(waiting).allMatch(merchant -> "PENDING".equals(merchant.get("status")));
    }

    @Test
    void bothLogsRecordTheDecision() {
        admin("suspend", Map.of("reason", "Şüpheli işlem"));

        assertThat(owner.sql("SELECT actor_label FROM audit_log WHERE tenant_id = :t AND action = 'MERCHANT_SUSPENDED'")
                .param("t", tenantId).query(String.class).single()).isEqualTo("Platform yönetimi");
        assertThat(owner.sql("SELECT COUNT(*) FROM admin_audit_log WHERE action = 'MERCHANT_SUSPENDED' AND target = 'Bekleyen'")
                .query(Long.class).single()).isPositive();
        Map<String, Object> chain = client.get().uri("/api/v1/audit-log/verify").header("Authorization", bearer()).retrieve().body(Map.class);
        assertThat(chain).containsEntry("valid", true);
    }

    @Test
    void aSuspendedMerchantIsNotChargedOnASchedule() {
        submitApplication();
        admin("approve", null);
        owner.sql("UPDATE tenants SET status = 'SUSPENDED' WHERE id = :id").param("id", tenantId).update();

        assertThat(owner.sql("SELECT COUNT(*) FROM find_subscriptions_to_renew(1000) WHERE tenant_id = :t")
                .param("t", tenantId).query(Long.class).single()).isZero();
    }

    @Test
    void businessesFromBeforeReviewsExistedStayActive() {
        assertThat(owner.sql("SELECT column_default FROM information_schema.columns WHERE table_name = 'tenants' AND column_name = 'status'")
                .query(String.class).single()).contains("ACTIVE");
    }

    @Test
    void nobodyIsApprovedWithoutAnApplication() {
        assertThat(admin("approve", null)).isEqualTo(409);
        assertThat(me()).containsEntry("tenantStatus", "PENDING");

        submitApplication();

        assertThat(admin("approve", null)).isEqualTo(204);
    }

    @Test
    void theReviewListShowsWhetherAnApplicationArrived() {
        assertThat(listed().get("profileSubmittedAt")).isNull();

        submitApplication();

        assertThat(listed().get("profileSubmittedAt")).isNotNull();
    }

    private Map<String, Object> listed() {
        List<Map<String, Object>> waiting = client.get().uri("/api/v1/admin/merchants?status=PENDING").header("X-Admin-Token", ADMIN)
                .retrieve().body(List.class);
        return waiting.stream().filter(merchant -> tenantId.toString().equals(merchant.get("id"))).findFirst().orElseThrow();
    }

    private void submitApplication() {
        assertThat(status(client.put().uri("/api/v1/settings/business-profile").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).body(MerchantProfileApiTest.validApplication()))).isEqualTo(200);
    }

    private TestMerchant asMerchant() {
        Map<String, Object> key = client.post().uri("/api/v1/api-keys").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "server")).retrieve().body(Map.class);
        return new TestMerchant(client, token, (String) key.get("key"), tenantId);
    }

    private int createPayment() {
        return status(client.post().uri("/api/v1/payments").header("Authorization", bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", 1000, "currency", "TRY", "cardToken", "tok_success")));
    }

    private int admin(String action, Object body) {
        var request = client.post().uri("/api/v1/admin/merchants/" + tenantId + "/" + action).header("X-Admin-Token", ADMIN);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return status(request);
    }

    private Map<String, Object> me() {
        return client.get().uri("/api/v1/me").header("Authorization", bearer()).retrieve().body(Map.class);
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private static int status(RestClient.RequestHeadersSpec<?> request) {
        return TestMerchant.statusOf(request);
    }
}
