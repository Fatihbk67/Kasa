package com.kasa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.kasa.security.Totp;
import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;

@Import(AccountSecurityApiTest.MailConfig.class)
class AccountSecurityApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final List<String[]> MAILS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class MailConfig {
        @Bean
        @Primary
        Mailer recordingMailer() {
            return (to, subject, body) -> MAILS.add(new String[] {to, body});
        }
    }

    private record Response(int status, Map<String, Object> body) {
    }

    @Value("${local.server.port}")
    int port;

    RestClient client;
    String email;
    String token;
    UUID userId;

    @BeforeEach
    void register() {
        client = RestClient.create("http://localhost:" + port);
        email = "guvenlik-" + UUID.randomUUID() + "@example.com";
        token = (String) send("/api/v1/auth/register", null, Map.of("businessName", "Shop", "email", email, "password", PASSWORD)).body().get("accessToken");
        userId = owner.sql("SELECT id FROM users WHERE email = :email").param("email", email).query(UUID.class).single();
    }

    @Test
    void withoutTheSecondStepTheAppOnlyAsksForAPassword() {
        Map<String, Object> login = login(PASSWORD).body();

        assertThat(login).containsEntry("twoFactorRequired", false);
        assertThat(login.get("accessToken")).isNotNull();
    }

    @Test
    void afterEnablingALoginNeedsTheCode() {
        String secret = enable().secret();

        Map<String, Object> first = login(PASSWORD).body();
        assertThat(first).containsEntry("twoFactorRequired", true);
        assertThat(first.get("accessToken")).isNull();

        Response second = complete(first, freshCode(secret));
        assertThat(second.status()).isEqualTo(200);
        assertThat(status("/api/v1/me", (String) second.body().get("accessToken"))).isEqualTo(200);
    }

    @Test
    void theChallengeTokenOpensNothing() {
        enable();
        String challenge = (String) login(PASSWORD).body().get("challengeToken");

        assertThat(status("/api/v1/me", challenge)).isEqualTo(401);
    }

    @Test
    void aWrongPasswordNeverReachesTheSecondStep() {
        enable();

        Response response = login("wrong-password-here");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).doesNotContainKey("challengeToken");
    }

    @Test
    void theSetupNeedsThePassword() {
        assertThat(send("/api/v1/settings/two-factor/setup", token, Map.of("password", "wrong-password-here")).status()).isEqualTo(422);
    }

    @Test
    void aWrongCodeDoesNotTurnItOn() {
        Map<String, Object> setup = send("/api/v1/settings/two-factor/setup", token, Map.of("password", PASSWORD)).body();

        assertThat(send("/api/v1/settings/two-factor/enable", token, Map.of("code", wrongCode((String) setup.get("secret")))).status()).isEqualTo(422);

        assertThat(login(PASSWORD).body()).containsEntry("twoFactorRequired", false);
    }

    @Test
    void theSecretIsEncryptedAtRest() {
        String secret = enable().secret();

        String stored = owner.sql("SELECT totp_secret FROM users WHERE id = :id").param("id", userId).query(String.class).single();

        assertThat(stored).startsWith("enc1:").doesNotContain(secret);
    }

    @Test
    void enablingEndsOlderSessions() {
        Enabled enabled = enable();

        assertThat(status("/api/v1/me", token)).isEqualTo(401);
        assertThat(status("/api/v1/me", enabled.token())).isEqualTo(200);
    }

    @Test
    void aCodeCannotBeUsedTwice() {
        String secret = enable().secret();
        String code = freshCode(secret);
        assertThat(complete(login(PASSWORD).body(), code).status()).isEqualTo(200);

        assertThat(complete(login(PASSWORD).body(), code).status()).isEqualTo(401);
    }

    @Test
    void sameCodeInManyRequestsAtOnceLetsOneIn() throws Exception {
        String secret = enable().secret();
        String code = freshCode(secret);
        List<Map<String, Object>> challenges = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            challenges.add(login(PASSWORD).body());
        }

        List<Integer> results = Concurrency.run(4, index -> () -> complete(challenges.get(index), code).status());

        assertThat(results.stream().filter(status -> status == 200)).hasSize(1);
    }

    @Test
    void aChallengeWorksOnce() {
        String secret = enable().secret();
        Map<String, Object> challenge = login(PASSWORD).body();
        assertThat(complete(challenge, freshCode(secret)).status()).isEqualTo(200);
        allowCodesAgain();

        assertThat(complete(challenge, freshCode(secret)).status()).isEqualTo(401);
    }

    @Test
    void aTypoDoesNotBurnTheChallenge() {
        String secret = enable().secret();
        Map<String, Object> challenge = login(PASSWORD).body();

        assertThat(complete(challenge, wrongCode(secret)).status()).isEqualTo(401);
        assertThat(complete(challenge, freshCode(secret)).status()).isEqualTo(200);
    }

    @Test
    void aMemberRemovedDuringTheSecondStepCannotFinishIt() {
        String secret = enable().secret();
        Map<String, Object> challenge = login(PASSWORD).body();
        owner.sql("UPDATE users SET disabled_at = now() WHERE id = :id").param("id", userId).update();

        assertThat(complete(challenge, freshCode(secret)).status()).isEqualTo(401);
    }

    @Test
    void anExpiredChallengeIsRefused() {
        String secret = enable().secret();
        Map<String, Object> challenge = login(PASSWORD).body();
        owner.sql("UPDATE login_challenges SET expires_at = now() - interval '1 second' WHERE user_id = :id").param("id", userId).update();

        assertThat(complete(challenge, freshCode(secret)).status()).isEqualTo(401);
    }

    @Test
    void guessingLocksTheAccountEvenWithANewChallengeAndEvenWithTheRightCode() {
        String secret = enable().secret();
        String wrong = wrongCode(secret);
        Map<String, Object> challenge = login(PASSWORD).body();
        for (int i = 0; i < 9; i++) {
            assertThat(complete(challenge, wrong).status()).isEqualTo(401);
        }
        assertThat(complete(challenge, wrong).status()).isEqualTo(429);

        assertThat(complete(challenge, freshCode(secret)).status()).isEqualTo(429);
        assertThat(complete(login(PASSWORD).body(), freshCode(secret)).status()).isEqualTo(429);
        assertThat(owner.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'TWO_FACTOR_LOCKED' AND target_id = :id")
                .param("id", userId.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theLockEnds() {
        String secret = enable().secret();
        owner.sql("UPDATE users SET totp_locked_until = now() - interval '1 second' WHERE id = :id").param("id", userId).update();

        assertThat(complete(login(PASSWORD).body(), freshCode(secret)).status()).isEqualTo(200);
    }

    @Test
    void aRecoveryCodeGetsInOnceOnly() {
        Enabled enabled = enable();
        String recovery = enabled.recoveryCodes().getFirst();

        Response first = complete(login(PASSWORD).body(), recovery);
        assertThat(first.status()).isEqualTo(200);
        assertThat(complete(login(PASSWORD).body(), recovery).status()).isEqualTo(401);

        Map<String, Object> status = get("/api/v1/settings/two-factor", (String) first.body().get("accessToken"));
        assertThat(((Number) status.get("recoveryCodesLeft")).intValue()).isEqualTo(9);
    }

    @Test
    void recoveryCodesAreStoredOnlyAsHashes() {
        Enabled enabled = enable();

        List<String> stored = owner.sql("SELECT code_hash FROM recovery_codes WHERE user_id = :id").param("id", userId).query(String.class).list();

        assertThat(stored).hasSize(10).doesNotContainAnyElementsOf(enabled.recoveryCodes());
    }

    @Test
    void anotherUsersRecoveryCodeDoesNotWork() {
        Enabled mine = enable();
        String otherEmail = "baska-" + UUID.randomUUID() + "@example.com";
        String otherToken = (String) send("/api/v1/auth/register", null, Map.of("businessName", "Other", "email", otherEmail, "password", PASSWORD)).body().get("accessToken");
        Map<String, Object> setup = send("/api/v1/settings/two-factor/setup", otherToken, Map.of("password", PASSWORD)).body();
        send("/api/v1/settings/two-factor/enable", otherToken, Map.of("code", Totp.code((String) setup.get("secret"), Totp.currentStep())));
        Map<String, Object> otherChallenge = send("/api/v1/auth/login", null, Map.of("email", otherEmail, "password", PASSWORD)).body();

        assertThat(complete(otherChallenge, mine.recoveryCodes().getFirst()).status()).isEqualTo(401);
    }

    @Test
    void disablingNeedsBothFactors() {
        Enabled enabled = enable();
        allowCodesAgain();
        String code = Totp.code(enabled.secret(), Totp.currentStep());

        assertThat(send("/api/v1/settings/two-factor/disable", enabled.token(), Map.of("password", "wrong-password-here", "code", code)).status()).isEqualTo(422);
        assertThat(send("/api/v1/settings/two-factor/disable", enabled.token(), Map.of("password", PASSWORD, "code", wrongCode(enabled.secret()))).status()).isEqualTo(422);

        Response disabled = send("/api/v1/settings/two-factor/disable", enabled.token(), Map.of("password", PASSWORD, "code", code));
        assertThat(disabled.status()).isEqualTo(200);
        assertThat(login(PASSWORD).body()).containsEntry("twoFactorRequired", false);
        assertThat(status("/api/v1/me", enabled.token())).isEqualTo(401);
    }

    @Test
    void anApiKeyCannotTouchTwoFactorSettings() {
        Map<String, Object> key = send("/api/v1/api-keys", token, Map.of("name", "server")).body();

        Integer status = client.post().uri("/api/v1/settings/two-factor/setup").header("X-Api-Key", (String) key.get("key"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("password", PASSWORD))
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(403);
    }

    @Test
    void theAuditLogRecordsTheStepsAndHoldsNoSecrets() {
        Enabled enabled = enable();
        complete(login(PASSWORD).body(), freshCode(enabled.secret()));

        List<String> actions = owner.sql("SELECT action FROM audit_log WHERE target_id = :id").param("id", userId.toString()).query(String.class).list();
        assertThat(actions).contains("TWO_FACTOR_ENABLED", "LOGIN_PASSWORD_ACCEPTED", "LOGIN_SUCCEEDED");
    }

    @Test
    void aResetLinkChangesThePasswordAndEndsEverySession() {
        send("/api/v1/auth/password-reset/request", null, Map.of("email", email));
        String reset = resetTokenFor(email);

        assertThat(send("/api/v1/auth/password-reset/confirm", null, Map.of("token", reset, "newPassword", "a-brand-new-password")).status()).isEqualTo(204);

        assertThat(status("/api/v1/me", token)).isEqualTo(401);
        assertThat(login(PASSWORD).status()).isEqualTo(401);
        assertThat(login("a-brand-new-password").status()).isEqualTo(200);
    }

    @Test
    void anUnknownEmailGetsTheSameAnswerAndNoMail() {
        int before = MAILS.size();

        assertThat(send("/api/v1/auth/password-reset/request", null, Map.of("email", "kimse-" + UUID.randomUUID() + "@example.com")).status()).isEqualTo(202);
        assertThat(send("/api/v1/auth/password-reset/request", null, Map.of("email", email)).status()).isEqualTo(202);

        assertThat(MAILS.size()).isEqualTo(before + 1);
    }

    @Test
    void aResetLinkWorksOnce() {
        send("/api/v1/auth/password-reset/request", null, Map.of("email", email));
        String reset = resetTokenFor(email);
        send("/api/v1/auth/password-reset/confirm", null, Map.of("token", reset, "newPassword", "a-brand-new-password"));

        assertThat(send("/api/v1/auth/password-reset/confirm", null, Map.of("token", reset, "newPassword", "yet-another-password")).status()).isEqualTo(410);
    }

    @Test
    void manySimultaneousUsesOfALinkLetOneThrough() throws Exception {
        send("/api/v1/auth/password-reset/request", null, Map.of("email", email));
        String reset = resetTokenFor(email);

        List<Integer> results = Concurrency.run(6, index -> () ->
                send("/api/v1/auth/password-reset/confirm", null, Map.of("token", reset, "newPassword", "a-brand-new-password-" + index)).status());

        assertThat(results.stream().filter(status -> status == 204)).hasSize(1);
    }

    @Test
    void aNewerLinkCancelsTheOlderOne() {
        send("/api/v1/auth/password-reset/request", null, Map.of("email", email));
        String older = resetTokenFor(email);
        send("/api/v1/auth/password-reset/request", null, Map.of("email", email));
        String newer = resetTokenFor(email);

        assertThat(send("/api/v1/auth/password-reset/confirm", null, Map.of("token", older, "newPassword", "a-brand-new-password")).status()).isEqualTo(410);
        assertThat(send("/api/v1/auth/password-reset/confirm", null, Map.of("token", newer, "newPassword", "a-brand-new-password")).status()).isEqualTo(204);
    }

    @Test
    void anExpiredLinkIsRefused() {
        send("/api/v1/auth/password-reset/request", null, Map.of("email", email));
        String reset = resetTokenFor(email);
        owner.sql("UPDATE password_reset_tokens SET expires_at = now() - interval '1 second' WHERE user_id = :id").param("id", userId).update();

        assertThat(send("/api/v1/auth/password-reset/confirm", null, Map.of("token", reset, "newPassword", "a-brand-new-password")).status()).isEqualTo(410);
    }

    @Test
    void theLinkIsStoredOnlyAsAHashAndAMaliciousHostHeaderCannotRedirectIt() {
        client.post().uri("/api/v1/auth/password-reset/request").header("Host", "saldirgan.example").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email)).exchange((request, response) -> response.getStatusCode().value());
        String body = lastMailTo(email);

        assertThat(body).doesNotContain("saldirgan.example").contains("http://localhost:8080/reset-password/");
        String reset = extractToken(body);
        assertThat(owner.sql("SELECT COUNT(*) FROM password_reset_tokens WHERE token_hash = :token").param("token", reset).query(Long.class).single()).isZero();
    }

    @Test
    void aResetDoesNotRemoveTheSecondFactor() {
        String secret = enable().secret();
        send("/api/v1/auth/password-reset/request", null, Map.of("email", email));
        send("/api/v1/auth/password-reset/confirm", null, Map.of("token", resetTokenFor(email), "newPassword", "a-brand-new-password"));

        Map<String, Object> first = login("a-brand-new-password").body();

        assertThat(first).containsEntry("twoFactorRequired", true);
        assertThat(complete(first, freshCode(secret)).status()).isEqualTo(200);
    }

    @Test
    void aResetCancelsAChallengeStillWaiting() {
        String secret = enable().secret();
        Map<String, Object> waiting = login(PASSWORD).body();
        send("/api/v1/auth/password-reset/request", null, Map.of("email", email));
        send("/api/v1/auth/password-reset/confirm", null, Map.of("token", resetTokenFor(email), "newPassword", "a-brand-new-password"));

        assertThat(complete(waiting, freshCode(secret)).status()).isEqualTo(401);
    }

    @Test
    void aRemovedMemberCannotUseAResetLink() {
        send("/api/v1/auth/password-reset/request", null, Map.of("email", email));
        String reset = resetTokenFor(email);
        owner.sql("UPDATE users SET disabled_at = now() WHERE id = :id").param("id", userId).update();

        send("/api/v1/auth/password-reset/confirm", null, Map.of("token", reset, "newPassword", "a-brand-new-password"));

        assertThat(login("a-brand-new-password").status()).isEqualTo(401);
    }

    private record Enabled(String secret, List<String> recoveryCodes, String token) {
    }

    private Enabled enable() {
        Map<String, Object> setup = send("/api/v1/settings/two-factor/setup", token, Map.of("password", PASSWORD)).body();
        String secret = (String) setup.get("secret");
        Map<String, Object> enabled = send("/api/v1/settings/two-factor/enable", token, Map.of("code", Totp.code(secret, Totp.currentStep()))).body();
        return new Enabled(secret, (List<String>) enabled.get("recoveryCodes"), (String) enabled.get("accessToken"));
    }

    /** The current step was spent turning the feature on, and the test would otherwise wait for the next one. */
    private String freshCode(String secret) {
        allowCodesAgain();
        return Totp.code(secret, Totp.currentStep());
    }

    private void allowCodesAgain() {
        owner.sql("UPDATE users SET totp_last_step = 0 WHERE id = :id").param("id", userId).update();
    }

    private static String wrongCode(String secret) {
        long step = Totp.currentStep();
        for (int candidate = 0; ; candidate++) {
            String code = String.format("%06d", candidate);
            if (!code.equals(Totp.code(secret, step - 1)) && !code.equals(Totp.code(secret, step)) && !code.equals(Totp.code(secret, step + 1))
                    && !code.equals(Totp.code(secret, step + 2))) {
                return code;
            }
        }
    }

    private Response login(String password) {
        return send("/api/v1/auth/login", null, Map.of("email", email, "password", password));
    }

    private Response complete(Map<String, Object> challenge, String code) {
        return send("/api/v1/auth/login/two-factor", null, Map.of("challengeToken", challenge.get("challengeToken"), "code", code));
    }

    private Response send(String path, String bearer, Object body) {
        var request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return request.exchange((sent, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            Map<String, Object> parsed = bytes.length == 0 ? new HashMap<>() : new tools.jackson.databind.json.JsonMapper().readValue(bytes, Map.class);
            return new Response(response.getStatusCode().value(), parsed);
        });
    }

    private Map<String, Object> get(String path, String bearer) {
        return client.get().uri(path).header("Authorization", "Bearer " + bearer).retrieve().body(Map.class);
    }

    private int status(String path, String bearer) {
        return client.get().uri(path).header("Authorization", "Bearer " + bearer)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private String resetTokenFor(String to) {
        return extractToken(lastMailTo(to));
    }

    private static String lastMailTo(String to) {
        return MAILS.stream().filter(mail -> mail[0].equals(to)).reduce((a, b) -> b).orElseThrow()[1];
    }

    private static String extractToken(String body) {
        Matcher matcher = Pattern.compile("/reset-password/([0-9a-f]+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
