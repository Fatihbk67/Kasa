package com.kasa.admin;

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

import com.kasa.security.Totp;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;
import com.kasa.webhook.WebhookSecretMigration;

class AdminAccountApiTest extends PostgresIntegrationTest {

    private static final String SHARED_TOKEN = "test-admin-token";
    private static final String PASSWORD = "a-long-admin-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    WebhookSecretMigration webhookSecrets;

    RestClient client;
    String email;
    Map<String, Object> created;

    @BeforeEach
    void createAdmin() {
        client = RestClient.create("http://localhost:" + port);
        email = "yonetici-" + UUID.randomUUID() + "@kasa.test";
        created = client.post().uri("/api/v1/admin/accounts").header("X-Admin-Token", SHARED_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("email", email, "password", PASSWORD))
                .retrieve().body(Map.class);
    }

    @Test
    void anAdministratorSignsInWithPasswordAndCodeAndReachesTheAdminApi() {
        String token = login(code());

        assertThat(status("/api/v1/admin/overview", token)).isEqualTo(200);
    }

    @Test
    void thePasswordAloneOrACodeAloneIsNotEnough() {
        assertThat(loginStatus(PASSWORD, wrongCode())).isEqualTo(401);
        assertThat(loginStatus("wrong-password-here", code())).isEqualTo(401);
    }

    @Test
    void aCodeCannotBeUsedTwice() {
        String code = code();
        login(code);

        assertThat(loginStatus(PASSWORD, code)).isEqualTo(401);
    }

    @Test
    void guessingLocksTheAccountAndThenEvenTheRightCodeFails() {
        String challenge = challenge(PASSWORD);
        for (int i = 0; i < 5; i++) {
            assertThat(complete(challenge, wrongCode())).isEqualTo(401);
        }

        assertThat(owner.sql("SELECT totp_locked_until > now() FROM platform_admins WHERE email = :email")
                .param("email", email).query(Boolean.class).single()).isTrue();
        assertThat(complete(challenge, code())).isEqualTo(401);
    }

    @Test
    void theSharedLoginPageOffersBothPanelsWhenTheEmailIsBoth() {
        String shared = "ikisi-" + UUID.randomUUID() + "@kasa.test";
        client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("businessName", "İkili", "email", shared, "password", PASSWORD)).retrieve().toBodilessEntity();
        client.post().uri("/api/v1/admin/accounts").header("X-Admin-Token", SHARED_TOKEN).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", shared, "password", PASSWORD)).retrieve().toBodilessEntity();

        Map<String, Object> response = client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", shared, "password", PASSWORD)).retrieve().body(Map.class);

