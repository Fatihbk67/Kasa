package com.kasa.iyzico;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.kasa.bank.BankResponse;
import com.kasa.bank.BankTimeoutException;

/**
 * The bank operations of a payment iyzico has already charged on its hosted page. The customer's money moved
 * there, so authorising and capturing only confirm what iyzico reported; cancelling, refunding and capturing less
 * than was charged send money back through iyzico.
 *
 * <p>Each operation is written down as SENT before iyzico is called and settled after, outside any transaction.
 * A repeated operation is answered from that record. One left at SENT, after a crash or timeout, is never guessed
 * at: it stays unresolved for a person to check in iyzico's panel, which is safer than refunding twice.
 */
@Component
public class IyzicoGateway {

    public static final String PREFIX = "iyzico:";

    private record Charged(long amount, String currency) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final IyzicoClient client;

    public IyzicoGateway(JdbcClient jdbc, TransactionTemplate tx, IyzicoClient client) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.client = client;
    }

    /** The card token for a payment iyzico charged. Its middle names the card, so fraud rules can recognise it. */
    public static String cardToken(String cardKey, String paymentId) {
        return PREFIX + cardKey + ":" + paymentId;
    }

    public static boolean handles(String tokenOrReference) {
        return tokenOrReference != null && tokenOrReference.startsWith(PREFIX);
    }

    /** Records what iyzico confirmed it charged. Safe to repeat. */
    public void recordCharge(String paymentId, long amount, String currency, int installment, String cardLabel) {
        jdbc.sql("""
                        INSERT INTO iyzico_payments (payment_id, amount, currency, installment, card_label)
                        VALUES (:id, :amount, :currency, :installment, :label)
                        ON CONFLICT (payment_id) DO NOTHING
                        """)
                .param("id", paymentId)
                .param("amount", amount)
                .param("currency", currency)
                .param("installment", installment)
                .param("label", cardLabel)
                .update();
    }

    BankResponse authorize(UUID operationId, String cardToken, long amount, String currency) {
        String paymentId = cardToken.substring(cardToken.lastIndexOf(':') + 1);
        return settleLocally(operationId, () -> {
            // Claims the charge for this operation alone: the same iyzico payment can never back two of ours.
            int claimed = jdbc.sql("""
                            UPDATE iyzico_payments SET operation_id = :operationId
                            WHERE payment_id = :id AND amount = :amount AND currency = :currency AND operation_id IS NULL
                            """)
                    .param("operationId", operationId)
                    .param("id", paymentId)
                    .param("amount", amount)
                    .param("currency", currency)
                    .update();
            return claimed == 1 ? BankResponse.approved(PREFIX + paymentId) : BankResponse.declined("invalid_token");
        });
    }

    /** The money is already taken. Capturing less sends the rest back to the customer. */
    BankResponse capture(UUID operationId, String reference, long amount) {
        String paymentId = paymentId(reference);
        Optional<Charged> charged = charged(paymentId);
        if (charged.isEmpty()) {
            return settleLocally(operationId, () -> BankResponse.declined("unknown_authorization"));
        }
        long rest = charged.get().amount() - amount;
        if (rest == 0) {
            return settleLocally(operationId, () -> BankResponse.approved(PREFIX + operationId));
        }
        return callIyzico(operationId, () -> client.refund(operationId.toString(), paymentId, minor(rest),
                charged.get().currency()));
    }

    /** Gives the whole payment back: cancelled if iyzico still allows it, refunded otherwise. */
    BankResponse voidAuthorization(UUID operationId, String reference) {
        String paymentId = paymentId(reference);
        Optional<Charged> charged = charged(paymentId);
        if (charged.isEmpty()) {
            return settleLocally(operationId, () -> BankResponse.declined("unknown_authorization"));
        }
        return callIyzico(operationId, () -> {
            try {
                client.cancel(operationId.toString(), paymentId);
            } catch (IyzicoClient.Refused e) {
                client.refund(operationId.toString(), paymentId, minor(charged.get().amount()), charged.get().currency());
            }
        });
    }

    BankResponse refund(UUID operationId, String reference, long amount) {
        String paymentId = paymentId(reference);
        Optional<Charged> charged = charged(paymentId);
        if (charged.isEmpty()) {
            return settleLocally(operationId, () -> BankResponse.declined("unknown_authorization"));
        }
        return callIyzico(operationId, () -> client.refund(operationId.toString(), paymentId, minor(amount),
                charged.get().currency()));
    }

    /** Empty when this gateway never saw the operation, so the caller can ask the other bank. */
    Optional<BankResponse> lookup(UUID operationId) {
        return jdbc.sql("SELECT status, reference, decline_code FROM iyzico_operations WHERE operation_id = :id")
                .param("id", operationId)
                .query((rs, rowNum) -> switch (rs.getString("status")) {
                    case "APPROVED" -> BankResponse.approved(rs.getString("reference"));
                    case "DECLINED" -> BankResponse.declined(rs.getString("decline_code"));
                    // Sent, but its outcome never came back. Recovery keeps asking instead of assuming.
                    default -> throw new BankTimeoutException();
                })
                .optional();
    }

    /** Whether this operation was handled here, without resolving it. */
    boolean knows(UUID operationId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM iyzico_operations WHERE operation_id = :id)")
                .param("id", operationId)
                .query(Boolean.class)
                .single();
    }

    /** An outcome decided without calling iyzico. The lock makes a repeat wait for, then return, the first answer. */
    private BankResponse settleLocally(UUID operationId, Supplier<BankResponse> outcome) {
        return tx.execute(status -> {
            lock(operationId);
            return lookup(operationId).orElseGet(() -> {
                BankResponse response = outcome.get();
                insert(operationId, response.approved() ? "APPROVED" : "DECLINED", response.reference(), response.declineCode());
                return response;
            });
        });
    }

    /**
     * Marks the call as sent and commits that before calling, so no transaction waits on iyzico and a crash
     * mid-call leaves a trace. A repeat of a finished operation gets the stored answer and calls nobody.
     */
    private BankResponse callIyzico(UUID operationId, Runnable call) {
        Optional<BankResponse> earlier = tx.execute(status -> {
            lock(operationId);
            Optional<BankResponse> found = lookup(operationId);
            if (found.isEmpty()) {
                insert(operationId, "SENT", null, null);
            }
            return found;
        });
        if (earlier.isPresent()) {
            return earlier.get();
        }
        try {
            call.run();
        } catch (IyzicoClient.Refused e) {
            String code = "iyzico_" + (e.code() == null ? "refused" : e.code());
            finish(operationId, "DECLINED", null, code);
            return BankResponse.declined(code);
        }
        String reference = PREFIX + operationId;
        finish(operationId, "APPROVED", reference, null);
        return BankResponse.approved(reference);
    }

    private void lock(UUID operationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "iyzico:" + operationId)
                .query((rs, rowNum) -> 1)
                .single();
    }

    private void insert(UUID operationId, String status, String reference, String declineCode) {
        jdbc.sql("""
                        INSERT INTO iyzico_operations (operation_id, status, reference, decline_code)
                        VALUES (:id, :status, :reference, :code)
                        """)
                .param("id", operationId)
                .param("status", status)
                .param("reference", reference)
                .param("code", declineCode)
                .update();
    }

    private void finish(UUID operationId, String status, String reference, String declineCode) {
        jdbc.sql("""
                        UPDATE iyzico_operations SET status = :status, reference = :reference, decline_code = :code,
                                                     updated_at = now()
                        WHERE operation_id = :id AND status = 'SENT'
                        """)
                .param("status", status)
                .param("reference", reference)
                .param("code", declineCode)
                .param("id", operationId)
                .update();
    }

    private Optional<Charged> charged(String paymentId) {
        return jdbc.sql("SELECT amount, currency FROM iyzico_payments WHERE payment_id = :id")
                .param("id", paymentId)
                .query((rs, rowNum) -> new Charged(rs.getLong("amount"), rs.getString("currency")))
                .optional();
    }

    private static String paymentId(String reference) {
        return reference.substring(PREFIX.length());
    }

    static BigDecimal minor(long amount) {
        return BigDecimal.valueOf(amount, 2);
    }
}
