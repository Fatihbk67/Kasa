package com.kasa.billing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.observability.BusinessMetrics;
import com.kasa.payment.OperationStatus;
import com.kasa.payment.PaymentService;
import com.kasa.payment.PaymentStatus;
import com.kasa.payment.PaymentViews.OperationResult;
import com.kasa.notification.NotificationService;
import com.kasa.tenant.TenantScope;
import com.kasa.webhook.WebhookEvents;

/**
 * Plans, subscriptions and the invoices they produce. Charging follows the rule the payment service follows
 * with the bank: commit the intent, charge with no transaction open, commit the outcome. An attempt keeps its
 * number and card until its outcome is known, so a crash can only ever repeat the same payment, never start a
 * second one. Always locks the subscription before its invoice.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private static final int BATCH_SIZE = 100;
    private static final int LIST_LIMIT = 100;

    public record PlanView(UUID id, String name, long amount, String currency, String billingInterval, boolean archived,
                           long activeSubscriptions, OffsetDateTime createdAt) {
    }

    public record SubscriptionView(UUID id, UUID customerId, String customerLabel, UUID planId, String planName, long amount,
                                   String currency, String billingInterval, String status, OffsetDateTime currentPeriodStart,
                                   OffsetDateTime currentPeriodEnd, boolean cancelAtPeriodEnd, long creditBalance,
                                   String cancelReason, OffsetDateTime canceledAt, OffsetDateTime createdAt) {
    }

    public record LineView(String kind, String planName, long amount) {
    }

    public record InvoiceView(UUID id, String kind, String status, OffsetDateTime periodStart, OffsetDateTime periodEnd,
                              long subtotal, long creditApplied, long amount, String currency, int attemptCount,
                              OffsetDateTime nextAttemptAt, UUID paymentId, String failureCode, OffsetDateTime paidAt,
                              OffsetDateTime createdAt, List<LineView> lines) {
    }

    public record SubscriptionDetail(SubscriptionView subscription, List<InvoiceView> invoices) {
    }

    public record SubscriptionPage(List<SubscriptionView> items, UUID nextCursor) {
    }

    public record CurrencyRevenue(String currency, long monthlyRecurring) {
    }

    public record Summary(List<CurrencyRevenue> revenue, long active, long pastDue, long canceledLast30Days) {
    }

    private record Plan(UUID id, String name, long amount, String currency, String interval, boolean archived) {
    }

    private record Subscription(UUID id, UUID customerId, UUID planId, String cardToken, String status, OffsetDateTime anchor,
                                int periodIndex, OffsetDateTime periodStart, OffsetDateTime periodEnd,
                                boolean cancelAtPeriodEnd, long creditBalance) {
    }

    private record Line(String kind, String planName, long amount) {
    }

    private record Due(UUID id, UUID tenantId) {
    }

    /** One attempt to charge an invoice, fixed before any money moves. */
    private record Attempt(UUID invoiceId, int number, String cardToken, long amount, String currency, String description,
                           UUID customerId, UUID subscriptionId) {
        String paymentKey() {
            return "invoice:" + invoiceId + ":" + number;
        }
    }

    private enum Outcome { PAID, FAILED, UNKNOWN }

    private record ChargeResult(Outcome outcome, UUID paymentId, String failureCode) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final NotificationService notifications;
    private final TenantScope tenantScope;
    private final PaymentService payments;
    private final AuditLog auditLog;
    private final WebhookEvents webhookEvents;
    private final BusinessMetrics metrics;
    private final BillingProperties properties;

    public BillingService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, PaymentService payments,
                          AuditLog auditLog, WebhookEvents webhookEvents, BusinessMetrics metrics, BillingProperties properties, NotificationService notifications) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.payments = payments;
        this.auditLog = auditLog;
        this.webhookEvents = webhookEvents;
        this.metrics = metrics;
        this.properties = properties;
    }

    public PlanView createPlan(UUID tenantId, String name, long amount, String currency, String interval) {
        UUID id = UUID.randomUUID();
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            jdbc.sql("""
                            INSERT INTO plans (id, tenant_id, name, amount, currency, billing_interval)
                            VALUES (:id, :tenantId, :name, :amount, :currency, :interval)
                            """)
                    .param("id", id)
                    .param("tenantId", tenantId)
                    .param("name", name)
                    .param("amount", amount)
                    .param("currency", currency)
                    .param("interval", interval)
                    .update();
            auditLog.record(tenantId, "PLAN_CREATED", "PLAN", id, Map.of("name", name, "amount", amount, "currency", currency));
            return findPlanView(id);
        });
    }

    /** Stops new sign-ups. Existing subscribers keep the price they agreed to. */
    public PlanView archivePlan(UUID tenantId, UUID id) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            int updated = jdbc.sql("UPDATE plans SET archived_at = now() WHERE id = :id AND archived_at IS NULL")
                    .param("id", id)
                    .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "active plan not found");
            }
            auditLog.record(tenantId, "PLAN_ARCHIVED", "PLAN", id, Map.of());
            return findPlanView(id);
        });
    }

    public List<PlanView> listPlans(UUID tenantId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            return jdbc.sql(PLAN_QUERY + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit")
                    .param("limit", LIST_LIMIT)
                    .query(BillingService::mapPlanView)
                    .list();
        });
    }

    /** The first invoice is charged before this returns. A declined card ends the sign-up rather than leaving it to retries. */
    public SubscriptionDetail subscribe(UUID tenantId, UUID customerId, UUID planId, String cardToken) {
        UUID id = UUID.randomUUID();
        UUID invoiceId;
        try {
            invoiceId = tx.execute(status -> {
                tenantScope.apply(tenantId);
                Plan plan = findPlan(planId).orElseThrow(() -> unprocessable("unknown plan"));
                if (plan.archived()) {
                    throw unprocessable("this plan is archived");
                }
                boolean customerActive = jdbc.sql("SELECT EXISTS (SELECT 1 FROM customers WHERE id = :id AND anonymized_at IS NULL)")
                        .param("id", customerId)
                        .query(Boolean.class)
                        .single();
                if (!customerActive) {
                    throw unprocessable("unknown customer");
                }
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
                OffsetDateTime end = BillingMath.periodEnd(now, plan.interval(), 1);
                jdbc.sql("""
                                INSERT INTO subscriptions (id, tenant_id, customer_id, plan_id, card_token, status, billing_anchor,
                                                           period_index, current_period_start, current_period_end)
                                VALUES (:id, :tenantId, :customerId, :planId, :token, 'INCOMPLETE', :now, 1, :now, :end)
                                """)
                        .param("id", id)
                        .param("tenantId", tenantId)
                        .param("customerId", customerId)
                        .param("planId", planId)
                        .param("token", cardToken)
                        .param("now", now)
                        .param("end", end)
                        .update();
                auditLog.record(tenantId, "SUBSCRIPTION_CREATED", "SUBSCRIPTION", id, Map.of("planName", plan.name()));
                return createInvoice(tenantId, id, "FIRST", now, end, plan, List.of(new Line("PLAN", plan.name(), plan.amount())), 0);
            });
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this customer is already on this plan");
        }
        chargeIfDue(tenantId, invoiceId);
        return get(tenantId, id);
    }

    /**
     * Moves to another plan now and settles the difference: the unused part of the old plan is credited and the
     * remaining part of the new one charged. A net credit is kept for the next invoice, since a downgrade should
     * not move money back through the card network.
     */
    public SubscriptionDetail changePlan(UUID tenantId, UUID id, UUID newPlanId) {
        UUID invoiceId;
        try {
            invoiceId = tx.execute(status -> {
                tenantScope.apply(tenantId);
                Subscription subscription = lock(id);
                if (!subscription.status().equals("ACTIVE")) {
                    throw conflict("only an active subscription can change plan");
                }
                if (subscription.planId().equals(newPlanId)) {
                    throw conflict("this subscription is already on this plan");
                }
                Plan current = findPlan(subscription.planId()).orElseThrow();
                Plan next = findPlan(newPlanId).orElseThrow(() -> unprocessable("unknown plan"));
                if (next.archived()) {
                    throw unprocessable("this plan is archived");
                }
                if (!next.currency().equals(current.currency()) || !next.interval().equals(current.interval())) {
                    throw unprocessable("the new plan must have the same currency and billing interval");
                }

                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
                long credit = BillingMath.unusedShare(current.amount(), subscription.periodStart(), subscription.periodEnd(), now);
                long charge = BillingMath.unusedShare(next.amount(), subscription.periodStart(), subscription.periodEnd(), now);
                jdbc.sql("UPDATE subscriptions SET plan_id = :plan, updated_at = now() WHERE id = :id")
                        .param("plan", newPlanId)
                        .param("id", id)
                        .update();
                auditLog.record(tenantId, "SUBSCRIPTION_PLAN_CHANGED", "SUBSCRIPTION", id,
                        Map.of("from", current.name(), "to", next.name()));
                List<Line> lines = List.of(new Line("UNUSED_TIME", current.name(), -credit), new Line("REMAINING_TIME", next.name(), charge));
                return createInvoice(tenantId, id, "PLAN_CHANGE", now, subscription.periodEnd(), next, lines, subscription.creditBalance());
            });
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this customer is already on this plan");
        }
        chargeIfDue(tenantId, invoiceId);
        return get(tenantId, id);
    }

    /** At period end the customer keeps what they paid for. Now ends it at once, with no refund. */
    public SubscriptionDetail cancel(UUID tenantId, UUID id, boolean atPeriodEnd) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            Subscription subscription = lock(id);
            if (subscription.status().equals("CANCELED")) {
                throw conflict("this subscription is already canceled");
            }
            if (atPeriodEnd) {
                if (!subscription.status().equals("ACTIVE")) {
                    throw conflict("only an active subscription can be canceled at period end");
                }
                jdbc.sql("UPDATE subscriptions SET cancel_at_period_end = true, updated_at = now() WHERE id = :id")
                        .param("id", id)
                        .update();
                auditLog.record(tenantId, "SUBSCRIPTION_CANCEL_SCHEDULED", "SUBSCRIPTION", id, Map.of());
                return;
            }
            // A charge whose outcome is unknown may yet succeed, and the customer must not pay for something ended.
            boolean inFlight = jdbc.sql("SELECT EXISTS (SELECT 1 FROM invoices WHERE subscription_id = :id AND status = 'OPEN' AND attempt_in_flight)")
                    .param("id", id)
                    .query(Boolean.class)
                    .single();
            if (inFlight) {
                throw conflict("a charge is in progress; try again shortly");
            }
            jdbc.sql("UPDATE invoices SET status = 'VOID', next_attempt_at = NULL, updated_at = now() WHERE subscription_id = :id AND status = 'OPEN'")
                    .param("id", id)
                    .update();
            markCanceled(tenantId, id, "REQUESTED");
        });
        return get(tenantId, id);
    }

    public SubscriptionDetail resume(UUID tenantId, UUID id) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            Subscription subscription = lock(id);
            if (!subscription.cancelAtPeriodEnd() || subscription.status().equals("CANCELED")) {
                throw conflict("this subscription is not scheduled to cancel");
            }
            jdbc.sql("UPDATE subscriptions SET cancel_at_period_end = false, updated_at = now() WHERE id = :id")
                    .param("id", id)
                    .update();
            auditLog.record(tenantId, "SUBSCRIPTION_RESUMED", "SUBSCRIPTION", id, Map.of());
        });
        return get(tenantId, id);
    }

    /** A new card is the usual cure for a failed charge, so an unpaid invoice is tried again straight away. */
    public SubscriptionDetail updateCard(UUID tenantId, UUID id, String cardToken) {
        List<UUID> open = tx.execute(status -> {
            tenantScope.apply(tenantId);
            Subscription subscription = lock(id);
            if (subscription.status().equals("CANCELED")) {
                throw conflict("this subscription is canceled");
            }
            jdbc.sql("UPDATE subscriptions SET card_token = :token, updated_at = now() WHERE id = :id")
                    .param("token", cardToken)
                    .param("id", id)
                    .update();
            auditLog.record(tenantId, "SUBSCRIPTION_CARD_UPDATED", "SUBSCRIPTION", id, Map.of());
            List<UUID> waiting = jdbc.sql("SELECT id FROM invoices WHERE subscription_id = :id AND status = 'OPEN' AND NOT attempt_in_flight")
                    .param("id", id)
                    .query(UUID.class)
                    .list();
            jdbc.sql("UPDATE invoices SET next_attempt_at = now(), updated_at = now() WHERE subscription_id = :id AND status = 'OPEN' AND NOT attempt_in_flight")
                    .param("id", id)
                    .update();
            return waiting;
        });
        open.forEach(invoiceId -> chargeIfDue(tenantId, invoiceId));
        return get(tenantId, id);
    }

    /** Bills the next period without waiting for it, so anyone trying the demo can see a renewal. */
    public SubscriptionDetail simulateRenewal(UUID tenantId, UUID id) {
        Optional<UUID> invoice = tx.execute(status -> {
            tenantScope.apply(tenantId);
            Subscription subscription = lock(id);
            if (!subscription.status().equals("ACTIVE")) {
                throw conflict("only an active subscription can renew");
            }
            return renewLocked(tenantId, subscription);
        });
        invoice.ifPresent(invoiceId -> chargeIfDue(tenantId, invoiceId));
        return get(tenantId, id);
    }

    /** Renews what has come due, then charges what is unpaid. Returns how many of each were handled. */
    public int runDue() {
        int handled = 0;
        for (Due due : findDue("SELECT id, tenant_id FROM find_subscriptions_to_renew(:limit)")) {
            try {
                Optional<UUID> invoice = tx.execute(status -> {
                    tenantScope.apply(due.tenantId());
                    Subscription subscription = lock(due.id());
                    // Another job may have renewed it between the listing and this lock.
                    boolean stillDue = subscription.status().equals("ACTIVE") && !subscription.periodEnd().isAfter(OffsetDateTime.now());
                    return stillDue ? renewLocked(due.tenantId(), subscription) : Optional.<UUID>empty();
                });
                invoice.ifPresent(invoiceId -> chargeIfDue(due.tenantId(), invoiceId));
                handled++;
            } catch (RuntimeException e) {
                // One merchant's problem must not hold up everyone else's billing. The next run retries it.
                log.error("Could not renew subscription {}", due.id(), e);
            }
        }
        for (Due due : findDue("SELECT id, tenant_id FROM find_invoices_to_charge(:limit)")) {
            try {
                chargeIfDue(due.tenantId(), due.id());
                handled++;
            } catch (RuntimeException e) {
                log.error("Could not charge invoice {}", due.id(), e);
            }
        }
        return handled;
    }

    public SubscriptionDetail get(UUID tenantId, UUID id) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            SubscriptionView subscription = jdbc.sql(SUBSCRIPTION_QUERY + " WHERE s.id = :id")
                    .param("id", id)
                    .query(BillingService::mapSubscriptionView)
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "subscription not found"));
            return new SubscriptionDetail(subscription, invoicesOf(id));
        });
    }

    public SubscriptionPage list(UUID tenantId, String statusFilter, UUID customerId, UUID before, int limit) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            StringBuilder sql = new StringBuilder(SUBSCRIPTION_QUERY + " WHERE true");
            Map<String, Object> params = new HashMap<>();
            params.put("limit", limit + 1);
            if (statusFilter != null) {
                sql.append(" AND s.status = :status");
                params.put("status", statusFilter);
            }
            if (customerId != null) {
                sql.append(" AND s.customer_id = :customerId");
                params.put("customerId", customerId);
            }
            if (before != null) {
                sql.append(" AND (s.created_at, s.id) < (SELECT created_at, id FROM subscriptions WHERE id = :before)");
                params.put("before", before);
            }
            sql.append(" ORDER BY s.created_at DESC, s.id DESC LIMIT :limit");
            List<SubscriptionView> rows = jdbc.sql(sql.toString()).params(params).query(BillingService::mapSubscriptionView).list();
            boolean hasMore = rows.size() > limit;
            List<SubscriptionView> page = hasMore ? rows.subList(0, limit) : rows;
            return new SubscriptionPage(page, hasMore ? page.getLast().id() : null);
        });
    }

    public Summary summary(UUID tenantId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            Map<String, Long> revenue = new LinkedHashMap<>();
            jdbc.sql("""
                            SELECT p.currency, p.amount, p.billing_interval
                            FROM subscriptions s
                            JOIN plans p ON p.id = s.plan_id
                            WHERE s.status IN ('ACTIVE', 'PAST_DUE') AND NOT s.cancel_at_period_end
                            ORDER BY p.currency
                            """)
                    .query((rs, rowNum) -> revenue.merge(rs.getString("currency"),
                            BillingMath.monthlyValue(rs.getLong("amount"), rs.getString("billing_interval")), Math::addExact))
                    .list();
            return jdbc.sql("""
                            SELECT COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active,
                                   COUNT(*) FILTER (WHERE status = 'PAST_DUE') AS past_due,
                                   COUNT(*) FILTER (WHERE status = 'CANCELED' AND canceled_at > now() - interval '30 days') AS canceled
                            FROM subscriptions
                            """)
                    .query((rs, rowNum) -> new Summary(
                            revenue.entrySet().stream().map(entry -> new CurrencyRevenue(entry.getKey(), entry.getValue())).toList(),
                            rs.getLong("active"),
                            rs.getLong("past_due"),
                            rs.getLong("canceled")))
                    .single();
        });
    }

    /** Expects the subscription to be locked. Moves it one period on and returns the invoice to charge, if any. */
    private Optional<UUID> renewLocked(UUID tenantId, Subscription subscription) {
        if (subscription.cancelAtPeriodEnd()) {
            markCanceled(tenantId, subscription.id(), "REQUESTED");
            return Optional.empty();
        }
        Plan plan = findPlan(subscription.planId()).orElseThrow();
        int nextIndex = subscription.periodIndex() + 1;
        OffsetDateTime start = subscription.periodEnd();
        OffsetDateTime end = BillingMath.periodEnd(subscription.anchor(), plan.interval(), nextIndex);
        jdbc.sql("""
                        UPDATE subscriptions
                        SET period_index = :index, current_period_start = :start, current_period_end = :end, updated_at = now()
                        WHERE id = :id
                        """)
                .param("index", nextIndex)
                .param("start", start)
                .param("end", end)
                .param("id", subscription.id())
                .update();
        UUID invoiceId = createInvoice(tenantId, subscription.id(), "RENEWAL", start, end, plan,
                List.of(new Line("PLAN", plan.name(), plan.amount())), subscription.creditBalance());
        return Optional.of(invoiceId);
    }

    /**
     * Uses up credit first. A negative total becomes credit for later, and an invoice with nothing left to pay
     * is settled here, because a payment of zero does not exist.
     */
    private UUID createInvoice(UUID tenantId, UUID subscriptionId, String kind, OffsetDateTime start, OffsetDateTime end, Plan plan,
                               List<Line> lines, long availableCredit) {
        long subtotal = lines.stream().mapToLong(Line::amount).reduce(0, Math::addExact);
        long creditApplied = Math.min(Math.max(subtotal, 0), availableCredit);
        long amount = subtotal - creditApplied > 0 ? subtotal - creditApplied : 0;
        long creditLeft = availableCredit - creditApplied + Math.max(-subtotal, 0);
        if (creditLeft != availableCredit) {
            jdbc.sql("UPDATE subscriptions SET credit_balance = :credit, updated_at = now() WHERE id = :id")
                    .param("credit", creditLeft)
                    .param("id", subscriptionId)
                    .update();
        }
        UUID id = UUID.randomUUID();
        boolean settled = amount == 0;
        jdbc.sql("""
                        INSERT INTO invoices (id, tenant_id, subscription_id, kind, status, period_start, period_end, subtotal,
                                              credit_applied, amount, currency, description, next_attempt_at, paid_at)
                        VALUES (:id, :tenantId, :subscriptionId, :kind, :status, :start, :end, :subtotal, :credit, :amount, :currency,
                                :description, CASE WHEN :settled THEN NULL ELSE now() END, CASE WHEN :settled THEN now() END)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("subscriptionId", subscriptionId)
                .param("kind", kind)
                .param("status", settled ? "PAID" : "OPEN")
                .param("start", start)
                .param("end", end)
                .param("subtotal", subtotal)
                .param("credit", creditApplied)
                .param("amount", amount)
                .param("currency", plan.currency())
                .param("description", "Abonelik: " + plan.name())
                .param("settled", settled)
                .update();
        int position = 0;
        for (Line line : lines) {
            jdbc.sql("""
                            INSERT INTO invoice_lines (tenant_id, invoice_id, position, kind, plan_name, amount)
                            VALUES (:tenantId, :invoiceId, :position, :kind, :planName, :amount)
                            """)
                    .param("tenantId", tenantId)
                    .param("invoiceId", id)
                    .param("position", position++)
                    .param("kind", line.kind())
                    .param("planName", line.planName())
                    .param("amount", line.amount())
                    .update();
        }
        return id;
    }

    private void chargeIfDue(UUID tenantId, UUID invoiceId) {
        Optional<Attempt> claimed = tx.execute(status -> {
            tenantScope.apply(tenantId);
            return claim(tenantId, invoiceId);
        });
        claimed.ifPresent(attempt -> {
            ChargeResult result = charge(tenantId, attempt);
            if (result.outcome() != Outcome.UNKNOWN) {
                tx.executeWithoutResult(status -> {
                    tenantScope.apply(tenantId);
                    finish(tenantId, attempt, result);
                });
            }
        });
    }

    /**
     * Only one job can hold an attempt, because claiming pushes the next attempt past the lease. An attempt still
     * marked in flight is resumed under its own number and card, which replays the same payment if one was made.
     */
    private Optional<Attempt> claim(UUID tenantId, UUID invoiceId) {
        UUID subscriptionId = jdbc.sql("SELECT subscription_id FROM invoices WHERE id = :id")
                .param("id", invoiceId)
                .query(UUID.class)
                .single();
        Subscription subscription = lock(subscriptionId);
        record Claimable(int attempts, boolean inFlight, String token, long amount, String currency, String description) {
        }
        Optional<Claimable> due = jdbc.sql("""
                        SELECT attempt_count, attempt_in_flight, attempt_token, amount, currency, description
                        FROM invoices
                        WHERE id = :id AND status = 'OPEN' AND next_attempt_at <= now()
                        FOR UPDATE
                        """)
                .param("id", invoiceId)
                .query((rs, rowNum) -> new Claimable(rs.getInt("attempt_count"), rs.getBoolean("attempt_in_flight"),
                        rs.getString("attempt_token"), rs.getLong("amount"), rs.getString("currency"), rs.getString("description")))
                .optional();
        if (due.isEmpty()) {
            return Optional.empty();
        }
        Claimable invoice = due.get();
        int number = invoice.inFlight() ? invoice.attempts() : invoice.attempts() + 1;
        String token = invoice.inFlight() ? invoice.token() : subscription.cardToken();
        jdbc.sql("""
                        UPDATE invoices
                        SET attempt_count = :number, attempt_token = :token, attempt_in_flight = true,
                            next_attempt_at = now() + make_interval(secs => :lease), updated_at = now()
                        WHERE id = :id
                        """)
                .param("number", number)
                .param("token", token)
                .param("lease", properties.lease().toSeconds())
                .param("id", invoiceId)
                .update();
        return Optional.of(new Attempt(invoiceId, number, token, invoice.amount(), invoice.currency(), invoice.description(),
                subscription.customerId(), subscriptionId));
    }

    /** Runs with no transaction open. Each step has a fixed key, so running it again only reads the earlier result. */
    private ChargeResult charge(UUID tenantId, Attempt attempt) {
        OperationResult created = payments.create(tenantId, attempt.paymentKey(), attempt.amount(), attempt.currency(),
                attempt.cardToken(), attempt.description(), attempt.customerId());
        UUID paymentId = created.payment().id();
        PaymentStatus status = created.payment().status();
        if (status == PaymentStatus.PENDING) {
            return new ChargeResult(Outcome.UNKNOWN, paymentId, null);
        }
        if (status == PaymentStatus.DECLINED || status == PaymentStatus.FAILED || status == PaymentStatus.VOIDED) {
            return new ChargeResult(Outcome.FAILED, paymentId, created.payment().failureCode());
        }
        if (status != PaymentStatus.AUTHORIZED) {
            return new ChargeResult(Outcome.PAID, paymentId, null);
        }
        OperationResult captured = payments.capture(tenantId, paymentId, attempt.paymentKey() + ":capture", null);
        if (captured.operation().status() == OperationStatus.PENDING) {
            return new ChargeResult(Outcome.UNKNOWN, paymentId, null);
        }
        if (captured.operation().status() == OperationStatus.FAILED) {
            // Otherwise the hold would keep blocking the customer's limit until the bank let it lapse.
            payments.voidPayment(tenantId, paymentId, attempt.paymentKey() + ":void");
            return new ChargeResult(Outcome.FAILED, paymentId, captured.operation().failureCode());
        }
        return new ChargeResult(Outcome.PAID, paymentId, null);
    }

    /** What a job does when its charge finally returns, reachable here because a test cannot pause a real job past its lease. */
    void reportLateFailure(UUID tenantId, UUID invoiceId, int attemptNumber) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            UUID subscriptionId = jdbc.sql("SELECT subscription_id FROM invoices WHERE id = :id")
                    .param("id", invoiceId)
                    .query(UUID.class)
                    .single();
            Attempt attempt = new Attempt(invoiceId, attemptNumber, null, 0, null, null, null, subscriptionId);
            finish(tenantId, attempt, new ChargeResult(Outcome.FAILED, null, "late_report"));
        });
    }

    private void finish(UUID tenantId, Attempt attempt, ChargeResult result) {
        Subscription subscription = lock(attempt.subscriptionId());
        record Open(String kind, String status, int attempts, boolean inFlight) {
        }
        Open invoice = jdbc.sql("SELECT kind, status, attempt_count, attempt_in_flight FROM invoices WHERE id = :id FOR UPDATE")
                .param("id", attempt.invoiceId())
                .query((rs, rowNum) -> new Open(rs.getString("kind"), rs.getString("status"), rs.getInt("attempt_count"),
                        rs.getBoolean("attempt_in_flight")))
                .single();
        // A job that ran past its lease may report an attempt another job has already settled.
        if (!invoice.status().equals("OPEN") || !invoice.inFlight() || invoice.attempts() != attempt.number()) {
            return;
        }
        if (result.outcome() == Outcome.PAID) {
            settlePaid(tenantId, attempt, result, subscription);
        } else {
            settleFailed(tenantId, attempt, result, subscription, invoice.kind());
        }
    }

    private void settlePaid(UUID tenantId, Attempt attempt, ChargeResult result, Subscription subscription) {
        jdbc.sql("""
                        UPDATE invoices
                        SET status = 'PAID', attempt_in_flight = false, next_attempt_at = NULL, payment_id = :payment,
                            failure_code = NULL, paid_at = now(), updated_at = now()
                        WHERE id = :id
                        """)
                .param("payment", result.paymentId())
                .param("id", attempt.invoiceId())
                .update();
        if (!subscription.status().equals("ACTIVE") && !subscription.status().equals("CANCELED")) {
            setStatus(subscription.id(), "ACTIVE");
        }
        webhookEvents.publish(tenantId, "invoice.paid", findInvoice(attempt.invoiceId()));
        metrics.countAfterCommit("kasa.invoices", "status", "PAID");
    }

    /** A sign-up that never paid simply ends. A running subscription gets the configured retries before it does. */
    private void settleFailed(UUID tenantId, Attempt attempt, ChargeResult result, Subscription subscription, String kind) {
        boolean firstCharge = kind.equals("FIRST");
        boolean retriesLeft = !firstCharge && attempt.number() <= properties.retryDelays().size();
        String status = retriesLeft ? "OPEN" : firstCharge ? "VOID" : "UNCOLLECTIBLE";
        Long retryAfter = retriesLeft ? properties.retryDelays().get(attempt.number() - 1).toSeconds() : null;
        jdbc.sql("""
                        UPDATE invoices
                        SET status = :status, attempt_in_flight = false, payment_id = :payment, failure_code = :code,
                            next_attempt_at = CASE WHEN :retry THEN now() + make_interval(secs => :seconds) END, updated_at = now()
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("payment", result.paymentId())
                .param("code", result.failureCode())
                .param("retry", retriesLeft)
                .param("seconds", retryAfter == null ? 0 : retryAfter)
                .param("id", attempt.invoiceId())
                .update();
        webhookEvents.publish(tenantId, "invoice.payment_failed", findInvoice(attempt.invoiceId()));
        metrics.countAfterCommit("kasa.invoices", "status", status);
        if (subscription.status().equals("CANCELED")) {
            return;
        }
        if (retriesLeft) {
            if (subscription.status().equals("ACTIVE")) {
                setStatus(subscription.id(), "PAST_DUE");
                notifications.toRoles(tenantId, NotificationService.OPERATORS, "SUBSCRIPTION_PAST_DUE", "Abonelik tahsilatı başarısız",
                        "Bir abonelik ödemesi alınamadı; otomatik olarak yeniden denenecek.", "/subscriptions");
                webhookEvents.publish(tenantId, "subscription.past_due", Map.of("id", subscription.id()));
            }
        } else {
            markCanceled(tenantId, subscription.id(), firstCharge ? "FIRST_PAYMENT_FAILED" : "PAYMENT_FAILED");
        }
    }

    private void markCanceled(UUID tenantId, UUID id, String reason) {
        jdbc.sql("""
                        UPDATE subscriptions
                        SET status = 'CANCELED', cancel_reason = :reason, canceled_at = now(), updated_at = now()
                        WHERE id = :id
                        """)
                .param("reason", reason)
                .param("id", id)
                .update();
        auditLog.record(tenantId, "SUBSCRIPTION_CANCELED", "SUBSCRIPTION", id, Map.of("reason", reason));
        webhookEvents.publish(tenantId, "subscription.canceled", Map.of("id", id, "reason", reason));
    }

    private void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE subscriptions SET status = :status, updated_at = now() WHERE id = :id")
                .param("status", status)
                .param("id", id)
                .update();
    }

    private List<Due> findDue(String sql) {
        return jdbc.sql(sql)
                .param("limit", BATCH_SIZE)
                .query((rs, rowNum) -> new Due(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)))
                .list();
    }

    private Subscription lock(UUID id) {
        return jdbc.sql("SELECT * FROM subscriptions WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, rowNum) -> new Subscription(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getObject("plan_id", UUID.class),
                        rs.getString("card_token"),
                        rs.getString("status"),
                        rs.getObject("billing_anchor", OffsetDateTime.class),
                        rs.getInt("period_index"),
                        rs.getObject("current_period_start", OffsetDateTime.class),
                        rs.getObject("current_period_end", OffsetDateTime.class),
                        rs.getBoolean("cancel_at_period_end"),
                        rs.getLong("credit_balance")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "subscription not found"));
    }

    private Optional<Plan> findPlan(UUID id) {
        return jdbc.sql("SELECT * FROM plans WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new Plan(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getLong("amount"),
                        rs.getString("currency"),
                        rs.getString("billing_interval"),
                        rs.getObject("archived_at", OffsetDateTime.class) != null))
                .optional();
    }

    private PlanView findPlanView(UUID id) {
        return jdbc.sql(PLAN_QUERY + " WHERE p.id = :id")
                .param("id", id)
                .query(BillingService::mapPlanView)
                .single();
    }

    private InvoiceView findInvoice(UUID id) {
        return jdbc.sql("SELECT * FROM invoices WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> mapInvoice(rs, linesOf(id)))
                .single();
    }

    private List<InvoiceView> invoicesOf(UUID subscriptionId) {
        List<UUID> ids = jdbc.sql("SELECT id FROM invoices WHERE subscription_id = :id ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("id", subscriptionId)
                .param("limit", LIST_LIMIT)
                .query(UUID.class)
                .list();
        List<InvoiceView> invoices = new ArrayList<>();
        ids.forEach(id -> invoices.add(findInvoice(id)));
        return invoices;
    }

    private List<LineView> linesOf(UUID invoiceId) {
        return jdbc.sql("SELECT kind, plan_name, amount FROM invoice_lines WHERE invoice_id = :id ORDER BY position")
                .param("id", invoiceId)
                .query((rs, rowNum) -> new LineView(rs.getString("kind"), rs.getString("plan_name"), rs.getLong("amount")))
                .list();
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }

    private static final String PLAN_QUERY = """
            SELECT p.*, (SELECT COUNT(*) FROM subscriptions s
                         WHERE s.plan_id = p.id AND s.status IN ('ACTIVE', 'PAST_DUE')) AS active_subscriptions
            FROM plans p
            """;

    private static final String SUBSCRIPTION_QUERY = """
            SELECT s.*, c.email, c.name AS customer_name, p.name AS plan_name, p.amount, p.currency, p.billing_interval
            FROM subscriptions s
            JOIN customers c ON c.id = s.customer_id
            JOIN plans p ON p.id = s.plan_id
            """;

    private static PlanView mapPlanView(ResultSet rs, int rowNum) throws SQLException {
        return new PlanView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getString("billing_interval"),
                rs.getObject("archived_at", OffsetDateTime.class) != null,
                rs.getLong("active_subscriptions"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    /** The card token is left out on purpose: it can charge the customer, and nothing on screen needs it. */
    private static SubscriptionView mapSubscriptionView(ResultSet rs, int rowNum) throws SQLException {
        String name = rs.getString("customer_name");
        return new SubscriptionView(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                name != null ? name : rs.getString("email"),
                rs.getObject("plan_id", UUID.class),
                rs.getString("plan_name"),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getString("billing_interval"),
                rs.getString("status"),
                rs.getObject("current_period_start", OffsetDateTime.class),
                rs.getObject("current_period_end", OffsetDateTime.class),
                rs.getBoolean("cancel_at_period_end"),
                rs.getLong("credit_balance"),
                rs.getString("cancel_reason"),
                rs.getObject("canceled_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static InvoiceView mapInvoice(ResultSet rs, List<LineView> lines) throws SQLException {
        return new InvoiceView(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getObject("period_start", OffsetDateTime.class),
                rs.getObject("period_end", OffsetDateTime.class),
                rs.getLong("subtotal"),
                rs.getLong("credit_applied"),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getInt("attempt_count"),
                rs.getObject("next_attempt_at", OffsetDateTime.class),
                rs.getObject("payment_id", UUID.class),
                rs.getString("failure_code"),
                rs.getObject("paid_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                lines);
    }
}
