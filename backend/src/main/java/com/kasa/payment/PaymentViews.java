package com.kasa.payment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** What the API exposes. Bank references and request hashes stay internal. */
public final class PaymentViews {

    private PaymentViews() {
    }

    public record PaymentView(UUID id, long amount, String currency, PaymentStatus status, long capturedAmount,
                              long refundedAmount, long feeAmount, String description, String failureCode,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        static PaymentView of(Payment p) {
            return new PaymentView(p.id(), p.amount(), p.currency(), p.status(), p.capturedAmount(),
                    p.refundedAmount(), p.feeAmount(), p.description(), p.failureCode(), p.createdAt(), p.updatedAt());
        }
    }

    public record OperationView(UUID id, OperationType type, long amount, OperationStatus status, String failureCode,
                                OffsetDateTime createdAt) {

        static OperationView of(PaymentOperation o) {
            return new OperationView(o.id(), o.type(), o.amount(), o.status(), o.failureCode(), o.createdAt());
        }
    }

    public record OperationResult(PaymentView payment, OperationView operation) {
    }

    public record PaymentDetail(PaymentView payment, List<OperationView> operations) {
    }

    public record PaymentPage(List<PaymentView> items, UUID nextCursor) {
    }
}
