package com.kasa.bank;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Stands in for a real bank. The card token decides how the bank behaves, so every failure mode can be
 * reproduced on demand. State lives in memory, so a restart loses the bank's records.
 */
@Component
public class SimulatedBank implements BankGateway {

    public enum PayoutBehavior {
        APPROVE,
        REJECT,
        TIMEOUT_PROCESSED,
        TIMEOUT_LOST
    }

    private record Authorization(String cardToken, String currency) {
    }

    private record StatementLine(String reference, String type, long amount, String currency, Instant bookedAt) {
    }

    private final Map<UUID, BankResponse> processed = new ConcurrentHashMap<>();
    private final Map<String, Authorization> authorizations = new ConcurrentHashMap<>();
    private final Map<UUID, PayoutBehavior> payoutBehaviors = new ConcurrentHashMap<>();
    private final Map<LocalDate, List<StatementLine>> statements = new ConcurrentHashMap<>();
    private final Map<LocalDate, String> malformedStatements = new ConcurrentHashMap<>();

    @Override
    public BankResponse authorize(UUID operationId, String cardToken, long amount, String currency) {
        return switch (cardToken) {
            case "tok_success", "tok_capture_declined", "tok_capture_timeout", "tok_refund_timeout" ->
                    process(operationId, () -> approveAuthorization(cardToken, currency));
            case "tok_declined" -> process(operationId, () -> BankResponse.declined("card_declined"));
            case "tok_insufficient_funds" -> process(operationId, () -> BankResponse.declined("insufficient_funds"));
            case "tok_timeout_processed" -> {
                process(operationId, () -> approveAuthorization(cardToken, currency));
                throw new BankTimeoutException();
            }
            case "tok_timeout_lost" -> throw new BankTimeoutException();
            default -> process(operationId, () -> BankResponse.declined("invalid_token"));
        };
    }

    @Override
    public BankResponse capture(UUID operationId, String authorizationReference, long amount) {
        Authorization authorization = authorizations.get(authorizationReference);
        return switch (authorization.cardToken()) {
            case "tok_capture_declined" -> process(operationId, () -> BankResponse.declined("capture_rejected"));
            case "tok_capture_timeout" -> {
                process(operationId, () -> book("CAPTURE", amount, authorization.currency()));
                throw new BankTimeoutException();
            }
            default -> process(operationId, () -> book("CAPTURE", amount, authorization.currency()));
        };
    }

    @Override
    public BankResponse voidAuthorization(UUID operationId, String authorizationReference) {
        return process(operationId, SimulatedBank::approve);
    }

    @Override
    public BankResponse refund(UUID operationId, String authorizationReference, long amount) {
        Authorization authorization = authorizations.get(authorizationReference);
        BankResponse response = process(operationId, () -> book("REFUND", amount, authorization.currency()));
        if (authorization.cardToken().equals("tok_refund_timeout")) {
            throw new BankTimeoutException();
        }
        return response;
    }

    @Override
    public BankResponse payout(UUID payoutId, UUID beneficiaryTenantId, long amount, String currency) {
        return switch (payoutBehaviors.getOrDefault(beneficiaryTenantId, PayoutBehavior.APPROVE)) {
            case APPROVE -> process(payoutId, () -> book("PAYOUT", amount, currency));
            case REJECT -> process(payoutId, () -> BankResponse.declined("account_closed"));
            case TIMEOUT_PROCESSED -> {
                process(payoutId, () -> book("PAYOUT", amount, currency));
                throw new BankTimeoutException();
            }
            case TIMEOUT_LOST -> throw new BankTimeoutException();
        };
    }

    @Override
    public Optional<BankResponse> lookup(UUID operationId) {
        return Optional.ofNullable(processed.get(operationId));
    }

    @Override
    public String downloadStatement(LocalDate date) {
        String malformed = malformedStatements.get(date);
        if (malformed != null) {
            return malformed;
        }
        String lines = statements.getOrDefault(date, List.of()).stream()
                .map(line -> String.join(",", line.reference(), line.type(), String.valueOf(line.amount()),
                        line.currency(), line.bookedAt().toString()))
                .collect(Collectors.joining("\n"));
        return "reference,type,amount,currency,booked_at\n" + lines;
    }

    public void setPayoutBehavior(UUID tenantId, PayoutBehavior behavior) {
        payoutBehaviors.put(tenantId, behavior);
    }

    // The methods below reproduce errors found in real bank files, so reconciliation can be exercised.

    public void simulateLineMissing(String reference) {
        statements.values().forEach(lines -> lines.removeIf(line -> line.reference().equals(reference)));
    }

    public void simulateAmountChange(String reference, long amount) {
        replaceLine(reference, line -> new StatementLine(reference, line.type(), amount, line.currency(), line.bookedAt()));
    }

    public void simulateCurrencyChange(String reference, String currency) {
        replaceLine(reference, line -> new StatementLine(reference, line.type(), line.amount(), currency, line.bookedAt()));
    }

    public void simulateDuplicateLine(String reference) {
        statements.values().forEach(lines -> lines.stream()
                .filter(line -> line.reference().equals(reference))
                .findFirst()
                .ifPresent(lines::add));
    }

    public void simulateUnknownLine(LocalDate date, String reference, long amount) {
        linesOf(date).add(new StatementLine(reference, "CAPTURE", amount, "TRY", Instant.now()));
    }

    public void simulateBookedOn(String reference, LocalDate date) {
        statements.values().forEach(lines -> lines.stream()
                .filter(line -> line.reference().equals(reference))
                .findFirst()
                .ifPresent(line -> {
                    lines.remove(line);
                    linesOf(date).add(line);
                }));
    }

    public void simulateMalformedStatement(LocalDate date, String content) {
        malformedStatements.put(date, content);
    }

    public void clearMalformedStatement(LocalDate date) {
        malformedStatements.remove(date);
    }

    private void replaceLine(String reference, java.util.function.UnaryOperator<StatementLine> change) {
        statements.values().forEach(lines -> lines.replaceAll(
                line -> line.reference().equals(reference) ? change.apply(line) : line));
    }

    private List<StatementLine> linesOf(LocalDate date) {
        return statements.computeIfAbsent(date, day -> new CopyOnWriteArrayList<>());
    }

    private BankResponse process(UUID operationId, Supplier<BankResponse> outcome) {
        return processed.computeIfAbsent(operationId, id -> outcome.get());
    }

    private BankResponse approveAuthorization(String cardToken, String currency) {
        BankResponse response = approve();
        authorizations.put(response.reference(), new Authorization(cardToken, currency));
        return response;
    }

    private BankResponse book(String type, long amount, String currency) {
        BankResponse response = approve();
        Instant now = Instant.now();
        linesOf(LocalDate.ofInstant(now, ZoneOffset.UTC))
                .add(new StatementLine(response.reference(), type, amount, currency, now));
        return response;
    }

    private static BankResponse approve() {
        return BankResponse.approved("bank_" + UUID.randomUUID());
    }
}
