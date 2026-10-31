package com.kasa.payment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kasa.payments")
public record PaymentProperties(int feeBasisPoints, Duration recoveryStaleAfter) {

    public PaymentProperties {
        if (feeBasisPoints < 0 || feeBasisPoints >= 10_000) {
            throw new IllegalArgumentException("kasa.payments.fee-basis-points must be between 0 and 9999");
        }
    }
}
