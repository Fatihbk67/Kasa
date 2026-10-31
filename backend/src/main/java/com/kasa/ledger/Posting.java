package com.kasa.ledger;

import java.util.Objects;
import java.util.UUID;

/** One line of a journal entry. Amounts are in minor units, e.g. kuruş. */
public record Posting(UUID accountId, Direction direction, long amount) {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    public static Posting debit(UUID accountId, long amount) {
        return new Posting(accountId, Direction.DEBIT, amount);
    }

    public static Posting credit(UUID accountId, long amount) {
        return new Posting(accountId, Direction.CREDIT, amount);
    }
}
