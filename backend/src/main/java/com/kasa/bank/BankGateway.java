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

    /**
     * Money the bank moved by itself because of a dispute. A real bank announces this in a notification that
     * already carries its reference; here the call stands in for that notification.
     */
    BankResponse disputeMovement(UUID operationId, String type, long amount, String currency);

    /** The card network's decision on a dispute whose evidence is under review. True means the merchant won. */
    boolean decideDispute(UUID disputeId, boolean hasDeliveryProof);

    /** Returns the result of an earlier call, or empty if the bank never received it. */
    Optional<BankResponse> lookup(UUID operationId);

    /** Returns the day's money movements as CSV: reference,type,amount,currency,booked_at. */
    String downloadStatement(LocalDate date);
}
