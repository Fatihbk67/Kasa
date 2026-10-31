package com.kasa.bank;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Stands in for a real bank. The card token decides how the bank behaves, so every failure mode can be
 * reproduced on demand. State lives in memory, so a restart loses the bank's records.
 */
@Component
public class SimulatedBank implements BankGateway {

    private final Map<UUID, BankResponse> processed = new ConcurrentHashMap<>();
    private final Map<String, String> tokenByAuthorization = new ConcurrentHashMap<>();

    @Override
    public BankResponse authorize(UUID operationId, String cardToken, long amount, String currency) {
        return switch (cardToken) {
            case "tok_success", "tok_capture_declined", "tok_capture_timeout", "tok_refund_timeout" ->
                    process(operationId, () -> approveAuthorization(cardToken));
            case "tok_declined" -> process(operationId, () -> BankResponse.declined("card_declined"));
            case "tok_insufficient_funds" -> process(operationId, () -> BankResponse.declined("insufficient_funds"));
            case "tok_timeout_processed" -> {
                process(operationId, () -> approveAuthorization(cardToken));
                throw new BankTimeoutException();
            }
            case "tok_timeout_lost" -> throw new BankTimeoutException();
            default -> process(operationId, () -> BankResponse.declined("invalid_token"));
        };
    }

    @Override
    public BankResponse capture(UUID operationId, String authorizationReference, long amount) {
        return switch (tokenOf(authorizationReference)) {
            case "tok_capture_declined" -> process(operationId, () -> BankResponse.declined("capture_rejected"));
            case "tok_capture_timeout" -> {
                process(operationId, SimulatedBank::approve);
                throw new BankTimeoutException();
            }
            default -> process(operationId, SimulatedBank::approve);
        };
    }

    @Override
    public BankResponse voidAuthorization(UUID operationId, String authorizationReference) {
        return process(operationId, SimulatedBank::approve);
    }

    @Override
    public BankResponse refund(UUID operationId, String authorizationReference, long amount) {
        BankResponse response = process(operationId, SimulatedBank::approve);
        if (tokenOf(authorizationReference).equals("tok_refund_timeout")) {
            throw new BankTimeoutException();
        }
        return response;
    }

    @Override
    public Optional<BankResponse> lookup(UUID operationId) {
        return Optional.ofNullable(processed.get(operationId));
    }

    private BankResponse process(UUID operationId, java.util.function.Supplier<BankResponse> outcome) {
        return processed.computeIfAbsent(operationId, id -> outcome.get());
    }

    private BankResponse approveAuthorization(String cardToken) {
        BankResponse response = approve();
        tokenByAuthorization.put(response.reference(), cardToken);
        return response;
    }

    private String tokenOf(String authorizationReference) {
        return tokenByAuthorization.getOrDefault(authorizationReference, "");
    }

    private static BankResponse approve() {
        return BankResponse.approved("bank_" + UUID.randomUUID());
    }
}
