package com.kasa.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

record PaymentOperation(
        UUID id,
        UUID paymentId,
        OperationType type,
        long amount,
        OperationStatus status,
        String requestHash,
        String failureCode,
        OffsetDateTime createdAt) {
}
