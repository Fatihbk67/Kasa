package com.kasa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** Brevo itself is never called from tests; a local server stands in for it and records what it was sent. */
class BrevoEmailSenderTest {

    HttpServer server;
    final AtomicInteger status = new AtomicInteger(201);
    final AtomicReference<String> reply = new AtomicReference<>("{\"messageId\":\"<abc@smtp-relay.brevo.com>\"}");
    final AtomicReference<String> receivedBody = new AtomicReference<>();
    final AtomicReference<String> receivedKey = new AtomicReference<>();
    final AtomicReference<String> receivedPath = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsTheMessageWithTheKeyAndReturnsBrevosId() {
        String id = sender("test-key").send("ali@example.com", "Kasa: e-posta adresinizi doğrulayın",
                "Doğrulamak için:\nhttps://kasa.example/verify-email/abc");

        assertThat(id).isEqualTo("<abc@smtp-relay.brevo.com>");
        assertThat(receivedPath.get()).isEqualTo("/v3/smtp/email");
        assertThat(receivedKey.get()).isEqualTo("test-key");
        assertThat(receivedBody.get()).contains("\"email\":\"ali@example.com\"").contains("\"email\":\"kasa@example.com\"")
                .contains("\"textContent\"").contains("\"htmlContent\"").contains("Kasa: e-posta adresinizi doğrulayın");
    }

    @Test
    void aRejectedRequestIsPermanentButAnOutageIsNot() {
        status.set(400);
        reply.set("{\"code\":\"invalid_parameter\"}");
        assertThatThrownBy(() -> sender("k").send("x@example.com", "s", "t"))
                .isInstanceOfSatisfying(EmailSender.SendFailure.class, failure -> assertThat(failure.permanent()).isTrue());

        status.set(503);
        assertThatThrownBy(() -> sender("k").send("x@example.com", "s", "t"))
                .isInstanceOfSatisfying(EmailSender.SendFailure.class, failure -> assertThat(failure.permanent()).isFalse());

        status.set(429);
        assertThatThrownBy(() -> sender("k").send("x@example.com", "s", "t"))
                .isInstanceOfSatisfying(EmailSender.SendFailure.class, failure -> assertThat(failure.permanent()).isFalse());
    }

    @Test
    void anUnreachableProviderIsWorthRetrying() {
        server.stop(0);

        assertThatThrownBy(() -> sender("k").send("x@example.com", "s", "t"))
                .isInstanceOfSatisfying(EmailSender.SendFailure.class, failure -> assertThat(failure.permanent()).isFalse());
    }

    @Test
    void refusesToStartWithoutAKeyOrSender() {
        assertThatThrownBy(() -> new BrevoEmailSender(properties("", "kasa@example.com"))).hasMessageContaining("KASA_BREVO_API_KEY");
        assertThatThrownBy(() -> new BrevoEmailSender(properties("key", ""))).hasMessageContaining("KASA_MAIL_FROM");
    }

    @Test
    void theHtmlVersionEscapesTextAndTurnsLinksIntoButtons() {
        String html = EmailTemplate.html("Başlık <b>", "Merhaba <script>alert(1)</script>\nhttps://kasa.example/reset-password/abc");

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;").contains("Başlık &lt;b&gt;")
                .contains("href=\"https://kasa.example/reset-password/abc\"").contains("Bağlantıyı aç");
    }

    private BrevoEmailSender sender(String key) {
        return new BrevoEmailSender(properties(key, "kasa@example.com"));
    }

    private MailProperties properties(String key, String from) {
        return new MailProperties("brevo", false, from, "Kasa", key, "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(5), 20, 6, Duration.ofSeconds(30), Duration.ofDays(30));
    }
}
