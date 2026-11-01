package com.kasa.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

record Payment(
        UUID id,
        UUID tenantId,
        long amount,
        String currency,
        PaymentStatus status,
        long capturedAmount,
        long refundedAmount,
        long feeAmount,
        long disputedAmount,
        UUID customerId,
        String description,
        String bankReference,
        String failureCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
