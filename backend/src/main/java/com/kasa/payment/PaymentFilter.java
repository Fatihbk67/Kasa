package com.kasa.payment;

import java.time.LocalDate;
import java.util.UUID;

/** Every field is optional. {@code query} matches an exact payment id or part of the description. */
public record PaymentFilter(PaymentStatus status, String query, LocalDate from, LocalDate to, Long minAmount,
                            Long maxAmount, UUID customerId) {
}
