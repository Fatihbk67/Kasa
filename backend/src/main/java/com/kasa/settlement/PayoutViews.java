package com.kasa.settlement;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PayoutViews {

    private PayoutViews() {
    }

    public record PayoutView(UUID id, String currency, long amount, String status, LocalDate settlementDate,
                             String failureCode, OffsetDateTime paidAt, OffsetDateTime createdAt) {
    }

    /** {@code netAmount} is what the item adds to the payout: negative for a refund. */
    public record PayoutItemView(UUID paymentId, UUID operationId, String type, long grossAmount, long feeAmount,
                                 long netAmount, boolean released) {
    }

    public record PayoutDetail(PayoutView payout, List<PayoutItemView> items) {
    }
}
