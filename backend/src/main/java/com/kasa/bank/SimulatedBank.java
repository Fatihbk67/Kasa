package com.kasa.bank;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stands in for a real bank. The card token decides how the bank behaves, so every failure mode can be
 * reproduced on demand. Its records live in their own tables, so a restart of our application does not make
 * the bank forget what it did. Only the test settings stay in memory.
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

    private static final String UNKNOWN_AUTHORIZATION = "unknown_authorization";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Map<UUID, PayoutBehavior> payoutBehaviors = new ConcurrentHashMap<>();
    private final Map<LocalDate, String> malformedStatements = new ConcurrentHashMap<>();

    public SimulatedBank(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

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
        Optional<Authorization> found = findAuthorization(authorizationReference);
        if (found.isEmpty()) {
            return process(operationId, () -> BankResponse.declined(UNKNOWN_AUTHORIZATION));
        }
        Authorization authorization = found.get();
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
        Optional<Authorization> found = findAuthorization(authorizationReference);
        if (found.isEmpty()) {
            return process(operationId, () -> BankResponse.declined(UNKNOWN_AUTHORIZATION));
        }
        Authorization authorization = found.get();
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
    public BankResponse disputeMovement(UUID operationId, String type, long amount, String currency) {
        return process(operationId, () -> book(type, amount, currency));
    }

    /** A predictable rule, so a demo can reach either outcome: proof of delivery wins, anything less loses. */
    @Override
    public boolean decideDispute(UUID disputeId, boolean hasDeliveryProof) {
        return hasDeliveryProof;
    }

    @Override
    public Optional<BankResponse> lookup(UUID operationId) {
        return jdbc.sql("SELECT approved, reference, decline_code FROM sim_bank_operations WHERE operation_id = :id")
                .param("id", operationId)
                .query((rs, rowNum) -> rs.getBoolean("approved")
                        ? BankResponse.approved(rs.getString("reference"))
                        : BankResponse.declined(rs.getString("decline_code")))
                .optional();
    }

    @Override
    public String downloadStatement(LocalDate date) {
        String malformed = malformedStatements.get(date);
        if (malformed != null) {
            return malformed;
        }
        List<StatementLine> rows = jdbc.sql("""
                        SELECT reference, type, amount, currency, booked_at
                        FROM sim_bank_statement_lines
                        WHERE booked_on = :date
                        ORDER BY id
                        """)
                .param("date", date)
                .query((rs, rowNum) -> new StatementLine(
                        rs.getString("reference"),
                        rs.getString("type"),
                        rs.getLong("amount"),
                        rs.getString("currency"),
                        rs.getObject("booked_at", OffsetDateTime.class).toInstant()))
                .list();
        String lines = rows.stream()
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
        jdbc.sql("DELETE FROM sim_bank_statement_lines WHERE reference = :reference")
                .param("reference", reference)
                .update();
    }

    public void simulateAmountChange(String reference, long amount) {
        jdbc.sql("UPDATE sim_bank_statement_lines SET amount = :amount WHERE reference = :reference")
                .param("amount", amount)
                .param("reference", reference)
                .update();
    }

    public void simulateCurrencyChange(String reference, String currency) {
        jdbc.sql("UPDATE sim_bank_statement_lines SET currency = :currency WHERE reference = :reference")
                .param("currency", currency)
                .param("reference", reference)
                .update();
    }

    public void simulateDuplicateLine(String reference) {
        jdbc.sql("""
                        INSERT INTO sim_bank_statement_lines (reference, type, amount, currency, booked_at, booked_on)
                        SELECT reference, type, amount, currency, booked_at, booked_on
                        FROM sim_bank_statement_lines
                        WHERE reference = :reference
                        ORDER BY id
                        LIMIT 1
                        """)
                .param("reference", reference)
                .update();
    }

    public void simulateUnknownLine(LocalDate date, String reference, long amount) {
        insertLine(reference, "CAPTURE", amount, "TRY", Instant.now(), date);
    }

    public void simulateBookedOn(String reference, LocalDate date) {
        jdbc.sql("UPDATE sim_bank_statement_lines SET booked_on = :date WHERE reference = :reference")
                .param("date", date)
                .param("reference", reference)
                .update();
    }

    public void simulateMalformedStatement(LocalDate date, String content) {
        malformedStatements.put(date, content);
    }

    public void clearMalformedStatement(LocalDate date) {
        malformedStatements.remove(date);
    }

    private Optional<Authorization> findAuthorization(String reference) {
        return jdbc.sql("SELECT card_token, currency FROM sim_bank_authorizations WHERE reference = :reference")
                .param("reference", reference)
                .query((rs, rowNum) -> new Authorization(rs.getString("card_token"), rs.getString("currency")))
                .optional();
    }

    /**
     * Repeating an operation id returns the first result and moves no money. The lock makes that hold when two
     * callers arrive together, and the transaction keeps a booked line and its result from being split by a crash.
     */
    private BankResponse process(UUID operationId, Supplier<BankResponse> outcome) {
        return tx.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", "bank:" + operationId)
                    .query((rs, rowNum) -> 1)
                    .single();
            return lookup(operationId).orElseGet(() -> {
                BankResponse response = outcome.get();
                jdbc.sql("""
                                INSERT INTO sim_bank_operations (operation_id, approved, reference, decline_code)
                                VALUES (:id, :approved, :reference, :declineCode)
                                """)
                        .param("id", operationId)
                        .param("approved", response.approved())
                        .param("reference", response.reference())
                        .param("declineCode", response.declineCode())
                        .update();
                return response;
            });
        });
    }

    private BankResponse approveAuthorization(String cardToken, String currency) {
        BankResponse response = approve();
        jdbc.sql("INSERT INTO sim_bank_authorizations (reference, card_token, currency) VALUES (:reference, :token, :currency)")
                .param("reference", response.reference())
                .param("token", cardToken)
                .param("currency", currency)
                .update();
        return response;
    }

    private BankResponse book(String type, long amount, String currency) {
        BankResponse response = approve();
        Instant now = Instant.now();
        insertLine(response.reference(), type, amount, currency, now, LocalDate.ofInstant(now, ZoneOffset.UTC));
        return response;
    }

    private void insertLine(String reference, String type, long amount, String currency, Instant bookedAt, LocalDate bookedOn) {
        jdbc.sql("""
                        INSERT INTO sim_bank_statement_lines (reference, type, amount, currency, booked_at, booked_on)
                        VALUES (:reference, :type, :amount, :currency, :bookedAt, :bookedOn)
                        """)
                .param("reference", reference)
                .param("type", type)
                .param("amount", amount)
                .param("currency", currency)
                .param("bookedAt", bookedAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                .param("bookedOn", bookedOn)
                .update();
    }

    private static BankResponse approve() {
        return BankResponse.approved("bank_" + UUID.randomUUID());
    }
}
