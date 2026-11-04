package com.kasa.mail;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where mail goes. With the "log" provider nothing leaves the machine, which suits local work and tests; "brevo"
 * sends real email through Brevo's HTTP API.
 */
@ConfigurationProperties("kasa.mail")
public record MailProperties(
        String provider,
        boolean logBody,
        String fromAddress,
        String fromName,
        String brevoApiKey,
        String brevoBaseUrl,
        Duration dispatchInterval,
        int batchSize,
        int maxAttempts,
        Duration retryDelay,
        Duration retention) {
}
