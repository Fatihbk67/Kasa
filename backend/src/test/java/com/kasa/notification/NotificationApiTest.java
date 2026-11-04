package com.kasa.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.kasa.bank.SimulatedBank;
import com.kasa.bank.SimulatedBank.PayoutBehavior;
import com.kasa.settlement.SettlementService;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

class NotificationApiTest extends PostgresIntegrationTest {

    private static final String ADMIN = "test-admin-token";
    private static final String PASSWORD = "correct-horse-battery";

    @Value("${local.server.port}")
    int port;

    @Autowired
    SettlementService settlement;

    @Autowired
    SimulatedBank bank;

    RestClient client;
    TestMerchant merchant;
    String viewerToken;
    UUID viewerId;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
        merchant = TestMerchant.register(port);
        Map<String, Object> invitation = client.post().uri("/api/v1/team/invitations").header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("email", "izleyen-" + UUID.randomUUID() + "@example.com", "role", "VIEWER"))
                .retrieve().body(Map.class);
        viewerToken = (String) client.post().uri("/api/v1/public/invitations/" + invitation.get("token") + "/accept")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("password", PASSWORD)).retrieve().body(Map.class).get("accessToken");
        viewerId = owner.sql("SELECT id FROM users WHERE email = :e").param("e", invitation.get("email")).query(UUID.class).single();
    }

    @Test
    void theOwnerHearsWhenSomeoneJoins() {
        Map<String, Object> inbox = inbox(merchant.token());

        assertThat(inbox.get("unread")).isEqualTo(1);
        assertThat(items(inbox)).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("kind", "MEMBER_JOINED").containsEntry("read", false).containsEntry("link", "/team");
        });
        Map<String, Object> viewerInbox = inbox(viewerToken);
        assertThat(items(viewerInbox)).isEmpty();
        // The badge counts the person's own unread notifications, not the whole business's.
        assertThat(viewerInbox.get("unread")).isEqualTo(0);
    }

    @Test
    void aDisputeReachesThoseWhoCanAnswerItButNotViewers() {
        UUID payment = merchant.capturedPayment(25_000);

        client.post().uri("/api/v1/disputes/simulate").header("X-Api-Key", merchant.apiKey())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("paymentId", payment, "reason", "FRAUDULENT")).retrieve().toBodilessEntity();

        assertThat(items(inbox(merchant.token()))).anySatisfy(item -> assertThat(item)
                .containsEntry("kind", "DISPUTE_OPENED").containsEntry("link", "/disputes")
                .satisfies(found -> assertThat((String) found.get("body")).contains("₺250,00").containsPattern("Son yanıt: \\d{1,2} \\p{L}+ \\d{4}\\.")));
        assertThat(items(inbox(viewerToken))).noneMatch(item -> "DISPUTE_OPENED".equals(item.get("kind")));
    }

    @Test
    void aSuspensionTellsEveryMember() {
        client.post().uri("/api/v1/admin/merchants/" + merchant.tenantId() + "/suspend").header("X-Admin-Token", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "Belge eksik")).retrieve().toBodilessEntity();

        assertThat(items(inbox(viewerToken))).anySatisfy(item -> assertThat(item).containsEntry("kind", "MERCHANT_SUSPENDED")
                .satisfies(found -> assertThat((String) found.get("body")).contains("Belge eksik")));
        assertThat(items(inbox(merchant.token()))).anyMatch(item -> "MERCHANT_SUSPENDED".equals(item.get("kind")));
    }

    @Test
    void theRequesterHearsTheAnswerToARoleRequest() {
        client.post().uri("/api/v1/settings/role-request").header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("role", "ADMIN", "reason", "İade yapmam gerekiyor"))
                .retrieve().toBodilessEntity();
        UUID requestId = owner.sql("SELECT id FROM role_requests WHERE user_id = :u").param("u", viewerId).query(UUID.class).single();

        client.post().uri("/api/v1/admin/role-requests/" + requestId + "/reject").header("X-Admin-Token", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("note", "Sahip onaylamadı")).retrieve().toBodilessEntity();

        assertThat(items(inbox(viewerToken))).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("kind", "ROLE_REQUEST_REJECTED").containsEntry("body", "Not: Sahip onaylamadı"));
        assertThat(items(inbox(merchant.token()))).noneMatch(item -> "ROLE_REQUEST_REJECTED".equals(item.get("kind")));
    }

    @Test
    void readingOneLeavesTheRestUnread() {
        merchant.capturedPayment(1_000);
        open(merchant.capturedPayment(2_000));
        open(merchant.capturedPayment(3_000));
        List<Map<String, Object>> items = items(inbox(merchant.token()));
        assertThat(items).hasSize(3);

        assertThat(post("/api/v1/notifications/" + items.getFirst().get("id") + "/read", merchant.token())).isEqualTo(204);

        Map<String, Object> after = inbox(merchant.token());
        assertThat(after.get("unread")).isEqualTo(2);
        assertThat(items(after).getFirst()).containsEntry("read", true);

        assertThat(post("/api/v1/notifications/read-all", merchant.token())).isEqualTo(204);
        assertThat(inbox(merchant.token()).get("unread")).isEqualTo(0);
    }

    @Test
    void nobodyCanMarkAColleaguesNotification() {
        String id = (String) items(inbox(merchant.token())).getFirst().get("id");

        assertThat(post("/api/v1/notifications/" + id + "/read", viewerToken)).isEqualTo(404);
        assertThat(inbox(merchant.token()).get("unread")).isEqualTo(1);
    }

    @Test
    void anotherBusinessCannotTouchTheInbox() {
        TestMerchant other = TestMerchant.register(port);
        String id = (String) items(inbox(merchant.token())).getFirst().get("id");

        assertThat(post("/api/v1/notifications/" + id + "/read", other.token())).isEqualTo(404);
        assertThat(items(inbox(other.token()))).isEmpty();
    }

    @Test
    void theOwnerHearsHowEachPayoutWent() {
        merchant.capturedPayment(10_000);
        bank.setPayoutBehavior(merchant.tenantId(), PayoutBehavior.REJECT);
        settlement.settleAll(java.time.Instant.now().plusSeconds(1));
        bank.setPayoutBehavior(merchant.tenantId(), PayoutBehavior.APPROVE);
        settlement.settleAll(java.time.Instant.now().plusSeconds(1));

        List<Map<String, Object>> items = items(inbox(merchant.token()));
        assertThat(items).extracting(item -> item.get("kind")).containsSubsequence("PAYOUT_PAID", "PAYOUT_FAILED");
        assertThat(items).anySatisfy(item -> assertThat((String) item.get("body")).startsWith("₺97,50"));
        assertThat(items(inbox(viewerToken))).isEmpty();
    }

    @Test
    void anApiKeyHasNoInbox() {
        int status = TestMerchant.statusOf(client.get().uri("/api/v1/notifications").header("X-Api-Key", merchant.apiKey()));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void aDisabledMemberIsNotNotified() {
        owner.sql("UPDATE users SET disabled_at = now() WHERE id = :id").param("id", viewerId).update();

        client.post().uri("/api/v1/admin/merchants/" + merchant.tenantId() + "/suspend").header("X-Admin-Token", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "Belge eksik")).retrieve().toBodilessEntity();

        assertThat(owner.sql("SELECT COUNT(*) FROM notifications WHERE user_id = :id").param("id", viewerId).query(Long.class).single()).isZero();
    }

    private void open(UUID payment) {
        client.post().uri("/api/v1/disputes/simulate").header("X-Api-Key", merchant.apiKey())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("paymentId", payment, "reason", "DUPLICATE")).retrieve().toBodilessEntity();
    }

    private Map<String, Object> inbox(String token) {
        return client.get().uri("/api/v1/notifications").header("Authorization", "Bearer " + token).retrieve().body(Map.class);
    }

    private static List<Map<String, Object>> items(Map<String, Object> inbox) {
        return (List<Map<String, Object>>) inbox.get("items");
    }

    private int post(String path, String token) {
        return TestMerchant.statusOf(client.post().uri(path).header("Authorization", "Bearer " + token));
    }
}
