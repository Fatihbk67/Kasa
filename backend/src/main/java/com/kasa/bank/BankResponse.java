package com.kasa.bank;

public record BankResponse(boolean approved, String reference, String declineCode) {

    public static BankResponse approved(String reference) {
        return new BankResponse(true, reference, null);
    }

    public static BankResponse declined(String code) {
        return new BankResponse(false, null, code);
    }
}
