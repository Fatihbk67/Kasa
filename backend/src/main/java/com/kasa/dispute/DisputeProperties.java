package com.kasa.dispute;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code lossFee} is in minor units and charged only when the merchant loses. */
@ConfigurationProperties("kasa.disputes")
public record DisputeProperties(long lossFee, Duration responseWindow, Duration reviewTime) {
}
