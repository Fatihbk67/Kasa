package com.kasa.payment;

public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    DECLINED,
    FAILED,
    VOIDED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED
}
