package com.kasa.settlement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.bank.BankGateway;
import com.kasa.bank.BankResponse;
import com.kasa.bank.BankTimeoutException;
import com.kasa.ledger.AccountType;
import com.kasa.ledger.LedgerService;
import com.kasa.ledger.NewJournalEntry;
import com.kasa.ledger.Posting;
import com.kasa.observability.BusinessMetrics;
import com.kasa.settlement.PayoutViews.PayoutDetail;
import com.kasa.settlement.PayoutViews.PayoutItemView;
import com.kasa.settlement.PayoutViews.PayoutView;
import com.kasa.tenant.TenantScope;
import com.kasa.webhook.WebhookEvents;

/**
 * Pays merchants what they have earned. A payout first moves the money to an in-transit account and commits,
 * then calls the bank with no transaction open, then settles or reverses the in-transit amount. As with
 * payments, a timeout or crash leaves a PENDING payout that {@link #recoverStale} resolves with the bank.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private static final String NO_RECORD_AT_BANK = "bank_no_record";
    private static final int RECOVERY_BATCH_SIZE = 100;
    private static final int LIST_LIMIT = 100;

    private record UnsettledTenant(UUID tenantId, String currency) {
    }

    private record StalePayout(UUID id, UUID tenantId) {
    }

    private record UnsettledItem(UUID operationId, UUID paymentId, String type, long grossAmount, long feeAmount) {
        /** The fee stays with the platform on capture and is not returned on refund. */
        long netAmount() {
            return type.equals("CAPTURE") ? grossAmount - feeAmount : -grossAmount;
        }
    }

    private record NewPayout(UUID id, long amount) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final LedgerService ledger;
    private final BankGateway bank;
    private final WebhookEvents webhookEvents;
    private final BusinessMetrics metrics;

    public SettlementService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, LedgerService ledger,
                             BankGateway bank, WebhookEvents webhookEvents, BusinessMetrics metrics) {
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.ledger = ledger;
        this.bank = bank;
        this.webhookEvents = webhookEvents;
    }

    /** Settles every operation completed before the cutoff. Returns how many payouts were created. */
    public int settleAll(Instant cutoff) {
        LocalDate settlementDate = LocalDate.ofInstant(cutoff, ZoneOffset.UTC);
        List<UnsettledTenant> tenants = jdbc.sql("SELECT tenant_id, currency FROM find_unsettled_tenants(:cutoff)")
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .query((rs, rowNum) -> new UnsettledTenant(rs.getObject("tenant_id", UUID.class), rs.getString("currency")))
                .list();
        int created = 0;
        for (UnsettledTenant tenant : tenants) {
            try {
                if (settle(tenant, cutoff, settlementDate)) {
                    created++;
                }
            } catch (RuntimeException e) {
                // One merchant's problem must not stop everyone else's payout. The next run retries it.
                log.error("Could not settle tenant {} in {}", tenant.tenantId(), tenant.currency(), e);
            }
        }
        return created;
    }

    /** Asks the bank what happened to payouts stuck in PENDING. Returns how many were resolved. */
    public int recoverStale(Duration olderThan) {
        List<StalePayout> stale = jdbc.sql("SELECT id, tenant_id FROM find_stale_payouts(:seconds, :limit)")
                .param("seconds", olderThan.toSeconds())
                .param("limit", RECOVERY_BATCH_SIZE)
                .query((rs, rowNum) -> new StalePayout(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)))
                .list();
        int resolved = 0;
        for (StalePayout payout : stale) {
            try {
                BankResponse response = bank.lookup(payout.id()).orElseGet(() -> BankResponse.declined(NO_RECORD_AT_BANK));
                applyOutcome(payout.tenantId(), payout.id(), response);
                resolved++;
            } catch (BankTimeoutException e) {
                log.warn("Bank lookup timed out for payout {}; will retry", payout.id());
            } catch (RuntimeException e) {
                log.error("Could not recover payout {}", payout.id(), e);
            }
        }
        return resolved;
    }

    public List<PayoutView> list(UUID tenantId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            return jdbc.sql("SELECT * FROM payouts ORDER BY created_at DESC, id DESC LIMIT :limit")
                    .param("limit", LIST_LIMIT)
                    .query(SettlementService::mapPayout)
                    .list();
        });
    }

    public PayoutDetail get(UUID tenantId, UUID payoutId) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            PayoutView payout = jdbc.sql("SELECT * FROM payouts WHERE id = :id")
                    .param("id", payoutId)
                    .query(SettlementService::mapPayout)
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payout not found"));
            List<PayoutItemView> items = jdbc.sql("""
                            SELECT payment_id, operation_id, type, gross_amount, fee_amount, net_amount, released_at
                            FROM payout_items
                            WHERE payout_id = :id
                            ORDER BY id
                            """)
                    .param("id", payoutId)
                    .query((rs, rowNum) -> new PayoutItemView(
                            rs.getObject("payment_id", UUID.class),
                            rs.getObject("operation_id", UUID.class),
                            rs.getString("type"),
                            rs.getLong("gross_amount"),
                            rs.getLong("fee_amount"),
                            rs.getLong("net_amount"),
                            rs.getObject("released_at", OffsetDateTime.class) != null))
                    .list();
            return new PayoutDetail(payout, items);
        });
    }

    private boolean settle(UnsettledTenant tenant, Instant cutoff, LocalDate settlementDate) {
        Optional<NewPayout> created = tx.execute(status -> createPayout(tenant, cutoff, settlementDate));
        if (created.isEmpty()) {
            return false;
        }
        NewPayout payout = created.get();
        try {
            applyOutcome(tenant.tenantId(), payout.id(),
                    bank.payout(payout.id(), tenant.tenantId(), payout.amount(), tenant.currency()));
        } catch (BankTimeoutException e) {
            log.warn("Bank timed out on payout {}; leaving it for recovery", payout.id());
        }
        return true;
    }

    private Optional<NewPayout> createPayout(UnsettledTenant tenant, Instant cutoff, LocalDate settlementDate) {
        UUID tenantId = tenant.tenantId();
        String currency = tenant.currency();
        tenantScope.apply(tenantId);
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "settlement:" + tenantId + ":" + currency)
                .query((rs, rowNum) -> 1)
                .single();

        boolean alreadySettled = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM payouts
                                       WHERE currency = :currency AND settlement_date = :date AND status <> 'FAILED')
                        """)
                .param("currency", currency)
                .param("date", settlementDate)
                .query(Boolean.class)
                .single();
        if (alreadySettled) {
            return Optional.empty();
        }

        List<UnsettledItem> items = findUnsettledItems(currency, cutoff);
        long net = items.stream().mapToLong(UnsettledItem::netAmount).reduce(0, Math::addExact);
        // A non-positive net carries forward: the items stay unsettled and are offset by future captures.
        if (net <= 0) {
            return Optional.empty();
        }
        // Refunds after the cutoff are not items yet but have already reduced what the merchant holds.
        long available = ledger.ensureAccount(tenantId, "merchant_payable", AccountType.LIABILITY, currency, true).balance();
        if (available < net) {
            log.info("Tenant {} holds {} but is due {} in {}; carrying forward", tenantId, available, net, currency);
            return Optional.empty();
        }

        UUID payoutId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO payouts (id, tenant_id, currency, amount, status, settlement_date, cutoff)
                        VALUES (:id, :tenantId, :currency, :amount, 'PENDING', :date, :cutoff)
                        """)
                .param("id", payoutId)
                .param("tenantId", tenantId)
                .param("currency", currency)
                .param("amount", net)
                .param("date", settlementDate)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .update();
        for (UnsettledItem item : items) {
            jdbc.sql("""
                            INSERT INTO payout_items (tenant_id, payout_id, operation_id, payment_id, type,
                                                      gross_amount, fee_amount, net_amount)
                            VALUES (:tenantId, :payoutId, :operationId, :paymentId, :type, :gross, :fee, :net)
                            """)
                    .param("tenantId", tenantId)
                    .param("payoutId", payoutId)
                    .param("operationId", item.operationId())
                    .param("paymentId", item.paymentId())
                    .param("type", item.type())
                    .param("gross", item.grossAmount())
                    .param("fee", item.feeAmount())
                    .param("net", item.netAmount())
                    .update();
        }
        post(tenantId, currency, payoutId, "reserve", net, "merchant_payable", AccountType.LIABILITY,
                "payout_in_transit", AccountType.LIABILITY);
        return Optional.of(new NewPayout(payoutId, net));
    }

    private List<UnsettledItem> findUnsettledItems(String currency, Instant cutoff) {
        return jdbc.sql("""
                        SELECT o.id, o.payment_id, o.type, o.amount,
                               CASE WHEN o.type = 'CAPTURE' THEN p.fee_amount ELSE 0 END AS fee
                        FROM payment_operations o
                        JOIN payments p ON p.id = o.payment_id
                        WHERE o.status = 'SUCCEEDED' AND o.type IN ('CAPTURE', 'REFUND')
                          AND o.updated_at < :cutoff AND p.currency = :currency
                          AND NOT EXISTS (SELECT 1 FROM payout_items i
                                          WHERE i.operation_id = o.id AND i.released_at IS NULL)
                        ORDER BY o.updated_at, o.id
                        """)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .param("currency", currency)
                .query((rs, rowNum) -> new UnsettledItem(
                        rs.getObject("id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getString("type"),
                        rs.getLong("amount"),
                        rs.getLong("fee")))
                .list();
    }

    /** The PENDING check under the row lock makes this safe when a job run and recovery report the same outcome. */
    private void applyOutcome(UUID tenantId, UUID payoutId, BankResponse response) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            PayoutView payout = jdbc.sql("SELECT * FROM payouts WHERE id = :id FOR UPDATE")
                    .param("id", payoutId)
                    .query(SettlementService::mapPayout)
                    .single();
            if (!payout.status().equals("PENDING")) {
                return;
            }
            if (response.approved()) {
                jdbc.sql("""
                                UPDATE payouts
                                SET status = 'PAID', bank_reference = :reference, paid_at = now(), updated_at = now()
                                WHERE id = :id
                                """)
                        .param("reference", response.reference())
                        .param("id", payoutId)
                        .update();
                post(tenantId, payout.currency(), payoutId, "paid", payout.amount(), "payout_in_transit",
                        AccountType.LIABILITY, "psp_clearing", AccountType.ASSET);
            } else {
                jdbc.sql("UPDATE payouts SET status = 'FAILED', failure_code = :code, updated_at = now() WHERE id = :id")
                        .param("code", response.declineCode())
                        .param("id", payoutId)
                        .update();
                jdbc.sql("UPDATE payout_items SET released_at = now() WHERE payout_id = :id")
                        .param("id", payoutId)
                        .update();
                post(tenantId, payout.currency(), payoutId, "reversed", payout.amount(), "payout_in_transit",
                        AccountType.LIABILITY, "merchant_payable", AccountType.LIABILITY);
            }
            PayoutView updated = jdbc.sql("SELECT * FROM payouts WHERE id = :id")
                    .param("id", payoutId)
                    .query(SettlementService::mapPayout)
                    .single();
            webhookEvents.publish(tenantId, response.approved() ? "payout.paid" : "payout.failed", updated);
            metrics.countAfterCommit("kasa.payouts", "status", updated.status());
        });
    }

    private void post(UUID tenantId, String currency, UUID payoutId, String step, long amount, String debitCode,
                      AccountType debitType, String creditCode, AccountType creditType) {
        UUID debit = ledger.ensureAccount(tenantId, debitCode, debitType, currency, allowsNegative(debitCode)).id();
        UUID credit = ledger.ensureAccount(tenantId, creditCode, creditType, currency, allowsNegative(creditCode)).id();
        ledger.post(tenantId, new NewJournalEntry("payout:" + payoutId + ":" + step, "payout " + step, List.of(
                Posting.debit(debit, amount),
                Posting.credit(credit, amount))));
    }

    private static boolean allowsNegative(String accountCode) {
        return !accountCode.equals("payout_in_transit");
    }

    private static PayoutView mapPayout(ResultSet rs, int rowNum) throws SQLException {
        return new PayoutView(
                rs.getObject("id", UUID.class),
                rs.getString("currency"),
                rs.getLong("amount"),
                rs.getString("status"),
                rs.getObject("settlement_date", LocalDate.class),
                rs.getString("failure_code"),
                rs.getObject("paid_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
