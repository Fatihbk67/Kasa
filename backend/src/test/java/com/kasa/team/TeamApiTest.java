package com.kasa.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

/** Permissions are tested through real HTTP, against every role that could try the action. */
class TeamApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "uzun-bir-test-parolasi";

    @Value("${local.server.port}")
    int port;

    TestMerchant merchant;
    String owner_;

    @BeforeEach
    void registerMerchant() {
        merchant = TestMerchant.register(port);
        owner_ = merchant.token();
    }

    @Test
    void anInvitedPersonJoinsWithTheInvitedRole() {
        String token = join(owner_, "ADMIN");

        Map<String, Object> me = send(HttpMethod.GET, "/api/v1/me", null, bearer(token)).getBody();

        assertThat(me).containsEntry("role", "ADMIN").containsEntry("tenantId", merchant.tenantId().toString());
    }

    @Test
    void aPublicInvitationViewShowsOnlyWhatTheInviteeNeeds() {
        String email = email();
        String invitation = invite(owner_, email, "VIEWER");

        Map<String, Object> view = send(HttpMethod.GET, "/api/v1/public/invitations/" + invitation, null, anonymous()).getBody();

        assertThat(view).containsEntry("email", email).containsEntry("status", "OPEN").containsEntry("role", "VIEWER");
        assertThat(view).doesNotContainKeys("tenantId", "id", "token");
    }

    @Test
    void anInvitationWorksOnlyOnceEvenWhenAcceptedAtTheSameTime() throws Exception {
        String invitation = invite(owner_, email(), "VIEWER");

        List<Integer> statuses = Concurrency.run(10, i -> () -> accept(invitation).getStatusCode().value());

        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
        assertThat(statuses).containsOnly(200, 410);
    }

    @Test
    void anExpiredOrRevokedInvitationCannotBeUsed() {
        String expired = invite(owner_, email(), "VIEWER");
        owner.sql("UPDATE team_invitations SET expires_at = now() - interval '1 minute' WHERE tenant_id = :t")
                .param("t", merchant.tenantId()).update();
        assertThat(accept(expired).getStatusCode().value()).isEqualTo(410);

        Map<String, Object> created = send(HttpMethod.POST, "/api/v1/team/invitations",
                Map.of("email", email(), "role", "VIEWER"), bearer(owner_)).getBody();
        send(HttpMethod.DELETE, "/api/v1/team/invitations/" + created.get("id"), null, bearer(owner_));
        assertThat(accept((String) created.get("token")).getStatusCode().value()).isEqualTo(410);
    }

    @Test
    void theInvitationTokenIsNotStored() {
        String invitation = invite(owner_, email(), "VIEWER");

        List<String> stored = owner.sql("SELECT token_hash FROM team_invitations WHERE tenant_id = :t")
                .param("t", merchant.tenantId()).query(String.class).list();

        assertThat(stored).hasSize(1).doesNotContain(invitation);
    }

    @Test
    void aViewerCanReadButNotActOrManage() {
        String viewer = join(owner_, "VIEWER");
        UUID payment = merchant.authorized(1_000, "tok_success");

        assertThat(status(HttpMethod.GET, "/api/v1/payments", null, bearer(viewer))).isEqualTo(200);
        assertThat(status(HttpMethod.GET, "/api/v1/analytics/summary", null, bearer(viewer))).isEqualTo(200);
        assertThat(status(HttpMethod.GET, "/api/v1/balances", null, bearer(viewer))).isEqualTo(200);
        assertThat(status(HttpMethod.GET, "/api/v1/me", null, bearer(viewer))).isEqualTo(200);

        Consumer<HttpHeaders> idempotent = bearer(viewer).andThen(h -> h.set("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(status(HttpMethod.POST, "/api/v1/payments/" + payment + "/capture", null, idempotent)).isEqualTo(403);
        assertThat(status(HttpMethod.POST, "/api/v1/checkout-sessions", Map.of("amount", 100, "currency", "TRY"), bearer(viewer))).isEqualTo(403);
        assertThat(status(HttpMethod.GET, "/api/v1/api-keys", null, bearer(viewer))).isEqualTo(403);
        assertThat(status(HttpMethod.GET, "/api/v1/webhook-endpoints", null, bearer(viewer))).isEqualTo(403);
        assertThat(status(HttpMethod.GET, "/api/v1/team", null, bearer(viewer))).isEqualTo(403);
        assertThat(status(HttpMethod.GET, "/api/v1/audit-log", null, bearer(viewer))).isEqualTo(403);
        assertThat(status(HttpMethod.PUT, "/api/v1/settings/tenant", Map.of("name", "X"), bearer(viewer))).isEqualTo(403);
    }

    @Test
    void aManagerCanOperateButNotGrantPowerOrChangeTheBusiness() {
        String manager = join(owner_, "ADMIN");
        UUID memberId = memberIdOf(join(owner_, "VIEWER"));

        assertThat(status(HttpMethod.GET, "/api/v1/api-keys", null, bearer(manager))).isEqualTo(200);
        assertThat(status(HttpMethod.POST, "/api/v1/team/invitations", Map.of("email", email(), "role", "VIEWER"), bearer(manager))).isEqualTo(201);
        assertThat(status(HttpMethod.POST, "/api/v1/team/invitations", Map.of("email", email(), "role", "ADMIN"), bearer(manager))).isEqualTo(403);
        assertThat(status(HttpMethod.POST, "/api/v1/team/invitations", Map.of("email", email(), "role", "OWNER"), bearer(manager))).isEqualTo(403);
        assertThat(status(HttpMethod.PUT, "/api/v1/team/members/" + memberId + "/role", Map.of("role", "ADMIN"), bearer(manager))).isEqualTo(403);
        assertThat(status(HttpMethod.DELETE, "/api/v1/team/members/" + memberId, null, bearer(manager))).isEqualTo(403);
        assertThat(status(HttpMethod.PUT, "/api/v1/settings/tenant", Map.of("name", "X"), bearer(manager))).isEqualTo(403);
    }

    @Test
    void anApiKeyCannotTouchTeamOrAuditOrSettings() {
        Consumer<HttpHeaders> key = headers -> headers.set("X-Api-Key", merchant.apiKey());

        assertThat(status(HttpMethod.GET, "/api/v1/team", null, key)).isEqualTo(403);
        assertThat(status(HttpMethod.GET, "/api/v1/audit-log", null, key)).isEqualTo(403);
        assertThat(status(HttpMethod.GET, "/api/v1/me", null, key)).isEqualTo(403);
        assertThat(status(HttpMethod.PUT, "/api/v1/settings/tenant", Map.of("name", "X"), key)).isEqualTo(403);
    }

    @Test
    void loweringARoleTakesEffectOnTheNextRequestWithTheSameToken() {
        String manager = join(owner_, "ADMIN");
        assertThat(status(HttpMethod.GET, "/api/v1/api-keys", null, bearer(manager))).isEqualTo(200);

        assertThat(status(HttpMethod.PUT, "/api/v1/team/members/" + memberIdOf(manager) + "/role", Map.of("role", "VIEWER"), bearer(owner_))).isEqualTo(204);

        assertThat(status(HttpMethod.GET, "/api/v1/api-keys", null, bearer(manager))).isEqualTo(403);
    }

    @Test
    void aRemovedMemberIsLockedOutAtOnceAndCannotLogIn() {
        String email = email();
        String invitation = invite(owner_, email, "ADMIN");
        String member = (String) accept(invitation).getBody().get("accessToken");

        assertThat(status(HttpMethod.DELETE, "/api/v1/team/members/" + memberIdOf(member), null, bearer(owner_))).isEqualTo(204);

        assertThat(status(HttpMethod.GET, "/api/v1/tenant", null, bearer(member))).isEqualTo(401);
        assertThat(status(HttpMethod.POST, "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), anonymous())).isEqualTo(401);
    }

    @Test
    void nobodyCanChangeTheirOwnMembership() {
        UUID self = UUID.fromString((String) send(HttpMethod.GET, "/api/v1/me", null, bearer(owner_)).getBody().get("userId"));

        assertThat(status(HttpMethod.PUT, "/api/v1/team/members/" + self + "/role", Map.of("role", "VIEWER"), bearer(owner_))).isEqualTo(409);
        assertThat(status(HttpMethod.DELETE, "/api/v1/team/members/" + self, null, bearer(owner_))).isEqualTo(409);
    }

    @Test
    void twoOwnersDemotingEachOtherCannotLeaveTheBusinessWithoutAnOwner() throws Exception {
        for (int round = 0; round < 5; round++) {
            TestMerchant business = TestMerchant.register(port);
            String firstOwner = business.token();
            String invitation = (String) send(HttpMethod.POST, "/api/v1/team/invitations", Map.of("email", email(), "role", "OWNER"), bearer(firstOwner))
                    .getBody().get("token");
            String secondOwner = (String) accept(invitation).getBody().get("accessToken");
            UUID firstId = memberIdOf(firstOwner);
            UUID secondId = memberIdOf(secondOwner);

            Concurrency.run(2, i -> () -> i == 0
                    ? status(HttpMethod.PUT, "/api/v1/team/members/" + secondId + "/role", Map.of("role", "VIEWER"), bearer(firstOwner))
                    : status(HttpMethod.PUT, "/api/v1/team/members/" + firstId + "/role", Map.of("role", "VIEWER"), bearer(secondOwner)));

            int owners = owner.sql("SELECT count(*) FROM users WHERE tenant_id = :t AND role = 'OWNER' AND disabled_at IS NULL")
                    .param("t", business.tenantId()).query(Integer.class).single();
            assertThat(owners).isEqualTo(1);
        }
    }

    @Test
    void anotherBusinessCannotSeeOrChangeMembers() {
        String memberOfMine = join(owner_, "VIEWER");
        TestMerchant other = TestMerchant.register(port);

        assertThat(status(HttpMethod.DELETE, "/api/v1/team/members/" + memberIdOf(memberOfMine), null, bearer(other.token()))).isEqualTo(404);
        Map<String, Object> theirTeam = send(HttpMethod.GET, "/api/v1/team", null, bearer(other.token())).getBody();
        assertThat((List<?>) theirTeam.get("members")).hasSize(1);
    }

    @Test
    void changingAPasswordEndsOtherSessionsButNotThisOne() {
        String email = email();
        String oldToken = (String) accept(invite(owner_, email, "ADMIN")).getBody().get("accessToken");
        String secondSession = (String) send(HttpMethod.POST, "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), anonymous()).getBody().get("accessToken");

        ResponseEntity<Map> changed = send(HttpMethod.POST, "/api/v1/settings/password",
                Map.of("currentPassword", PASSWORD, "newPassword", "yepyeni-bir-parola-123"), bearer(oldToken));

        assertThat(changed.getStatusCode().value()).isEqualTo(200);
        assertThat(status(HttpMethod.GET, "/api/v1/me", null, bearer(secondSession))).isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/api/v1/me", null, bearer(oldToken))).isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/api/v1/me", null, bearer((String) changed.getBody().get("accessToken")))).isEqualTo(200);
        assertThat(status(HttpMethod.POST, "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), anonymous())).isEqualTo(401);
        assertThat(status(HttpMethod.POST, "/api/v1/auth/login", Map.of("email", email, "password", "yepyeni-bir-parola-123"), anonymous())).isEqualTo(200);
    }

    @Test
    void aWrongCurrentPasswordChangesNothing() {
        String email = email();
        String token = (String) accept(invite(owner_, email, "ADMIN")).getBody().get("accessToken");

        assertThat(status(HttpMethod.POST, "/api/v1/settings/password",
                Map.of("currentPassword", "yanlis-parola-1234", "newPassword", "yepyeni-bir-parola-123"), bearer(token))).isEqualTo(422);

        assertThat(status(HttpMethod.GET, "/api/v1/me", null, bearer(token))).isEqualTo(200);
    }

    @Test
    void theOwnerCanRenameTheBusiness() {
        assertThat(status(HttpMethod.PUT, "/api/v1/settings/tenant", Map.of("name", "Yeni Ad"), bearer(owner_))).isEqualTo(204);

        assertThat(send(HttpMethod.GET, "/api/v1/tenant", null, bearer(owner_)).getBody()).containsEntry("name", "Yeni Ad");
    }

    private String join(String inviterToken, String role) {
        return (String) accept(invite(inviterToken, email(), role)).getBody().get("accessToken");
    }

    private String invite(String inviterToken, String email, String role) {
        ResponseEntity<Map> response = send(HttpMethod.POST, "/api/v1/team/invitations", Map.of("email", email, "role", role), bearer(inviterToken));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return (String) response.getBody().get("token");
    }

    private ResponseEntity<Map> accept(String invitation) {
        return send(HttpMethod.POST, "/api/v1/public/invitations/" + invitation + "/accept", Map.of("password", PASSWORD), anonymous());
    }

    private UUID memberIdOf(String token) {
        return UUID.fromString((String) send(HttpMethod.GET, "/api/v1/me", null, bearer(token)).getBody().get("userId"));
    }

    private int status(HttpMethod method, String path, Object body, Consumer<HttpHeaders> headers) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method).uri(path).headers(headers);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().onStatus(s -> true, (req, res) -> { }).toBodilessEntity().getStatusCode().value();
    }

    private ResponseEntity<Map> send(HttpMethod method, String path, Object body, Consumer<HttpHeaders> headers) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method).uri(path).headers(headers);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().onStatus(s -> true, (req, res) -> { }).toEntity(Map.class);
    }

    private static Consumer<HttpHeaders> bearer(String token) {
        return headers -> headers.setBearerAuth(token);
    }

    private static Consumer<HttpHeaders> anonymous() {
        return headers -> { };
    }

    private static String email() {
        return "uye-" + UUID.randomUUID() + "@example.com";
    }
}
