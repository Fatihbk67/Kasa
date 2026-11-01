package com.kasa.billing;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code retryDelays} is the wait after each failed attempt, so its size decides how many retries there are.
 * {@code lease} must outlast any charge, so a second job never picks up an attempt that is still running.
 */
@ConfigurationProperties("kasa.billing")
public record BillingProperties(List<Duration> retryDelays, Duration lease) {
}
