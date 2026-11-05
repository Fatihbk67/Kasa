package com.kasa.iyzico;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.kasa.bank.BankGateway;
import com.kasa.bank.BankResponse;
import com.kasa.bank.SimulatedBank;

/**
 * Sends each card operation to the network that took the payment: iyzico for payments made on its page, the
 * simulated bank for everything else. The token or reference says which, so payment logic stays unaware of it.
 * Payouts, disputes and statements stay with the simulated bank, which stands for the platform's own bank account.
 */
@Primary
@Component
public class RoutingBankGateway implements BankGateway {

    private final SimulatedBank simulated;
    private final IyzicoGateway iyzico;

    public RoutingBankGateway(SimulatedBank simulated, IyzicoGateway iyzico) {
        this.simulated = simulated;
        this.iyzico = iyzico;
    }

    @Override
    public BankResponse authorize(UUID operationId, String cardToken, long amount, String currency) {
        return IyzicoGateway.handles(cardToken)
                ? iyzico.authorize(operationId, cardToken, amount, currency)
                : simulated.authorize(operationId, cardToken, amount, currency);
    }

    @Override
    public BankResponse capture(UUID operationId, String authorizationReference, long amount) {
        return IyzicoGateway.handles(authorizationReference)
                ? iyzico.capture(operationId, authorizationReference, amount)
                : simulated.capture(operationId, authorizationReference, amount);
    }

    @Override
    public BankResponse voidAuthorization(UUID operationId, String authorizationReference) {
        return IyzicoGateway.handles(authorizationReference)
                ? iyzico.voidAuthorization(operationId, authorizationReference)
                : simulated.voidAuthorization(operationId, authorizationReference);
    }

    @Override
    public BankResponse refund(UUID operationId, String authorizationReference, long amount) {
        return IyzicoGateway.handles(authorizationReference)
                ? iyzico.refund(operationId, authorizationReference, amount)
                : simulated.refund(operationId, authorizationReference, amount);
    }

    @Override
    public BankResponse payout(UUID payoutId, UUID beneficiaryTenantId, long amount, String currency) {
        return simulated.payout(payoutId, beneficiaryTenantId, amount, currency);
    }

    @Override
    public BankResponse disputeMovement(UUID operationId, String type, long amount, String currency) {
        return simulated.disputeMovement(operationId, type, amount, currency);
    }

    @Override
    public boolean decideDispute(UUID disputeId, boolean hasDeliveryProof) {
        return simulated.decideDispute(disputeId, hasDeliveryProof);
    }

    @Override
    public Optional<BankResponse> lookup(UUID operationId) {
        return iyzico.knows(operationId) ? iyzico.lookup(operationId) : simulated.lookup(operationId);
    }

    @Override
    public String downloadStatement(LocalDate date) {
        return simulated.downloadStatement(date);
    }
}
