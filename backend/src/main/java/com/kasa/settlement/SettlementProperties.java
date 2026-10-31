package com.kasa.settlement;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kasa.settlement")
public record SettlementProperties(Duration delay, Duration recoveryStaleAfter) {
}
