package com.kasa.auth;

/** The way out to an email provider. Only a logging version exists so far, since the demo sends no real mail. */
public interface Mailer {

    void send(String to, String subject, String body);
}
