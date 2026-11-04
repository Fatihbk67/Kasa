package com.kasa.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** The body stays out of the logs unless asked for, because a reset link in a log file is a working password. */
/** Anything but "brevo", a blank value included, keeps mail in the log, so a half-finished setup never stops the site. */
@Component
@ConditionalOnExpression("'${kasa.mail.provider:log}'.trim().toLowerCase() != 'brevo'")
class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    private final boolean logBody;

    LogEmailSender(MailProperties properties) {
        this.logBody = properties.logBody();
    }

    @Override
    public String send(String to, String subject, String text) {
        if (logBody) {
            log.info("Mail to {}: {}\n{}", to, subject, text);
        } else {
            log.info("Mail not sent, no provider is configured: {}", subject);
        }
        return null;
    }
}
