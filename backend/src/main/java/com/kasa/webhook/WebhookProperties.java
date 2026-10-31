package com.kasa.webhook;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kasa.webhooks")
public record WebhookProperties(
        int batchSize,
        int maxAttempts,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration lease,
        Duration baseBackoff,
        Duration maxBackoff,
        boolean allowInsecureTargets) {

    public WebhookProperties {
        if (lease.compareTo(connectTimeout.plus(requestTimeout)) <= 0) {
            throw new IllegalArgumentException("kasa.webhooks.lease must exceed connect-timeout plus request-timeout");
        }
    }
}
