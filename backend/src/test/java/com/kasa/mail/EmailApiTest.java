package com.kasa.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.kasa.auth.AuthService;
import com.kasa.auth.EmailVerificationService;
import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

@Import(EmailApiTest.RecordingSenderConfig.class)
class EmailApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern LINK = Pattern.compile("/verify-email/([0-9a-f]{64})");

    record Delivered(String to, String subject, String text) {
    }

    static final List<Delivered> DELIVERED = new CopyOnWriteArrayList<>();
    /** Set to make the provider fail the next sends, as an outage or a rejected address would. */
    static final AtomicReference<EmailSender.SendFailure> FAILURE = new AtomicReference<>();

    @TestConfiguration
    static class RecordingSenderConfig {
        @Bean
        @Primary
        EmailSender recordingSender() {
            return (to, subject, text) -> {
                EmailSender.SendFailure failure = FAILURE.get();
                if (failure != null) {
                    throw failure;
                }
                DELIVERED.add(new Delivered(to, subject, text));
                return "msg-" + UUID.randomUUID();
            };
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    EmailDispatcher dispatcher;

    @Autowired
    EmailVerificationService verification;

    @Autowired
    AuthService authService;

    @Autowired
    com.kasa.auth.Mailer mailer;

    RestClient client;
    String email;
    String token;

    @BeforeEach
    void register() {
        FAILURE.set(null);
        // Rows from other tests share the queue; they are sent now so each test counts only its own.
        drain();
        DELIVERED.clear();
        client = RestClient.create("http://localhost:" + port);
        email = "posta-" + UUID.randomUUID() + "@example.com";
        token = (String) client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("businessName", "Posta", "email", email, "password", PASSWORD)).retrieve().body(Map.class).get("accessToken");
    }

    @AfterEach
    void reset() {
        FAILURE.set(null);
        ReflectionTestUtils.setField(verification, "required", false);
        ReflectionTestUtils.setField(authService, "requireReview", false);
    }

    @Test
    void signingUpQueuesAVerificationEmailWhoseLinkIsNotStoredReadably() {
        Map<String, Object> queued = owner.sql("SELECT status, body FROM email_outbox WHERE recipient = :to").param("to", email)
                .query((rs, rowNum) -> Map.<String, Object>of("status", rs.getString("status"), "body", rs.getString("body"))).single();

        assertThat(queued).containsEntry("status", "PENDING");
        assertThat((String) queued.get("body")).startsWith("enc1:").doesNotContain("verify-email");
        assertThat(me()).containsEntry("emailVerified", false);
    }

    @Test
    void theLinkVerifiesTheAddressOnce() {
        String link = sendAndReadLink();

        assertThat(verify(link)).isEqualTo(204);
        assertThat(me()).containsEntry("emailVerified", true);
        assertThat(verify(link)).isEqualTo(410);
        assertThat(owner.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'EMAIL_VERIFIED' AND actor_label = :e").param("e", email)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aLinkClickedInManyTabsAtOnceCountsOnce() throws Exception {
        String link = sendAndReadLink();

        List<Integer> results = Concurrency.run(8, index -> () -> verify(link));

        assertThat(results).containsOnlyOnce(204);
        assertThat(results.stream().filter(code -> code == 410).count()).isEqualTo(7);
        assertThat(owner.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'EMAIL_VERIFIED' AND actor_label = :e").param("e", email)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aSentEmailLeavesOnlyItsEnvelopeBehind() {
        sendAndReadLink();

        Map<String, Object> sent = owner.sql("SELECT status, body, provider_id, sent_at FROM email_outbox WHERE recipient = :to")
                .param("to", email)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("status", rs.getString("status"));
                    row.put("body", rs.getString("body"));
                    row.put("provider", rs.getString("provider_id"));
                    row.put("sentAt", rs.getObject("sent_at"));
                    return row;
                }).single();
        assertThat(sent).containsEntry("status", "SENT").containsEntry("body", null);
        assertThat((String) sent.get("provider")).startsWith("msg-");
        assertThat(sent.get("sentAt")).isNotNull();
    }

    @Test
    void aNewLinkReplacesTheOld() {
        String first = sendAndReadLink();

        assertThat(TestMerchant.statusOf(client.post().uri("/api/v1/settings/email-verification").header("Authorization", "Bearer " + token)))
                .isEqualTo(202);
        String second = sendAndReadLink();

        assertThat(second).isNotEqualTo(first);
        assertThat(verify(first)).isEqualTo(410);
        assertThat(verify(second)).isEqualTo(204);
        assertThat(TestMerchant.statusOf(client.post().uri("/api/v1/settings/email-verification").header("Authorization", "Bearer " + token)))
                .isEqualTo(409);
    }

    @Test
    void anOutageDelaysTheEmailAndALaterRunDeliversIt() {
        FAILURE.set(new EmailSender.SendFailure("Brevo answered 503", false));

        assertThat(dispatcher.dispatchDue()).isZero();

        Map<String, Object> waiting = row();
        assertThat(waiting).containsEntry("status", "PENDING").containsEntry("attempts", 1).containsEntry("lastError", "Brevo answered 503");
        assertThat((Boolean) waiting.get("later")).isTrue();
        assertThat(dispatcher.dispatchDue()).as("not retried before its time").isZero();

        FAILURE.set(null);
        owner.sql("UPDATE email_outbox SET next_attempt_at = now() WHERE recipient = :to").param("to", email).update();
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(row()).containsEntry("status", "SENT").containsEntry("attempts", 2);
    }

    @Test
    void aRejectedAddressIsNotRetriedAndItsBodyIsErased() {
        FAILURE.set(new EmailSender.SendFailure("Brevo answered 400: invalid email", true));

        dispatcher.dispatchDue();

        assertThat(row()).containsEntry("status", "FAILED").containsEntry("attempts", 1).containsEntry("body", null);
    }

    @Test
    void theQueueGivesUpAfterTheLastAttempt() {
        FAILURE.set(new EmailSender.SendFailure("timeout", false));
        for (int attempt = 1; attempt <= 6; attempt++) {
            owner.sql("UPDATE email_outbox SET next_attempt_at = now() WHERE recipient = :to").param("to", email).update();
            dispatcher.dispatchDue();
        }

        assertThat(row()).containsEntry("status", "FAILED").containsEntry("attempts", 6);
    }

    @Test
    void parallelDispatchersSendEachEmailOnce() throws Exception {
        for (int i = 0; i < 30; i++) {
            mailer.send("toplu-" + i + "-" + email, "Toplu", "Merhaba " + i);
        }

        List<Integer> sent = Concurrency.run(4, index -> () -> dispatcher.dispatchDue() + dispatcher.dispatchDue());

        assertThat(sent.stream().mapToInt(Integer::intValue).sum()).isEqualTo(31);
        assertThat(DELIVERED).hasSize(31);
        assertThat(DELIVERED.stream().map(Delivered::to).distinct().count()).as("nobody received an email twice").isEqualTo(31);
    }

    @Test
    void aFailedSignUpQueuesNothing() {
        int status = TestMerchant.statusOf(client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("businessName", "Tekrar", "email", email, "password", PASSWORD)));

        assertThat(status).isEqualTo(409);
        assertThat(owner.sql("SELECT COUNT(*) FROM email_outbox WHERE recipient = :to").param("to", email).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void anUnverifiedOwnerCannotApplyToTakePayments() {
        ReflectionTestUtils.setField(verification, "required", true);
        ReflectionTestUtils.setField(authService, "requireReview", true);
        String pendingEmail = "basvuru-" + UUID.randomUUID() + "@example.com";
        String pendingToken = (String) client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("businessName", "Fırın", "email", pendingEmail, "password", PASSWORD)).retrieve().body(Map.class).get("accessToken");

        assertThat(apply(pendingToken)).isEqualTo(403);

        drain();
        String link = DELIVERED.stream().filter(mail -> mail.to().equals(pendingEmail)).map(Delivered::text).map(EmailApiTest::tokenIn)
                .findFirst().orElseThrow();
        verify(link);
        assertThat(apply(pendingToken)).isEqualTo(200);
    }

    @Test
    void anInvitedMemberConfirmsTheirAddressToo() {
        Map<String, Object> invitation = client.post().uri("/api/v1/team/invitations").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("email", "uye-" + UUID.randomUUID() + "@example.com", "role", "VIEWER"))
                .retrieve().body(Map.class);
        String member = (String) client.post().uri("/api/v1/public/invitations/" + invitation.get("token") + "/accept")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("password", PASSWORD)).retrieve().body(Map.class).get("accessToken");

        assertThat(client.get().uri("/api/v1/me").header("Authorization", "Bearer " + member).retrieve().body(Map.class))
                .containsEntry("emailVerified", false);
        assertThat(owner.sql("SELECT COUNT(*) FROM email_outbox WHERE recipient = :to").param("to", invitation.get("email")).query(Long.class).single())
                .isEqualTo(1);
    }

    private String sendAndReadLink() {
        drain();
        Delivered mail = DELIVERED.stream().filter(sent -> sent.to().equals(email)).reduce((first, last) -> last).orElseThrow();
        assertThat(mail.subject()).contains("doğrulayın");
        return tokenIn(mail.text());
    }

    /** A batch takes only some of the queue, and the whole suite fills it, so this sends until nothing is due. */
    private void drain() {
        while (dispatcher.dispatchDue() > 0) {
            // keep going
        }
    }

    private static String tokenIn(String text) {
        Matcher matcher = LINK.matcher(text);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private int verify(String link) {
        return TestMerchant.statusOf(client.post().uri("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", link)));
    }

    private int apply(String bearer) {
        Map<String, Object> application = new java.util.HashMap<>(Map.of("businessType", "SOLE_PROPRIETOR", "legalName", "Ali Usta",
                "taxId", "10000000146", "taxOffice", "Beşiktaş", "sector", "FOOD", "phone", "+90 212 555 01 02",
                "address", "Çarşı Sk. No: 4", "city", "İstanbul", "iban", "TR330006100519786457841326"));
        return TestMerchant.statusOf(client.put().uri("/api/v1/settings/business-profile").header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).body(application));
    }

    private Map<String, Object> me() {
        return client.get().uri("/api/v1/me").header("Authorization", "Bearer " + token).retrieve().body(Map.class);
    }

    private Map<String, Object> row() {
        return owner.sql("""
                        SELECT status, attempts, last_error, body, next_attempt_at > now() AS later
                        FROM email_outbox WHERE recipient = :to
                        """)
                .param("to", email)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("status", rs.getString("status"));
                    row.put("attempts", rs.getInt("attempts"));
                    row.put("lastError", rs.getString("last_error"));
                    row.put("body", rs.getString("body"));
                    row.put("later", rs.getBoolean("later"));
                    return row;
                }).single();
    }
}
