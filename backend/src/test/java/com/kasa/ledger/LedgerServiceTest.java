package com.kasa.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;

class LedgerServiceTest extends PostgresIntegrationTest {

    @Autowired
    LedgerService ledger;

    UUID tenant;
    UUID clearing;
    UUID merchantPayable;
    UUID feeRevenue;

    @BeforeEach
    void createChartOfAccounts() {
        tenant = newTenant();
        clearing = ledger.createAccount(tenant, "psp_clearing", AccountType.ASSET, "TRY", false).id();
        merchantPayable = ledger.createAccount(tenant, "merchant_payable", AccountType.LIABILITY, "TRY", false).id();
        feeRevenue = ledger.createAccount(tenant, "fee_revenue", AccountType.REVENUE, "TRY", false).id();
    }

    @Test
    void captureSplitsTheAmountBetweenMerchantAndFee() {
        capture("capture-1", 10_000, 300);

        assertThat(balance(clearing)).isEqualTo(10_000);
        assertThat(balance(merchantPayable)).isEqualTo(9_700);
        assertThat(balance(feeRevenue)).isEqualTo(300);
        assertThat(ledger.findDriftedAccounts(tenant)).isEmpty();
    }

    @Test
    void rejectsAnEntryWhoseDebitsAndCreditsDiffer() {
        NewJournalEntry entry = new NewJournalEntry("bad", "unbalanced", List.of(
                Posting.debit(clearing, 100),
                Posting.credit(merchantPayable, 99)));

        assertThatThrownBy(() -> ledger.post(tenant, entry)).isInstanceOf(LedgerException.UnbalancedEntry.class);

        assertThat(balance(clearing)).isZero();
        assertThat(entryCount("bad")).isZero();
    }

    @Test
    void rejectsAPayoutLargerThanTheBalanceAndLeavesNothingBehind() {
        capture("capture-1", 1_000, 0);

        assertThatThrownBy(() -> payout("payout-1", 1_001)).isInstanceOf(LedgerException.InsufficientFunds.class);

        assertThat(balance(merchantPayable)).isEqualTo(1_000);
        assertThat(entryCount("payout-1")).isZero();
    }

    @Test
    void cannotPostToAnotherTenantsAccount() {
        UUID otherTenant = newTenant();
        UUID foreign = ledger.createAccount(otherTenant, "merchant_payable", AccountType.LIABILITY, "TRY", false).id();
        NewJournalEntry entry = new NewJournalEntry("cross-tenant", null, List.of(
                Posting.debit(clearing, 100),
                Posting.credit(foreign, 100)));

        assertThatThrownBy(() -> ledger.post(tenant, entry)).isInstanceOf(LedgerException.AccountNotFound.class);

        assertThat(ledger.getAccount(otherTenant, foreign).balance()).isZero();
    }

    @Test
    void rejectsAnEntryThatMixesCurrencies() {
        UUID usdPayable = ledger.createAccount(tenant, "merchant_payable", AccountType.LIABILITY, "USD", false).id();
        NewJournalEntry entry = new NewJournalEntry("fx", null, List.of(
                Posting.debit(clearing, 100),
                Posting.credit(usdPayable, 100)));

        assertThatThrownBy(() -> ledger.post(tenant, entry)).isInstanceOf(LedgerException.UnbalancedEntry.class);
    }

