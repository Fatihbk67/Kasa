package com.kasa.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The body stays out of the logs unless asked for, because a reset link in a log file is a working password. */
@Component
class LogMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LogMailer.class);

    private final boolean logBody;

    LogMailer(@Value("${kasa.mail.log-body}") boolean logBody) {
        this.logBody = logBody;
    }

    @Override
    public void send(String to, String subject, String body) {
        if (logBody) {
            log.info("Mail to {}: {}\n{}", to, subject, body);
        } else {
            log.info("Mail not sent, no provider is configured: {}", subject);
        }
    }
}
