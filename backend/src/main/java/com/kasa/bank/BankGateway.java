package com.kasa.bank;

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

    /** Returns the result of an earlier call, or empty if the bank never received it. */
    Optional<BankResponse> lookup(UUID operationId);
}
