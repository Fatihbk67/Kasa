package com.kasa.bank;

public class BankTimeoutException extends RuntimeException {

    public BankTimeoutException() {
        super("bank did not answer in time; the outcome is unknown");
    }
}
