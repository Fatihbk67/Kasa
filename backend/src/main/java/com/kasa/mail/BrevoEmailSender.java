package com.kasa.mail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** Sends through Brevo's transactional email API, over HTTPS, since many hosts block outgoing SMTP ports. */
@Component
@ConditionalOnExpression("'${kasa.mail.provider:log}'.trim().toLowerCase() == 'brevo'")
class BrevoEmailSender implements EmailSender {

    private static final Pattern MESSAGE_ID = Pattern.compile("\"messageId\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BrevoEmailSender.class);

    private final MailProperties properties;
    private final String apiKey;
    private final String fromAddress;

    BrevoEmailSender(MailProperties properties) {
        // Fails at startup rather than at the first password reset, when nobody is watching.
        if (isBlank(properties.brevoApiKey()) || isBlank(properties.fromAddress())) {
            throw new IllegalStateException("KASA_BREVO_API_KEY and KASA_MAIL_FROM must be set to send real email");
        }
        // A key pasted into a dashboard easily picks up a stray space or line break, which Brevo would reject.
        this.properties = properties;
        this.apiKey = properties.brevoApiKey().strip();
        this.fromAddress = properties.fromAddress().strip();
        log.info("Sending email through Brevo as {}", fromAddress);
    }

    @Override
    public String send(String to, String subject, String text) {
        String body = json.writeValueAsString(Map.of(
                "sender", Map.of("name", properties.fromName(), "email", fromAddress),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "textContent", text,
                "htmlContent", EmailTemplate.html(subject, text)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.brevoBaseUrl() + "/v3/smtp/email"))
                .timeout(Duration.ofSeconds(10))
                .header("api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new SendFailure("could not reach Brevo: " + e.getClass().getSimpleName(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SendFailure("interrupted", false);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            Matcher id = MESSAGE_ID.matcher(response.body());
            return id.find() ? id.group(1) : null;
        }
        // A rejected request stays rejected; only throttling and the provider's own errors pass with time.
        boolean permanent = status >= 400 && status < 500 && status != 408 && status != 429;
        throw new SendFailure("Brevo answered " + status + ": " + abbreviate(response.body()), permanent);
    }

    private static String abbreviate(String text) {
        return text == null ? "" : text.length() > 300 ? text.substring(0, 300) : text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
