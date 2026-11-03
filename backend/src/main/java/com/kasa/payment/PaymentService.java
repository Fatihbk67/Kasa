package com.kasa.payment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.bank.BankGateway;
import com.kasa.bank.BankResponse;
import com.kasa.bank.BankTimeoutException;
import com.kasa.common.Hashes;
import com.kasa.observability.BusinessMetrics;
import com.kasa.payment.PaymentViews.OperationResult;
import com.kasa.payment.PaymentViews.OperationView;
import com.kasa.payment.PaymentViews.PaymentDetail;
import com.kasa.payment.PaymentViews.PaymentPage;
import com.kasa.payment.PaymentViews.PaymentView;
import com.kasa.tenant.TenantScope;
import com.kasa.webhook.WebhookEvents;

/**
 * Runs each bank call in three steps: commit a PENDING operation, call the bank with no transaction open,
 * then commit the outcome. A database connection is never held while waiting on the bank, and a crash or
 * timeout between the steps leaves a PENDING row that {@link #recoverStale} can resolve.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String NO_RECORD_AT_BANK = "bank_no_record";
    private static final int RECOVERY_BATCH_SIZE = 100;
    private static final int MAX_KEY_LENGTH = 255;

    /** The operation to send to the bank, or with {@code replay} set, the earlier result of the same request. */
    private record Begun(PaymentOperation operation, String authorizationReference, boolean replay) {
    }

    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final PaymentStore store;
    private final PaymentLedger paymentLedger;
    private final BankGateway bank;
    private final PaymentProperties properties;
    private final WebhookEvents webhookEvents;
    private final BusinessMetrics metrics;
    private final AuditLog auditLog;

    public PaymentService(TransactionTemplate tx, TenantScope tenantScope, PaymentStore store,
                          PaymentLedger paymentLedger, BankGateway bank, PaymentProperties properties,
                          WebhookEvents webhookEvents, BusinessMetrics metrics, AuditLog auditLog) {
        this.auditLog = auditLog;
        this.webhookEvents = webhookEvents;
        this.metrics = metrics;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.store = store;
        this.paymentLedger = paymentLedger;
        this.bank = bank;
        this.properties = properties;
    }

    public OperationResult create(UUID tenantId, String key, long amount, String currency, String cardToken,
                                  String description) {
        return create(tenantId, key, amount, currency, cardToken, description, null);
    }

    public OperationResult create(UUID tenantId, String key, long amount, String currency, String cardToken,
                                  String description, UUID customerId) {
        requireValidKey(key);
        String hash = Hashes.sha256Hex(String.join("|", "CREATE", String.valueOf(amount), currency, cardToken,
                String.valueOf(description)) + (customerId == null ? "" : "|" + customerId));
        Begun begun = tx.execute(status -> {
            tenantScope.apply(tenantId);
            store.lockIdempotencyKey(tenantId, key);
            return store.findOperationByKey(key)
                    .map(existing -> replay(existing, hash))
                    .orElseGet(() -> {
                        // Every way of taking money starts here, so this one check covers the API, the dashboard,
                        // checkout pages, payment links and subscriptions. Refunds stay open: customers are still owed.
                        if (!store.tenantIsActive()) {
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "this merchant account is not approved to take payments");
                        }
                        if (customerId != null && !store.activeCustomerExists(customerId)) {
                            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown customer");
                        }
                        UUID paymentId = UUID.randomUUID();
                        store.insertPayment(paymentId, tenantId, amount, currency, description, customerId);
                        return new Begun(store.insertOperation(tenantId, paymentId, OperationType.AUTHORIZE, amount,
                                key, hash), null, false);
                    });
        });
        return run(tenantId, begun, () -> bank.authorize(begun.operation().id(), cardToken, amount, currency));
    }

    public OperationResult capture(UUID tenantId, UUID paymentId, String key, Long requestedAmount) {
        Begun begun = begin(tenantId, paymentId, OperationType.CAPTURE, key, requestedAmount);
        return run(tenantId, begun, () -> bank.capture(begun.operation().id(), begun.authorizationReference(),
                begun.operation().amount()));
    }

    public OperationResult voidPayment(UUID tenantId, UUID paymentId, String key) {
        Begun begun = begin(tenantId, paymentId, OperationType.VOID, key, null);
        return run(tenantId, begun, () -> bank.voidAuthorization(begun.operation().id(),
                begun.authorizationReference()));
    }

    public OperationResult refund(UUID tenantId, UUID paymentId, String key, Long requestedAmount) {
        Begun begun = begin(tenantId, paymentId, OperationType.REFUND, key, requestedAmount);
        return run(tenantId, begun, () -> bank.refund(begun.operation().id(), begun.authorizationReference(),
                begun.operation().amount()));
    }

    public PaymentDetail get(UUID tenantId, UUID paymentId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            Payment payment = store.find(paymentId).orElseThrow(PaymentService::notFound);
            List<OperationView> operations = store.findOperations(paymentId).stream().map(OperationView::of).toList();
            return new PaymentDetail(PaymentView.of(payment), operations);
        });
    }

    public PaymentPage list(UUID tenantId, PaymentFilter filter, UUID before, int limit) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            List<Payment> rows = store.list(filter, before, limit + 1);
            boolean hasMore = rows.size() > limit;
            List<Payment> page = hasMore ? rows.subList(0, limit) : rows;
            return new PaymentPage(page.stream().map(PaymentView::of).toList(), hasMore ? page.getLast().id() : null);
        });
    }

    public record CsvExport(byte[] content, boolean truncated) {
    }

    /** Reports whether rows were left out, so a cut-off report can never be mistaken for a complete one. */
    public CsvExport exportCsv(UUID tenantId, PaymentFilter filter, int limit) {
        PaymentPage page = list(tenantId, filter, null, limit);
        return new CsvExport(PaymentCsv.write(page.items()), page.nextCursor() != null);
    }

    /** Asks the bank what happened to operations stuck in PENDING. Returns how many were resolved. */
    public int recoverStale(Duration olderThan) {
        int resolved = 0;
        for (PaymentStore.StaleOperation stale : store.findStaleOperations(olderThan, RECOVERY_BATCH_SIZE)) {
            try {
                BankResponse response = bank.lookup(stale.id()).orElseGet(() -> BankResponse.declined(NO_RECORD_AT_BANK));
                applyOutcome(stale.tenantId(), stale.paymentId(), stale.id(), response);
                resolved++;
            } catch (BankTimeoutException e) {
                log.warn("Bank lookup timed out for operation {}; will retry", stale.id());
            } catch (RuntimeException e) {
                log.error("Could not recover operation {}", stale.id(), e);
            }
        }
        return resolved;
    }

    private Begun begin(UUID tenantId, UUID paymentId, OperationType type, String key, Long requestedAmount) {
        requireValidKey(key);
        String hash = Hashes.sha256Hex(String.join("|", type.name(), paymentId.toString(),
                String.valueOf(requestedAmount)));
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            store.lockIdempotencyKey(tenantId, key);
            var existing = store.findOperationByKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), hash);
            }
            Payment payment = store.lock(paymentId).orElseThrow(PaymentService::notFound);
            if (store.hasPendingOperation(paymentId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "another operation is in progress");
            }
            long amount = validatedAmount(payment, type, requestedAmount);
            PaymentOperation operation = store.insertOperation(tenantId, paymentId, type, amount, key, hash);
            auditLog.record(tenantId, "PAYMENT_" + type.name() + "_REQUESTED", "PAYMENT", paymentId,
                    Map.of("amount", amount, "currency", payment.currency(), "operationId", operation.id()));
            return new Begun(operation, payment.bankReference(), false);
        });
    }

    private static long validatedAmount(Payment payment, OperationType type, Long requested) {
        return switch (type) {
            case CAPTURE -> {
                requireStatus(payment, type, PaymentStatus.AUTHORIZED);
                yield withinLimit(requested, payment.amount());
            }
            case VOID -> {
                requireStatus(payment, type, PaymentStatus.AUTHORIZED);
                yield payment.amount();
            }
            case REFUND -> {
                requireStatus(payment, type, PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED);
                // The bank has already returned disputed money to the customer. Refunding it too would pay them twice.
                long refundable = payment.capturedAmount() - payment.refundedAmount() - payment.disputedAmount();
                if (refundable == 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "nothing left to refund: the rest is under dispute");
                }
                yield withinLimit(requested, refundable);
            }
            default -> throw new IllegalArgumentException(type + " is not started through a bank call");
        };
    }

    private static void requireStatus(Payment payment, OperationType type, PaymentStatus... allowed) {
        if (!List.of(allowed).contains(payment.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cannot " + type.name().toLowerCase(java.util.Locale.ROOT) + " a payment in status " + payment.status());
        }
    }

    private static long withinLimit(Long requested, long limit) {
        long amount = requested == null ? limit : requested;
        if (amount > limit) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "amount exceeds the available " + limit);
        }
        return amount;
    }

    private static Begun replay(PaymentOperation existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "idempotency key was used for a different request");
        }
        return new Begun(existing, null, true);
    }

    private OperationResult run(UUID tenantId, Begun begun, Supplier<BankResponse> bankCall) {
        PaymentOperation operation = begun.operation();
        if (!begun.replay()) {
            try {
                applyOutcome(tenantId, operation.paymentId(), operation.id(), bankCall.get());
            } catch (BankTimeoutException e) {
                log.warn("Bank timed out on operation {}; leaving it for recovery", operation.id());
            }
        }
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            Payment payment = store.find(operation.paymentId()).orElseThrow(PaymentService::notFound);
            return new OperationResult(PaymentView.of(payment), OperationView.of(store.getOperation(operation.id())));
        });
    }

    /**
     * Commits the bank's answer and its ledger entries atomically. The PENDING check under the row lock
     * makes this safe when a request and the recovery job report the same outcome.
     */
    private void applyOutcome(UUID tenantId, UUID paymentId, UUID operationId, BankResponse response) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            Payment payment = store.lock(paymentId).orElseThrow(PaymentService::notFound);
            PaymentOperation operation = store.getOperation(operationId);
            if (operation.status() != OperationStatus.PENDING) {
                return;
            }
            Runnable ledgerEntry = () -> { };
            if (response.approved()) {
                store.completeOperation(operationId, OperationStatus.SUCCEEDED, response.reference(), null);
                ledgerEntry = applyApproval(tenantId, payment, operation, response);
            } else {
                store.completeOperation(operationId, OperationStatus.FAILED, null, response.declineCode());
                if (operation.type() == OperationType.AUTHORIZE) {
                    PaymentStatus failed = NO_RECORD_AT_BANK.equals(response.declineCode())
                            ? PaymentStatus.FAILED
                            : PaymentStatus.DECLINED;
                    store.update(paymentId, failed, 0, 0, 0, null, response.declineCode());
                }
            }
            publishOutcome(tenantId, paymentId, operationId);
            // Last, because posting locks the merchant's balance rows until commit, and every other payment
            // of the same merchant waits for them. Nothing slow may run after it.
            ledgerEntry.run();
        });
    }

    private void publishOutcome(UUID tenantId, UUID paymentId, UUID operationId) {
        Payment payment = store.find(paymentId).orElseThrow(PaymentService::notFound);
        PaymentOperation operation = store.getOperation(operationId);
        webhookEvents.publish(tenantId, eventType(payment, operation),
                new OperationResult(PaymentView.of(payment), OperationView.of(operation)));
        metrics.countAfterCommit("kasa.payment.operations", "type", operation.type().name(), "status", operation.status().name());
    }

    private static String eventType(Payment payment, PaymentOperation operation) {
        if (operation.status() == OperationStatus.SUCCEEDED) {
            return switch (operation.type()) {
                case AUTHORIZE -> "payment.authorized";
                case CAPTURE -> "payment.captured";
                case VOID -> "payment.voided";
                case REFUND -> "payment.refunded";
                default -> throw new IllegalArgumentException(operation.type() + " is not started through a bank call");
            };
        }
        if (operation.type() == OperationType.AUTHORIZE) {
            return payment.status() == PaymentStatus.FAILED ? "payment.failed" : "payment.declined";
        }
        return "payment.operation_failed";
    }

    /** Updates the payment and returns its ledger entry, which the caller posts at the very end of the transaction. */
    private Runnable applyApproval(UUID tenantId, Payment payment, PaymentOperation operation, BankResponse response) {
        return switch (operation.type()) {
            case AUTHORIZE -> {
                store.update(payment.id(), PaymentStatus.AUTHORIZED, 0, 0, 0, response.reference(), null);
                yield () -> { };
            }
            case VOID -> {
                store.update(payment.id(), PaymentStatus.VOIDED, 0, 0, 0, payment.bankReference(), null);
                yield () -> { };
            }
            case CAPTURE -> {
                long fee = feeFor(operation.amount());
                store.update(payment.id(), PaymentStatus.CAPTURED, operation.amount(), 0, fee, payment.bankReference(), null);
                yield () -> paymentLedger.recordCapture(tenantId, payment.currency(), operation.id(), operation.amount(), fee);
            }
            case REFUND -> {
                long refunded = payment.refundedAmount() + operation.amount();
                PaymentStatus next = refunded == payment.capturedAmount()
                        ? PaymentStatus.REFUNDED
                        : PaymentStatus.PARTIALLY_REFUNDED;
                store.update(payment.id(), next, payment.capturedAmount(), refunded, payment.feeAmount(),
                        payment.bankReference(), null);
                yield () -> paymentLedger.recordRefund(tenantId, payment.currency(), operation.id(), operation.amount());
            }
            default -> throw new IllegalArgumentException(operation.type() + " is not started through a bank call");
        };
    }

    /** Rounds half up, in integer arithmetic so no floating point error can touch money. */
    private long feeFor(long amount) {
        return (Math.multiplyExact(amount, properties.feeBasisPoints()) + 5_000) / 10_000;
    }

    private static void requireValidKey(String key) {
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-255 characters");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "payment not found");
    }
}
