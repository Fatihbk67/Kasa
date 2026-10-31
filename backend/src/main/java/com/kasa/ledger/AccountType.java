package com.kasa.ledger;

public enum AccountType {
    ASSET(Direction.DEBIT),
    EXPENSE(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    EQUITY(Direction.CREDIT),
    REVENUE(Direction.CREDIT);

    private final Direction normalSide;

    AccountType(Direction normalSide) {
        this.normalSide = normalSide;
    }

    /** A posting on the account's normal side increases its balance; the other side decreases it. */
    long balanceDelta(Direction direction, long amount) {
        return direction == normalSide ? amount : -amount;
    }
}
