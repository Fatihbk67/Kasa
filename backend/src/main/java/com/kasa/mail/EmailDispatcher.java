package com.kasa.mail;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.kasa.security.SecretCipher;

/**
 * Sends queued email. Claiming a batch pushes its next attempt into the future before anything is sent, so a
 * second instance skips those rows and a crash mid-send only delays them. Delivery is at least once: a crash
 * after the provider accepted a message but before it was marked sent repeats that one message.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    /** Long enough for any provider call to finish, so a slow send is never claimed twice. */
    private static final int LEASE_SECONDS = 120;

    private record Queued(UUID id, String recipient, String subject, String body, int attempts) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SecretCipher cipher;
    private final EmailSender sender;
    private final MailProperties properties;

    public EmailDispatcher(JdbcClient jdbc, TransactionTemplate tx, SecretCipher cipher, EmailSender sender, MailProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.cipher = cipher;
        this.sender = sender;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${kasa.mail.dispatch-interval}", initialDelayString = "${kasa.mail.dispatch-interval}")
    void scheduledDispatch() {
        dispatchDue();
        forgetOld();
    }

    /** Returns how many emails were handed to the provider. */
    public int dispatchDue() {
        List<Queued> batch = tx.execute(status -> jdbc.sql("""
                        UPDATE email_outbox
                        SET attempts = attempts + 1, next_attempt_at = now() + make_interval(secs => :lease)
                        WHERE id IN (SELECT id FROM email_outbox
                                     WHERE status = 'PENDING' AND next_attempt_at <= now()
                                     ORDER BY next_attempt_at
                                     LIMIT :limit
                                     FOR UPDATE SKIP LOCKED)
                        RETURNING id, recipient, subject, body, attempts
                        """)
                .param("lease", LEASE_SECONDS)
                .param("limit", properties.batchSize())
                .query((rs, rowNum) -> new Queued(rs.getObject("id", UUID.class), rs.getString("recipient"),
                        rs.getString("subject"), rs.getString("body"), rs.getInt("attempts")))
                .list());
        int sent = 0;
        for (Queued email : batch) {
            try {
                String providerId = sender.send(email.recipient(), email.subject(), cipher.decrypt(OutboxMailer.context(email.id()), email.body()));
                markSent(email.id(), providerId);
                sent++;
            } catch (EmailSender.SendFailure e) {
                markFailed(email, e.getMessage(), e.permanent());
            } catch (RuntimeException e) {
                log.error("Unexpected error sending email {}", email.id(), e);
                markFailed(email, e.getClass().getSimpleName(), false);
            }
        }
        return sent;
    }

    private void markSent(UUID id, String providerId) {
        jdbc.sql("UPDATE email_outbox SET status = 'SENT', sent_at = now(), body = NULL, provider_id = :provider, last_error = NULL WHERE id = :id")
                .param("provider", providerId)
                .param("id", id)
                .update();
    }

    /** Waits twice as long after each failure, so an outage is not met with a flood of retries. */
    private void markFailed(Queued email, String error, boolean permanent) {
        boolean givingUp = permanent || email.attempts() >= properties.maxAttempts();
        if (givingUp) {
            log.warn("Giving up on email {} after {} attempts: {}", email.id(), email.attempts(), error);
        }
        long delay = properties.retryDelay().toSeconds() << Math.min(email.attempts() - 1, 10);
        jdbc.sql("""
                        UPDATE email_outbox
                        SET status = :status, last_error = :error, next_attempt_at = now() + make_interval(secs => :delay),
                            body = CASE WHEN :givingUp THEN NULL ELSE body END
                        WHERE id = :id
                        """)
                .param("status", givingUp ? "FAILED" : "PENDING")
                .param("error", error)
                .param("delay", delay)
                .param("givingUp", givingUp)
                .param("id", email.id())
                .update();
    }

    /** The queue is not an archive: old rows only hold addresses nobody needs any more. */
    void forgetOld() {
        jdbc.sql("DELETE FROM email_outbox WHERE status <> 'PENDING' AND created_at < now() - make_interval(secs => :seconds)")
                .param("seconds", properties.retention().toSeconds())
                .update();
    }
}
