package com.kasa.bank;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The acquiring bank. Every call carries our operation id as an idempotency reference: repeating an id
 * returns the original result. Any call may throw {@link BankTimeoutException}, meaning the outcome is unknown.
 */
public interface BankGateway {

    BankResponse authorize(UUID operationId, String cardToken, long amount, String currency);

    BankResponse capture(UUID operationId, String authorizationReference, long amount);

    BankResponse voidAuthorization(UUID operationId, String authorizationReference);

    BankResponse refund(UUID operationId, String authorizationReference, long amount);

    BankResponse payout(UUID payoutId, UUID beneficiaryTenantId, long amount, String currency);

    /** Returns the result of an earlier call, or empty if the bank never received it. */
    Optional<BankResponse> lookup(UUID operationId);

    /** Returns the day's money movements as CSV: reference,type,amount,currency,booked_at. */
    String downloadStatement(LocalDate date);
}