    @Test
    void replayingAKeyReturnsTheFirstResultWithoutPostingAgain() {
        PostedEntry first = capture("capture-1", 1_000, 0);
        PostedEntry second = capture("capture-1", 1_000, 0);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.entryId()).isEqualTo(first.entryId());
        assertThat(balance(merchantPayable)).isEqualTo(1_000);
    }

    @Test
    void reusingAKeyForADifferentRequestIsAnError() {
        capture("capture-1", 1_000, 0);

        assertThatThrownBy(() -> capture("capture-1", 2_000, 0))
                .isInstanceOf(LedgerException.IdempotencyConflict.class);

        assertThat(balance(merchantPayable)).isEqualTo(1_000);
    }

    @Test
    void concurrentDuplicatesPostExactlyOnce() throws Exception {
        List<PostedEntry> results = Concurrency.run(20, i -> () -> capture("capture-1", 1_000, 0));

        assertThat(results.stream().filter(result -> !result.replayed())).hasSize(1);
        assertThat(results.stream().map(PostedEntry::entryId).distinct()).hasSize(1);
        assertThat(balance(merchantPayable)).isEqualTo(1_000);
    }

    @Test
    void concurrentPayoutsCannotOverdraw() throws Exception {
        capture("capture-1", 1_000, 0);

        List<Boolean> results = Concurrency.run(50, i -> () -> {
            try {
                payout("payout-" + i, 100);
                return true;
            } catch (LedgerException.InsufficientFunds e) {
                return false;
            }
        });

        assertThat(results.stream().filter(paid -> paid)).hasSize(10);
        assertThat(balance(merchantPayable)).isZero();
        assertThat(ledger.findDriftedAccounts(tenant)).isEmpty();
    }

    @Test
    void transfersInOppositeDirectionsDoNotDeadlock() throws Exception {
        UUID a = ledger.createAccount(tenant, "a", AccountType.ASSET, "TRY", true).id();
        UUID b = ledger.createAccount(tenant, "b", AccountType.ASSET, "TRY", true).id();

        List<PostedEntry> results = Concurrency.run(100, i -> () -> {
            UUID from = i % 2 == 0 ? a : b;
            UUID to = i % 2 == 0 ? b : a;
            return ledger.post(tenant, new NewJournalEntry("transfer-" + i, null, List.of(
                    Posting.credit(from, 10),
                    Posting.debit(to, 10))));
        });

        assertThat(results).hasSize(100);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isZero();
        assertThat(ledger.findDriftedAccounts(tenant)).isEmpty();
    }

    @Test
    void databaseRejectsAnUnbalancedEntryEvenWithoutTheService() {
        assertThatThrownBy(() -> ownerTx.executeWithoutResult(status -> {
            UUID entryId = insertEntryDirectly("direct");
            insertPostingDirectly(entryId, clearing, "DEBIT", 100);
        })).hasStackTraceContaining("unbalanced");

        assertThat(entryCount("direct")).isZero();
    }

    @Test
    void databaseRejectsAnEntryWithNoPostings() {
        assertThatThrownBy(() -> ownerTx.executeWithoutResult(status -> insertEntryDirectly("empty")))
                .hasStackTraceContaining("unbalanced");
    }

    @Test
    void postingsCannotBeChangedOrDeleted() {
        capture("capture-1", 1_000, 0);

        assertThatThrownBy(() -> owner.sql("UPDATE ledger_postings SET amount = 1 WHERE tenant_id = :t")
                .param("t", tenant).update()).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> owner.sql("DELETE FROM ledger_postings WHERE tenant_id = :t")
                .param("t", tenant).update()).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> owner.sql("TRUNCATE ledger_postings").update())
                .hasStackTraceContaining("append-only");
    }

    @Test
    void reconciliationDetectsATamperedBalance() {
        capture("capture-1", 1_000, 0);

        owner.sql("UPDATE ledger_accounts SET balance = balance + 1 WHERE id = :id")
                .param("id", merchantPayable)
                .update();

        assertThat(ledger.findDriftedAccounts(tenant)).containsExactly(merchantPayable);
    }

    private PostedEntry capture(String key, long amount, long fee) {
        List<Posting> postings = new ArrayList<>();
        postings.add(Posting.debit(clearing, amount));
        postings.add(Posting.credit(merchantPayable, amount - fee));
        if (fee > 0) {
            postings.add(Posting.credit(feeRevenue, fee));
        }
        return ledger.post(tenant, new NewJournalEntry(key, "capture", postings));
    }

    private PostedEntry payout(String key, long amount) {
        return ledger.post(tenant, new NewJournalEntry(key, "payout", List.of(
                Posting.debit(merchantPayable, amount),
                Posting.credit(clearing, amount))));
    }

    private long balance(UUID accountId) {
        return ledger.getAccount(tenant, accountId).balance();
    }

    private int entryCount(String key) {
        return owner.sql("SELECT count(*) FROM journal_entries WHERE tenant_id = :t AND idempotency_key = :k")
                .param("t", tenant)
                .param("k", key)
                .query(Integer.class)
                .single();
    }

    private UUID newTenant() {
        return owner.sql("INSERT INTO tenants (name) VALUES ('test') RETURNING id")
                .query(UUID.class)
                .single();
    }

    private UUID insertEntryDirectly(String key) {
        UUID entryId = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO journal_entries (id, tenant_id, idempotency_key, request_hash)
                        VALUES (:id, :t, :k, 'x')
                        """)
                .param("id", entryId)
                .param("t", tenant)
                .param("k", key)
                .update();
        return entryId;
    }

    private void insertPostingDirectly(UUID entryId, UUID accountId, String direction, long amount) {
        owner.sql("""
                        INSERT INTO ledger_postings (entry_id, tenant_id, account_id, currency, direction, amount)
                        VALUES (:e, :t, :a, 'TRY', :d, :amount)
                        """)
                .param("e", entryId)
                .param("t", tenant)
                .param("a", accountId)
                .param("d", direction)
                .param("amount", amount)
                .update();
    }
}
