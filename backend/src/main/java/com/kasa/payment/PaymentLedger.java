package com.kasa.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.kasa.ledger.AccountType;
import com.kasa.ledger.LedgerService;
import com.kasa.ledger.NewJournalEntry;
import com.kasa.ledger.Posting;

/** Translates payment events into ledger entries, keyed by operation so an event can never post twice. */
@Component
class PaymentLedger {

    private final LedgerService ledger;

    PaymentLedger(LedgerService ledger) {
        this.ledger = ledger;
    }

    void recordCapture(UUID tenantId, String currency, UUID operationId, long amount, long fee) {
        List<Posting> postings = new ArrayList<>();
        postings.add(Posting.debit(clearing(tenantId, currency), amount));
        if (amount > fee) {
            postings.add(Posting.credit(merchantPayable(tenantId, currency), amount - fee));
        }
        if (fee > 0) {
            postings.add(Posting.credit(feeRevenue(tenantId, currency), fee));
        }
        ledger.post(tenantId, new NewJournalEntry(entryKey(operationId), "payment capture", postings));
    }

    /** The processing fee is not returned, so a full refund leaves the merchant owing the fee. */
    void recordRefund(UUID tenantId, String currency, UUID operationId, long amount) {
        ledger.post(tenantId, new NewJournalEntry(entryKey(operationId), "payment refund", List.of(
                Posting.debit(merchantPayable(tenantId, currency), amount),
                Posting.credit(clearing(tenantId, currency), amount))));
    }

    private UUID clearing(UUID tenantId, String currency) {
        return ledger.ensureAccount(tenantId, "psp_clearing", AccountType.ASSET, currency, true).id();
    }

    // May go negative: by the time a refund is recorded the bank has already paid it, so it must not fail here.
    private UUID merchantPayable(UUID tenantId, String currency) {
        return ledger.ensureAccount(tenantId, "merchant_payable", AccountType.LIABILITY, currency, true).id();
    }

    private UUID feeRevenue(UUID tenantId, String currency) {
        return ledger.ensureAccount(tenantId, "fee_revenue", AccountType.REVENUE, currency, false).id();
    }

    private static String entryKey(UUID operationId) {
        return "payment-operation:" + operationId;
    }
}
