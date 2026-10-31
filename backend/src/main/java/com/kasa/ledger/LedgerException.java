package com.kasa.ledger;

import java.util.UUID;

public abstract class LedgerException extends RuntimeException {

    private LedgerException(String message) {
        super(message);
    }

    public static final class AccountNotFound extends LedgerException {
        AccountNotFound(UUID accountId) {
            super("account not found: " + accountId);
        }
    }

    public static final class UnbalancedEntry extends LedgerException {
        UnbalancedEntry(String currency) {
            super("debits and credits differ for currency " + currency);
        }
    }

    public static final class InsufficientFunds extends LedgerException {
        InsufficientFunds(UUID accountId) {
            super("insufficient funds on account " + accountId);
        }
    }

    public static final class IdempotencyConflict extends LedgerException {
        IdempotencyConflict(String key) {
            super("idempotency key was used for a different request: " + key);
        }
    }
}
