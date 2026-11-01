package com.kasa.payment;

public enum OperationType {
    AUTHORIZE,
    CAPTURE,
    VOID,
    REFUND,
    /** The three below are started by the bank's decisions on a dispute, so they never go through the bank-call steps. */
    DISPUTE_WITHDRAWAL,
    DISPUTE_REVERSAL,
    DISPUTE_FEE
}
