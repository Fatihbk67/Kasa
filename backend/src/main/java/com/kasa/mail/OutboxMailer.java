package com.kasa.mail;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kasa.auth.Mailer;
import com.kasa.security.SecretCipher;

/**
 * Queues an email instead of sending it. Inside a transaction it joins it, so the email and the event it reports
 * commit together or not at all; outside one it commits on its own. {@link EmailDispatcher} does the sending.
 */
@Component
class OutboxMailer implements Mailer {

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    OutboxMailer(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    @Transactional
    public void send(String to, String subject, String body) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO email_outbox (id, recipient, subject, body) VALUES (:id, :to, :subject, :body)")
                .param("id", id)
                .param("to", to)
                .param("subject", subject)
                .param("body", cipher.encrypt(context(id), body))
                .update();
    }

    static String context(UUID id) {
        return "email-outbox:" + id;
    }
}