        assertThat(response.get("accessToken")).isNotNull();
        assertThat(response.get("adminChallengeToken")).isNotNull();
    }

    @Test
    void aMerchantLoginGetsNoAdminStep() {
        String merchant = "isletme-" + UUID.randomUUID() + "@kasa.test";
        client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("businessName", "Tek", "email", merchant, "password", PASSWORD)).retrieve().toBodilessEntity();

        Map<String, Object> response = client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", merchant, "password", PASSWORD)).retrieve().body(Map.class);

        assertThat(response.get("adminChallengeToken")).isNull();
        assertThat(response.get("accessToken")).isNotNull();
    }

    @Test
    void anAdminChallengeIsNotAMerchantChallenge() {
        String challenge = challenge(PASSWORD);

        int status = TestMerchant.statusOf(client.post().uri("/api/v1/auth/login/two-factor").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("challengeToken", challenge, "code", code())));

        assertThat(status).isEqualTo(401);
    }

    @Test
    void anExpiredAdminChallengeIsRefused() {
        String challenge = challenge(PASSWORD);
        owner.sql("UPDATE admin_login_challenges SET expires_at = now() - interval '1 second'").update();

        assertThat(complete(challenge, code())).isEqualTo(401);
    }

    @Test
    void theAdminTokenOpensNoMerchantData() {
        String token = login(code());

        assertThat(status("/api/v1/payments", token)).isEqualTo(403);
        assertThat(status("/api/v1/me", token)).isEqualTo(403);
        assertThat(status("/api/v1/customers", token)).isEqualTo(403);
    }

    @Test
    void aMerchantTokenCannotReachTheAdminApi() {
        TestMerchant merchant = TestMerchant.register(port);

        assertThat(status("/api/v1/admin/overview", merchant.token())).isEqualTo(403);
    }

    @Test
    void aDisabledAdministratorIsOutAtOnce() {
        String token = login(code());
        String other = "ikinci-" + UUID.randomUUID() + "@kasa.test";
        Map<String, Object> second = client.post().uri("/api/v1/admin/accounts").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("email", other, "password", PASSWORD)).retrieve().body(Map.class);

        client.post().uri("/api/v1/admin/accounts/" + created.get("id") + "/disable").header("X-Admin-Token", SHARED_TOKEN).retrieve().toBodilessEntity();

        assertThat(status("/api/v1/admin/overview", token)).isEqualTo(401);
        assertThat(second.get("id")).isNotNull();
    }

    @Test
    void raisingTheTokenVersionEndsOpenSessions() {
        String token = login(code());

        owner.sql("UPDATE platform_admins SET token_version = token_version + 1 WHERE email = :email").param("email", email).update();

        assertThat(status("/api/v1/admin/overview", token)).isEqualTo(401);
    }

    @Test
    void nobodyCanDisableThemselves() {
        String token = login(code());

        Integer status = client.post().uri("/api/v1/admin/accounts/" + created.get("id") + "/disable").header("Authorization", "Bearer " + token)
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(409);
    }

    @Test
    void anEmailCanBeUsedOnce() {
        Integer status = client.post().uri("/api/v1/admin/accounts").header("X-Admin-Token", SHARED_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("email", email, "password", PASSWORD))
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(409);
    }

    @Test
    void theLogSaysWhoDidWhat() {
        String token = login(code());
        client.post().uri("/api/v1/admin/settlements/run").header("Authorization", "Bearer " + token).retrieve().toBodilessEntity();

        List<Map<String, Object>> entries = client.get().uri("/api/v1/admin/audit-log").header("Authorization", "Bearer " + token).retrieve().body(List.class);

        assertThat(entries).anyMatch(entry -> email.equals(entry.get("actor")) && "SETTLEMENT_RUN".equals(entry.get("action")));
        assertThat(entries).anyMatch(entry -> email.equals(entry.get("actor")) && "ADMIN_LOGIN".equals(entry.get("action")));
    }

    @Test
    void theApplicationCannotEditTheLog() {
        var appConnection = org.springframework.jdbc.core.simple.JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                owner.sql("SELECT 'jdbc:postgresql://localhost:' || current_setting('port') || '/kasa'").query(String.class).single(), "kasa_app", "kasa_app"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> appConnection.sql("DELETE FROM admin_audit_log").update())
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void anAuthenticatorSecretIsEncryptedAtRest() {
        String stored = owner.sql("SELECT totp_secret FROM platform_admins WHERE email = :email").param("email", email).query(String.class).single();

        assertThat(stored).startsWith("enc1:").doesNotContain((String) created.get("totpSecret"));
    }

    @Test
    void newWebhookSecretsAreEncryptedAndOldOnesGetConverted() {
        TestMerchant merchant = TestMerchant.register(port);
        Map<String, Object> endpoint = merchant.client().post().uri("/api/v1/webhook-endpoints").header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("url", "http://localhost:" + port + "/x")).retrieve().body(Map.class);
        UUID id = UUID.fromString((String) endpoint.get("id"));
        String shown = (String) endpoint.get("secret");

        String stored = owner.sql("SELECT secret FROM webhook_endpoints WHERE id = :id").param("id", id).query(String.class).single();
        assertThat(stored).startsWith("enc1:").doesNotContain(shown);

        owner.sql("UPDATE webhook_endpoints SET secret = 'whsec_old_plain_value' WHERE id = :id").param("id", id).update();
        assertThat(webhookSecrets.encryptPlaintextSecrets()).isPositive();

        assertThat(owner.sql("SELECT secret FROM webhook_endpoints WHERE id = :id").param("id", id).query(String.class).single())
                .startsWith("enc1:").doesNotContain("whsec_old_plain_value");
        assertThat(webhookSecrets.encryptPlaintextSecrets()).isZero();
    }

    private String code() {
        return Totp.code((String) created.get("totpSecret"), Totp.currentStep());
    }

    private String wrongCode() {
        String secret = (String) created.get("totpSecret");
        long step = Totp.currentStep();
        for (int candidate = 0; ; candidate++) {
            String code = String.format("%06d", candidate);
            if (!code.equals(Totp.code(secret, step - 1)) && !code.equals(Totp.code(secret, step)) && !code.equals(Totp.code(secret, step + 1))
                    && !code.equals(Totp.code(secret, step + 2))) {
                return code;
            }
        }
    }

    /** The password step on the shared login page. Null when the password does not open an administrator account. */
    private String challenge(String password) {
        return client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() != 200) {
                        return null;
                    }
                    Map<String, Object> body = new tools.jackson.databind.json.JsonMapper().readValue(response.getBody().readAllBytes(), Map.class);
                    return (String) body.get("adminChallengeToken");
                });
    }

    private int complete(String challenge, String code) {
        return TestMerchant.statusOf(client.post().uri("/api/v1/auth/login/admin").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("challengeToken", challenge, "code", code)));
    }

    private String login(String code) {
        Map<String, Object> response = client.post().uri("/api/v1/auth/login/admin").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("challengeToken", challenge(PASSWORD), "code", code)).retrieve().body(Map.class);
        return (String) response.get("accessToken");
    }

    private int loginStatus(String password, String code) {
        String challenge = challenge(password);
        return challenge == null ? 401 : complete(challenge, code);
    }

    private int status(String path, String bearer) {
        return client.get().uri(path).header("Authorization", "Bearer " + bearer)
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
