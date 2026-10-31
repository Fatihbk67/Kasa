package com.kasa.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kasa.tenant.TenantScope;

@Service
public class LedgerService {

    private final JdbcClient jdbc;
    private final TenantScope tenantScope;

    public LedgerService(JdbcClient jdbc, TenantScope tenantScope) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
    }

    @Transactional
    public LedgerAccount createAccount(UUID tenantId, String code, AccountType type, String currency,
                                       boolean allowNegative) {
        tenantScope.apply(tenantId);
        return jdbc.sql("""
                        INSERT INTO ledger_accounts (tenant_id, code, type, currency, allow_negative)
                        VALUES (:tenantId, :code, :type, :currency, :allowNegative)
                        RETURNING *
                        """)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("type", type.name())
                .param("currency", currency)
                .param("allowNegative", allowNegative)
                .query(LedgerService::mapAccount)
                .single();
    }

    public record MerchantBalance(String currency, long available, long inTransit) {
    }

    /** What the merchant is owed. The platform's own accounts, such as fee revenue, stay out of this view. */
    @Transactional(readOnly = true)
    public List<MerchantBalance> merchantBalances(UUID tenantId) {
        tenantScope.apply(tenantId);
        return jdbc.sql("""
                        SELECT currency,
                               COALESCE(SUM(balance) FILTER (WHERE code = 'merchant_payable'), 0) AS available,
                               COALESCE(SUM(balance) FILTER (WHERE code = 'payout_in_transit'), 0) AS in_transit
                        FROM ledger_accounts
                        WHERE code IN ('merchant_payable', 'payout_in_transit')
                        GROUP BY currency
                        ORDER BY currency
                        """)
                .query((rs, rowNum) -> new MerchantBalance(
                        rs.getString("currency"), rs.getLong("available"), rs.getLong("in_transit")))
                .list();
    }

    /** Returns the account, creating it first if needed. Safe to call concurrently. */
    @Transactional
    public LedgerAccount ensureAccount(UUID tenantId, String code, AccountType type, String currency,
                                       boolean allowNegative) {
        tenantScope.apply(tenantId);
        jdbc.sql("""
                        INSERT INTO ledger_accounts (tenant_id, code, type, currency, allow_negative)
                        VALUES (:tenantId, :code, :type, :currency, :allowNegative)
                        ON CONFLICT (tenant_id, code, currency) DO NOTHING
                        """)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("type", type.name())
                .param("currency", currency)
                .param("allowNegative", allowNegative)
                .update();
        return jdbc.sql("SELECT * FROM ledger_accounts WHERE tenant_id = :tenantId AND code = :code AND currency = :currency")
                .param("tenantId", tenantId)
                .param("code", code)
                .param("currency", currency)
                .query(LedgerService::mapAccount)
                .single();
    }

    @Transactional(readOnly = true)
    public LedgerAccount getAccount(UUID tenantId, UUID accountId) {
        tenantScope.apply(tenantId);
        return jdbc.sql("SELECT * FROM ledger_accounts WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", accountId)
                .query(LedgerService::mapAccount)
                .optional()
                .orElseThrow(() -> new LedgerException.AccountNotFound(accountId));
    }

    @Transactional
    public PostedEntry post(UUID tenantId, NewJournalEntry request) {
        tenantScope.apply(tenantId);
        String fingerprint = request.fingerprint();
        UUID entryId = UUID.randomUUID();

        // A concurrent request with the same key blocks here until the first one commits or rolls back.
        int inserted = jdbc.sql("""
                        INSERT INTO journal_entries (id, tenant_id, idempotency_key, request_hash, description)
                        VALUES (:id, :tenantId, :key, :hash, :description)
                        ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                        """)
                .param("id", entryId)
                .param("tenantId", tenantId)
                .param("key", request.idempotencyKey())
                .param("hash", fingerprint)
                .param("description", request.description())
                .update();
        if (inserted == 0) {
            return replay(tenantId, request.idempotencyKey(), fingerprint);
        }

        Map<UUID, LedgerAccount> accounts = lockAccounts(tenantId, request.postings());
        assertBalanced(request.postings(), accounts);

        for (Posting posting : request.postings()) {
            jdbc.sql("""
                            INSERT INTO ledger_postings (entry_id, tenant_id, account_id, currency, direction, amount)
                            VALUES (:entryId, :tenantId, :accountId, :currency, :direction, :amount)
                            """)
                    .param("entryId", entryId)
                    .param("tenantId", tenantId)
                    .param("accountId", posting.accountId())
                    .param("currency", accounts.get(posting.accountId()).currency())
                    .param("direction", posting.direction().name())
                    .param("amount", posting.amount())
                    .update();
        }
        applyBalances(request.postings(), accounts);

        return new PostedEntry(entryId, false);
    }

    /** Returns accounts whose stored balance no longer matches the sum of their postings. */
    @Transactional(readOnly = true)
    public List<UUID> findDriftedAccounts(UUID tenantId) {
        tenantScope.apply(tenantId);
        return jdbc.sql("""
                        SELECT a.id
                        FROM ledger_accounts a
                        LEFT JOIN ledger_postings p ON p.account_id = a.id
                        WHERE a.tenant_id = :tenantId
                        GROUP BY a.id, a.type, a.balance
                        HAVING a.balance <> COALESCE(SUM(
                            CASE WHEN (p.direction = 'DEBIT') = (a.type IN ('ASSET', 'EXPENSE'))
                                 THEN p.amount ELSE -p.amount END), 0)
                        """)
                .param("tenantId", tenantId)
                .query(UUID.class)
                .list();
    }

    private PostedEntry replay(UUID tenantId, String key, String fingerprint) {
        return jdbc.sql("""
                        SELECT id, request_hash
                        FROM journal_entries
                        WHERE tenant_id = :tenantId AND idempotency_key = :key
                        """)
                .param("tenantId", tenantId)
                .param("key", key)
                .query((rs, rowNum) -> {
                    if (!fingerprint.equals(rs.getString("request_hash"))) {
                        throw new LedgerException.IdempotencyConflict(key);
                    }
                    return new PostedEntry(rs.getObject("id", UUID.class), true);
                })
                .single();
    }

    /** Locks in id order, so entries touching the same accounts in opposite order cannot deadlock. */
    private Map<UUID, LedgerAccount> lockAccounts(UUID tenantId, List<Posting> postings) {
        Set<UUID> ids = postings.stream().map(Posting::accountId).collect(Collectors.toSet());
        Map<UUID, LedgerAccount> accounts = new HashMap<>();
        jdbc.sql("""
                        SELECT *
                        FROM ledger_accounts
                        WHERE tenant_id = :tenantId AND id IN (:ids)
                        ORDER BY id
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId)
                .param("ids", ids)
                .query(LedgerService::mapAccount)
                .list()
                .forEach(account -> accounts.put(account.id(), account));
        for (UUID id : ids) {
            if (!accounts.containsKey(id)) {
                throw new LedgerException.AccountNotFound(id);
            }
        }
        return accounts;
    }

    private void assertBalanced(List<Posting> postings, Map<UUID, LedgerAccount> accounts) {
        Map<String, Long> netByCurrency = new HashMap<>();
        for (Posting posting : postings) {
            long signed = posting.direction() == Direction.DEBIT ? posting.amount() : -posting.amount();
            netByCurrency.merge(accounts.get(posting.accountId()).currency(), signed, Math::addExact);
        }
        netByCurrency.forEach((currency, net) -> {
            if (net != 0) {
                throw new LedgerException.UnbalancedEntry(currency);
            }
        });
    }

    private void applyBalances(List<Posting> postings, Map<UUID, LedgerAccount> accounts) {
        Map<UUID, Long> deltas = new LinkedHashMap<>();
        for (Posting posting : postings) {
            long delta = accounts.get(posting.accountId()).type().balanceDelta(posting.direction(), posting.amount());
            deltas.merge(posting.accountId(), delta, Math::addExact);
        }
        deltas.forEach((accountId, delta) -> {
            LedgerAccount account = accounts.get(accountId);
            long balance = Math.addExact(account.balance(), delta);
            if (balance < 0 && !account.allowNegative()) {
                throw new LedgerException.InsufficientFunds(accountId);
            }
            jdbc.sql("UPDATE ledger_accounts SET balance = :balance WHERE id = :id")
                    .param("balance", balance)
                    .param("id", accountId)
                    .update();
        });
    }

    private static LedgerAccount mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("code"),
                AccountType.valueOf(rs.getString("type")),
                rs.getString("currency"),
                rs.getBoolean("allow_negative"),
                rs.getLong("balance"));
    }
}
