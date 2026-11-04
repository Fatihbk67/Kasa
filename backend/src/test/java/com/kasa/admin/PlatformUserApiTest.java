package com.kasa.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class PlatformUserApiTest extends PostgresIntegrationTest {

    private static final String ADMIN = "test-admin-token";
    private static final String PASSWORD = "correct-horse-battery";

    @Value("${local.server.port}")
    int port;

    RestClient client;
    TestMerchant merchant;
    UUID ownerId;
    UUID viewerId;
    String viewerToken;
    String viewerEmail;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
        merchant = TestMerchant.register(port);
        ownerId = owner.sql("SELECT id FROM users WHERE tenant_id = :t").param("t", merchant.tenantId()).query(UUID.class).single();
        Map<String, Object> invitation = client.post().uri("/api/v1/team/invitations").header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("email", "izleyen-" + UUID.randomUUID() + "@example.com", "role", "VIEWER"))
                .retrieve().body(Map.class);
        viewerToken = (String) client.post().uri("/api/v1/public/invitations/" + invitation.get("token") + "/accept")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("password", PASSWORD)).retrieve().body(Map.class).get("accessToken");
        viewerEmail = (String) invitation.get("email");
        viewerId = owner.sql("SELECT id FROM users WHERE email = :e").param("e", viewerEmail).query(UUID.class).single();
    }

    @Test
    void theAdministratorSeesEveryoneAcrossBusinesses() {
        TestMerchant other = TestMerchant.register(port);

        List<Map<String, Object>> found = adminGet("/api/v1/admin/users?q=" + viewerEmail.substring(0, 20));
        assertThat(found).anyMatch(user -> viewerEmail.equals(user.get("email")) && "VIEWER".equals(user.get("role")));

        List<Map<String, Object>> everyone = adminGet("/api/v1/admin/users");
        assertThat(everyone).anyMatch(user -> other.tenantId().toString().equals(user.get("tenantId")));
        assertThat(everyone.getFirst()).doesNotContainKeys("passwordHash", "password_hash", "totpSecret");
    }

    @Test
    void aMerchantCannotListPeopleOrChangeRolesAcrossBusinesses() {
        assertThat(TestMerchant.statusOf(client.get().uri("/api/v1/admin/users").header("Authorization", "Bearer " + merchant.token()))).isEqualTo(403);
        assertThat(TestMerchant.statusOf(client.post().uri("/api/v1/admin/users/" + viewerId + "/role").header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("role", "OWNER")))).isEqualTo(403);
    }

    @Test
    void theAdministratorCanChangeARoleAndItTakesEffectAtOnce() {
        assertThat(createPaymentAs(viewerToken)).isEqualTo(403);

        assertThat(admin("/api/v1/admin/users/" + viewerId + "/role", Map.of("role", "ADMIN"))).isEqualTo(204);

        assertThat(createPaymentAs(viewerToken)).isEqualTo(201);
        assertThat(roleOf(viewerId)).isEqualTo("ADMIN");
    }

    @Test
    void aBusinessCannotLoseItsLastOwner() {
        assertThat(admin("/api/v1/admin/users/" + ownerId + "/role", Map.of("role", "VIEWER"))).isEqualTo(409);
        assertThat(admin("/api/v1/admin/users/" + ownerId + "/disable", null)).isEqualTo(409);

        admin("/api/v1/admin/users/" + viewerId + "/role", Map.of("role", "OWNER"));
        assertThat(admin("/api/v1/admin/users/" + ownerId + "/role", Map.of("role", "VIEWER"))).isEqualTo(204);
    }

    @Test
    void twoDemotionsAtOnceStillLeaveAnOwner() throws Exception {
        // A race shows up only some of the time, so it gets many chances to.
        for (int round = 0; round < 15; round++) {
            owner.sql("UPDATE users SET role = 'OWNER' WHERE tenant_id = :t").param("t", merchant.tenantId()).update();

            List<Integer> results = Concurrency.run(2, index -> () ->
                    admin("/api/v1/admin/users/" + (index == 0 ? ownerId : viewerId) + "/role", Map.of("role", "VIEWER")));

            assertThat(results).containsExactlyInAnyOrder(204, 409);
            assertThat(owner.sql("SELECT COUNT(*) FROM users WHERE tenant_id = :t AND role = 'OWNER' AND disabled_at IS NULL")
                    .param("t", merchant.tenantId()).query(Long.class).single()).isEqualTo(1);
        }
    }

    @Test
    void disablingSomeoneEndsTheirSessionAndEnablingRestoresIt() {
        assertThat(admin("/api/v1/admin/users/" + viewerId + "/disable", null)).isEqualTo(204);
        assertThat(meStatus(viewerToken)).isEqualTo(401);

        assertThat(admin("/api/v1/admin/users/" + viewerId + "/enable", null)).isEqualTo(204);
        assertThat(meStatus(viewerToken)).isEqualTo(200);
    }

    @Test
    void aRequestIsVisibleToTheAdministratorAndApprovingGrantsTheRole() {
        assertThat(requestRole(viewerToken, "ADMIN")).isEqualTo(201);

        List<Map<String, Object>> pending = adminGet("/api/v1/admin/role-requests?status=PENDING");
        Map<String, Object> request = pending.stream().filter(row -> viewerEmail.equals(row.get("email"))).findFirst().orElseThrow();
        assertThat(request).containsEntry("requestedRole", "ADMIN").containsEntry("currentRole", "VIEWER");
        assertThat(adminGet("/api/v1/admin/users?q=" + viewerEmail)).first().satisfies(user -> assertThat(user).containsEntry("pendingRequest", "ADMIN"));

        assertThat(admin("/api/v1/admin/role-requests/" + request.get("id") + "/approve", Map.of("note", "Tamam"))).isEqualTo(204);

        assertThat(roleOf(viewerId)).isEqualTo("ADMIN");
        assertThat(myRequest(viewerToken)).containsEntry("status", "APPROVED").containsEntry("decisionNote", "Tamam");
        assertThat(createPaymentAs(viewerToken)).isEqualTo(201);
    }

    @Test
    void rejectingNeedsAReasonAndChangesNothing() {
        requestRole(viewerToken, "OWNER");
        String id = pendingId();

        assertThat(admin("/api/v1/admin/role-requests/" + id + "/reject", Map.of("note", " "))).isEqualTo(400);
        assertThat(admin("/api/v1/admin/role-requests/" + id + "/reject", Map.of("note", "Sahip zaten var"))).isEqualTo(204);

        assertThat(roleOf(viewerId)).isEqualTo("VIEWER");
        assertThat(myRequest(viewerToken)).containsEntry("status", "REJECTED").containsEntry("decisionNote", "Sahip zaten var");
    }

    @Test
    void aRequestIsDecidedOnce() throws Exception {
        requestRole(viewerToken, "ADMIN");
        String id = pendingId();

        List<Integer> results = Concurrency.run(4, index -> () -> admin("/api/v1/admin/role-requests/" + id + (index % 2 == 0 ? "/approve" : "/reject"),
                Map.of("note", "karar")));

        assertThat(results.stream().filter(status -> status == 204)).hasSize(1);
        assertThat(results.stream().filter(status -> status == 409)).hasSize(3);
    }

    @Test
    void onlyOneOpenRequestAtATimeAndNotForTheCurrentRole() {
        assertThat(requestRole(viewerToken, "VIEWER")).isEqualTo(409);
        assertThat(requestRole(viewerToken, "ADMIN")).isEqualTo(201);
        assertThat(requestRole(viewerToken, "OWNER")).isEqualTo(409);

        assertThat(TestMerchant.statusOf(client.method(HttpMethod.DELETE).uri("/api/v1/settings/role-request").header("Authorization", "Bearer " + viewerToken)))
                .isEqualTo(204);
        assertThat(requestRole(viewerToken, "OWNER")).isEqualTo(201);
    }

    @Test
    void aRequestAlreadyGrantedByTheOwnerIsSimplyClosed() {
        requestRole(viewerToken, "ADMIN");
        String id = pendingId();
        client.put().uri("/api/v1/team/members/" + viewerId + "/role").header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("role", "ADMIN")).retrieve().toBodilessEntity();

        assertThat(admin("/api/v1/admin/role-requests/" + id + "/approve", null)).isEqualTo(204);
        assertThat(myRequest(viewerToken)).containsEntry("status", "APPROVED");
    }

    @Test
    void anApiKeyCannotAskForARole() {
        int status = TestMerchant.statusOf(client.post().uri("/api/v1/settings/role-request").header("X-Api-Key", merchant.apiKey())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("role", "OWNER", "reason", "x")));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void theBusinessSeesTheDecisionInItsOwnLog() {
        requestRole(viewerToken, "ADMIN");
        admin("/api/v1/admin/role-requests/" + pendingId() + "/approve", null);

        List<String> actions = owner.sql("SELECT action FROM audit_log WHERE tenant_id = :t ORDER BY seq").param("t", merchant.tenantId())
                .query(String.class).list();
        assertThat(actions).contains("ROLE_REQUESTED", "MEMBER_ROLE_CHANGED", "ROLE_REQUEST_APPROVED");
        Map<String, Object> chain = client.get().uri("/api/v1/audit-log/verify").header("Authorization", "Bearer " + merchant.token())
                .retrieve().body(Map.class);
        assertThat(chain).containsEntry("valid", true);
    }

    @Test
    void theDatabaseRefusesReopeningADecidedRequest() {
        requestRole(viewerToken, "ADMIN");
        String id = pendingId();
        admin("/api/v1/admin/role-requests/" + id + "/reject", Map.of("note", "hayır"));

        assertThatThrownBy(() -> owner.sql("UPDATE role_requests SET status = 'PENDING' WHERE id = :id").param("id", UUID.fromString(id)).update())
                .hasMessageContaining("illegal role request transition");
    }

    private String pendingId() {
        return (String) adminGet("/api/v1/admin/role-requests?status=PENDING").stream()
                .filter(row -> viewerEmail.equals(row.get("email"))).findFirst().orElseThrow().get("id");
    }

    private int requestRole(String token, String role) {
        return TestMerchant.statusOf(client.post().uri("/api/v1/settings/role-request").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("role", role, "reason", "Ödemeleri ben yönetiyorum")));
    }

    private Map<String, Object> myRequest(String token) {
        return client.get().uri("/api/v1/settings/role-request").header("Authorization", "Bearer " + token).retrieve().body(Map.class);
    }

    private int createPaymentAs(String token) {
        return TestMerchant.statusOf(client.post().uri("/api/v1/payments").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", 1000, "currency", "TRY", "cardToken", "tok_success")));
    }

    private int meStatus(String token) {
        return TestMerchant.statusOf(client.get().uri("/api/v1/me").header("Authorization", "Bearer " + token));
    }

    private String roleOf(UUID id) {
        return owner.sql("SELECT role FROM users WHERE id = :id").param("id", id).query(String.class).single();
    }

    private List<Map<String, Object>> adminGet(String path) {
        return client.get().uri(path).header("X-Admin-Token", ADMIN).retrieve().body(List.class);
    }

    private int admin(String path, Object body) {
        var request = client.post().uri(path).header("X-Admin-Token", ADMIN);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return TestMerchant.statusOf(request);
    }
}
