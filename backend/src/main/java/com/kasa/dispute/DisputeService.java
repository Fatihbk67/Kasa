package com.kasa.dispute;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.bank.BankGateway;
import com.kasa.ledger.AccountType;
import com.kasa.ledger.LedgerService;
import com.kasa.ledger.NewJournalEntry;
import com.kasa.ledger.Posting;
import com.kasa.observability.BusinessMetrics;
import com.kasa.notification.NotificationService;
import com.kasa.tenant.TenantScope;
import com.kasa.webhook.WebhookEvents;

/**
 * Chargebacks. The bank takes the money back the moment a dispute opens, so the merchant's balance falls at once
 * and only a win returns it. Each movement is a payment operation, which puts it into the payment's history,
 * the next payout and the bank reconciliation with no rules of its own.
 * Locks the payment before the dispute everywhere, so the two can never deadlock.
 */
@Service
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

    private static final int RESOLVE_BATCH_SIZE = 100;

    public record DisputeView(UUID id, UUID paymentId, long amount, String currency, String reason, String status,
                              long feeAmount, OffsetDateTime respondBy, String evidenceExplanation,
                              String evidenceTracking, String evidenceCommunication,
                              OffsetDateTime evidenceSubmittedAt, OffsetDateTime resolvedAt, OffsetDateTime createdAt) {
    }

    public record DisputePage(List<DisputeView> items, UUID nextCursor) {
    }

    public record Evidence(String explanation, String tracking, String communication) {
    }

    public record DisputeFilter(String status, UUID paymentId) {
    }

    private record LockedPayment(long captured, long refunded, long disputed, String currency, String status) {
    }

    private record DueDispute(UUID id, UUID tenantId, String status, boolean hasTracking) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final NotificationService notifications;
    private final TenantScope tenantScope;
    private final LedgerService ledger;
    private final BankGateway bank;
    private final WebhookEvents webhookEvents;
    private final BusinessMetrics metrics;
    private final AuditLog auditLog;
    private final DisputeProperties properties;

    public DisputeService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, LedgerService ledger,
                          BankGateway bank, WebhookEvents webhookEvents, BusinessMetrics metrics, AuditLog auditLog,
                          DisputeProperties properties, NotificationService notifications) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.ledger = ledger;
        this.bank = bank;
        this.webhookEvents = webhookEvents;
        this.metrics = metrics;
        this.auditLog = auditLog;
        this.properties = properties;
    }

    /** Opens a dispute as the bank would. A null amount disputes everything still on the payment. */
    public DisputeView open(UUID tenantId, UUID paymentId, Long requestedAmount, String reason) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            LockedPayment payment = lockPayment(paymentId);
            if (!payment.status().equals("CAPTURED") && !payment.status().equals("PARTIALLY_REFUNDED")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "only a captured payment can be disputed");
            }
            boolean busy = jdbc.sql("SELECT EXISTS (SELECT 1 FROM payment_operations WHERE payment_id = :id AND status = 'PENDING')")
                    .param("id", paymentId)
                    .query(Boolean.class)
                    .single();
            if (busy) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "another operation is in progress");
            }
            boolean alreadyDisputed = jdbc.sql("SELECT EXISTS (SELECT 1 FROM disputes WHERE payment_id = :id)")
                    .param("id", paymentId)
                    .query(Boolean.class)
                    .single();
            if (alreadyDisputed) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "this payment has already been disputed");
            }
            long available = payment.captured() - payment.refunded();
            long amount = requestedAmount == null ? available : requestedAmount;
            if (available == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "the payment has been fully refunded");
            }
            if (amount > available) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "amount exceeds the available " + available);
            }

            UUID disputeId = UUID.randomUUID();
            OffsetDateTime respondBy = OffsetDateTime.now(ZoneOffset.UTC).plus(properties.responseWindow());
            jdbc.sql("""
                            INSERT INTO disputes (id, tenant_id, payment_id, amount, currency, reason, status, respond_by)
                            VALUES (:id, :tenantId, :paymentId, :amount, :currency, :reason, 'NEEDS_RESPONSE', :respondBy)
                            """)
                    .param("id", disputeId)
                    .param("tenantId", tenantId)
                    .param("paymentId", paymentId)
                    .param("amount", amount)
                    .param("currency", payment.currency())
                    .param("reason", reason)
                    .param("respondBy", respondBy)
                    .update();
            UUID operationId = recordMovement(tenantId, paymentId, disputeId, "DISPUTE_WITHDRAWAL", amount, payment.currency());
            setDisputedAmount(paymentId, payment.disputed() + amount);
            post(tenantId, payment.currency(), operationId, "dispute withdrawal", amount, "merchant_payable", AccountType.LIABILITY,
                    "psp_clearing", AccountType.ASSET);

            auditLog.record(tenantId, AuditLog.Actor.SYSTEM, "DISPUTE_OPENED", "DISPUTE", disputeId,
                    Map.of("amount", amount, "currency", payment.currency(), "reason", reason));
            notifications.toRoles(tenantId, NotificationService.OPERATORS, "DISPUTE_OPENED", "Yeni itiraz açıldı",
                    com.kasa.common.MoneyText.format(amount, payment.currency()) + " tutarında itiraz. Son yanıt: "
                            + respondBy.atZoneSameInstant(java.time.ZoneId.of("Europe/Istanbul")).format(
                                    java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.forLanguageTag("tr-TR"))) + ".",
                    "/disputes");
            DisputeView view = find(disputeId);
            webhookEvents.publish(tenantId, "dispute.opened", view);
            metrics.countAfterCommit("kasa.disputes", "status", "OPENED");
            return view;
        });
    }

    public DisputeView submitEvidence(UUID tenantId, UUID disputeId, Evidence evidence) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            DisputeView dispute = lockDispute(disputeId);
            if (!dispute.status().equals("NEEDS_RESPONSE")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "this dispute no longer accepts evidence");
            }
            if (dispute.respondBy().isBefore(OffsetDateTime.now())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "the deadline to respond has passed");
            }
            jdbc.sql("""
                            UPDATE disputes
                            SET status = 'UNDER_REVIEW', evidence_explanation = :explanation, evidence_tracking = :tracking,
                                evidence_communication = :communication, evidence_submitted_at = now(), updated_at = now()
                            WHERE id = :id
                            """)
                    .param("explanation", evidence.explanation())
                    .param("tracking", evidence.tracking())
                    .param("communication", evidence.communication())
                    .param("id", disputeId)
                    .update();
            // The evidence text stays out of the permanent log: it can name the customer.
            auditLog.record(tenantId, "DISPUTE_EVIDENCE_SUBMITTED", "DISPUTE", disputeId, Map.of());
            DisputeView view = find(disputeId);
            webhookEvents.publish(tenantId, "dispute.under_review", view);
            return view;
        });
    }

    /** The merchant gives up the money without a fight, which is sometimes cheaper than proving a small sale. */
    public DisputeView accept(UUID tenantId, UUID disputeId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            DisputeView dispute = lockDispute(disputeId);
            if (!dispute.status().equals("NEEDS_RESPONSE")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "this dispute can no longer be accepted");
            }
            auditLog.record(tenantId, "DISPUTE_ACCEPTED", "DISPUTE", disputeId, Map.of());
            return resolve(tenantId, dispute, false);
        });
    }

    /** Applies missed deadlines and the bank's decisions. Returns how many disputes reached an outcome. */
    public int resolveDue(Duration reviewedFor) {
        List<DueDispute> due = jdbc.sql("SELECT id, tenant_id, status, has_tracking FROM find_due_disputes(:seconds, :limit)")
                .param("seconds", reviewedFor.toSeconds())
                .param("limit", RESOLVE_BATCH_SIZE)
                .query((rs, rowNum) -> new DueDispute(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("status"),
                        rs.getBoolean("has_tracking")))
                .list();
        int resolved = 0;
        for (DueDispute candidate : due) {
            try {
                if (resolveIfStill(candidate.tenantId(), candidate.id(), candidate.status(), candidate.hasTracking())) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                // One merchant's problem must not hold up everyone else's decisions. The next run retries it.
                log.error("Could not resolve dispute {}", candidate.id(), e);
            }
        }
        return resolved;
    }

    /**
     * Under the lock the dispute may have moved on since it was listed: evidence arriving just before the
     * deadline must not be overruled by a job that saw it still waiting.
     */
    boolean resolveIfStill(UUID tenantId, UUID disputeId, String seenStatus, boolean hasTracking) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            tenantScope.apply(tenantId);
            DisputeView dispute = lockDispute(disputeId);
            if (!dispute.status().equals(seenStatus)) {
                return false;
            }
            boolean won = seenStatus.equals("UNDER_REVIEW") && bank.decideDispute(disputeId, hasTracking);
            resolve(tenantId, dispute, won);
            return true;
        }));
    }

    public DisputeView get(UUID tenantId, UUID disputeId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            return find(disputeId);
        });
    }

    public DisputePage list(UUID tenantId, DisputeFilter filter, UUID before, int limit) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            StringBuilder sql = new StringBuilder("SELECT * FROM disputes WHERE true");
            Map<String, Object> params = new HashMap<>();
            params.put("limit", limit + 1);
            if (filter.status() != null) {
                sql.append(" AND status = :status");
                params.put("status", filter.status());
            }
            if (filter.paymentId() != null) {
                sql.append(" AND payment_id = :paymentId");
                params.put("paymentId", filter.paymentId());
            }
            if (before != null) {
                sql.append(" AND (created_at, id) < (SELECT created_at, id FROM disputes WHERE id = :before)");
                params.put("before", before);
            }
            sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
            List<DisputeView> rows = jdbc.sql(sql.toString()).params(params).query(DisputeService::mapDispute).list();
            boolean hasMore = rows.size() > limit;
            List<DisputeView> page = hasMore ? rows.subList(0, limit) : rows;
            return new DisputePage(page, hasMore ? page.getLast().id() : null);
        });
    }

    /** Expects the dispute to be locked and still open. A win returns the money, a loss adds the fee. */
    private DisputeView resolve(UUID tenantId, DisputeView dispute, boolean won) {
        String currency = dispute.currency();
        long fee = 0;
        if (won) {
            UUID operationId = recordMovement(tenantId, dispute.paymentId(), dispute.id(), "DISPUTE_REVERSAL", dispute.amount(), currency);
            setDisputedAmount(dispute.paymentId(), lockPayment(dispute.paymentId()).disputed() - dispute.amount());
            post(tenantId, currency, operationId, "dispute reversal", dispute.amount(), "psp_clearing", AccountType.ASSET,
                    "merchant_payable", AccountType.LIABILITY);
        } else if (properties.lossFee() > 0) {
            fee = properties.lossFee();
            UUID operationId = recordFee(tenantId, dispute.paymentId(), dispute.id(), fee);
            post(tenantId, currency, operationId, "dispute fee", fee, "merchant_payable", AccountType.LIABILITY,
                    "fee_revenue", AccountType.REVENUE);
        }
        String outcome = won ? "WON" : "LOST";
        jdbc.sql("""
                        UPDATE disputes
                        SET status = :status, fee_amount = :fee, resolved_at = now(), updated_at = now()
                        WHERE id = :id
                        """)
                .param("status", outcome)
                .param("fee", fee)
                .param("id", dispute.id())
                .update();
        auditLog.record(tenantId, AuditLog.Actor.SYSTEM, "DISPUTE_" + outcome, "DISPUTE", dispute.id(),
                Map.of("amount", dispute.amount(), "currency", currency));
        DisputeView view = find(dispute.id());
        webhookEvents.publish(tenantId, won ? "dispute.won" : "dispute.lost", view);
        metrics.countAfterCommit("kasa.disputes", "status", outcome);
        return view;
    }

    private UUID recordMovement(UUID tenantId, UUID paymentId, UUID disputeId, String type, long amount, String currency) {
        UUID operationId = UUID.randomUUID();
        String reference = bank.disputeMovement(operationId, type, amount, currency).reference();
        insertOperation(operationId, tenantId, paymentId, disputeId, type, amount, reference);
        return operationId;
    }

    /** The fee moves only between our own accounts, so it has no bank reference and no statement line. */
    private UUID recordFee(UUID tenantId, UUID paymentId, UUID disputeId, long fee) {
        UUID operationId = UUID.randomUUID();
        insertOperation(operationId, tenantId, paymentId, disputeId, "DISPUTE_FEE", fee, null);
        return operationId;
    }

    private void insertOperation(UUID id, UUID tenantId, UUID paymentId, UUID disputeId, String type, long amount, String reference) {
        jdbc.sql("""
                        INSERT INTO payment_operations (id, tenant_id, payment_id, type, amount, status, idempotency_key,
                                                        request_hash, bank_reference)
                        VALUES (:id, :tenantId, :paymentId, :type, :amount, 'SUCCEEDED', :key, :hash, :reference)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("paymentId", paymentId)
                .param("type", type)
                .param("amount", amount)
                .param("key", "dispute:" + disputeId + ":" + type)
                .param("hash", type)
                .param("reference", reference)
                .update();
    }

    private void setDisputedAmount(UUID paymentId, long amount) {
        jdbc.sql("UPDATE payments SET disputed_amount = :amount, updated_at = now() WHERE id = :id")
                .param("amount", amount)
                .param("id", paymentId)
                .update();
    }

    private void post(UUID tenantId, String currency, UUID operationId, String description, long amount, String debitCode,
                      AccountType debitType, String creditCode, AccountType creditType) {
        UUID debit = ledger.ensureAccount(tenantId, debitCode, debitType, currency, !debitCode.equals("fee_revenue")).id();
        UUID credit = ledger.ensureAccount(tenantId, creditCode, creditType, currency, !creditCode.equals("fee_revenue")).id();
        ledger.post(tenantId, new NewJournalEntry("payment-operation:" + operationId, description, List.of(
                Posting.debit(debit, amount),
                Posting.credit(credit, amount))));
    }

    private LockedPayment lockPayment(UUID paymentId) {
        return jdbc.sql("""
                        SELECT captured_amount, refunded_amount, disputed_amount, currency, status
                        FROM payments WHERE id = :id FOR UPDATE
                        """)
                .param("id", paymentId)
                .query((rs, rowNum) -> new LockedPayment(rs.getLong("captured_amount"), rs.getLong("refunded_amount"),
                        rs.getLong("disputed_amount"), rs.getString("currency"), rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payment not found"));
    }

    private DisputeView lockDispute(UUID disputeId) {
        UUID paymentId = find(disputeId).paymentId();
        lockPayment(paymentId);
        return jdbc.sql("SELECT * FROM disputes WHERE id = :id FOR UPDATE")
                .param("id", disputeId)
                .query(DisputeService::mapDispute)
                .single();
    }

    private DisputeView find(UUID disputeId) {
        return jdbc.sql("SELECT * FROM disputes WHERE id = :id")
                .param("id", disputeId)
                .query(DisputeService::mapDispute)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "dispute not found"));
    }

    private static DisputeView mapDispute(ResultSet rs, int rowNum) throws SQLException {
        return new DisputeView(
                rs.getObject("id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getLong("fee_amount"),
                rs.getObject("respond_by", OffsetDateTime.class),
                rs.getString("evidence_explanation"),
                rs.getString("evidence_tracking"),
                rs.getString("evidence_communication"),
                rs.getObject("evidence_submitted_at", OffsetDateTime.class),
                rs.getObject("resolved_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
