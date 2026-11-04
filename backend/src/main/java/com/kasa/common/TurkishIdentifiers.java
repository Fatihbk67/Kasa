package com.kasa.common;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Check digit rules for Turkish identifiers. They catch typing mistakes before an application reaches review;
 * they cannot tell whether a number really belongs to the applicant, which is what the reviewer is for.
 */
public final class TurkishIdentifiers {

    private static final int TR_IBAN_LENGTH = 26;

    private TurkishIdentifiers() {
    }

    /** T.C. kimlik numarası: 11 digits, not starting with 0, with two check digits. */
    public static boolean isValidTckn(String value) {
        if (value == null || !value.matches("[1-9]\\d{10}")) {
            return false;
        }
        int[] d = digits(value);
        int odd = d[0] + d[2] + d[4] + d[6] + d[8];
        int even = d[1] + d[3] + d[5] + d[7];
        int tenth = Math.floorMod(odd * 7 - even, 10);
        int eleventh = (odd + even + d[9]) % 10;
        return d[9] == tenth && d[10] == eleventh;
    }

    /** Vergi kimlik numarası, as issued to companies: 10 digits, the last one a check digit. */
    public static boolean isValidVkn(String value) {
        if (value == null || !value.matches("\\d{10}")) {
            return false;
        }
        int[] d = digits(value);
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int shifted = (d[i] + 9 - i) % 10;
            int part = (shifted * (1 << (9 - i))) % 9;
            if (shifted != 0 && part == 0) {
                part = 9;
            }
            sum += part;
        }
        return d[9] == (10 - sum % 10) % 10;
    }

    /** Strips spaces and capitalises, so "tr33 0006 ..." as people type it is accepted. */
    public static String normalizeIban(String value) {
        return value == null ? null : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** A Turkish IBAN: 26 characters, the reserved digit 0, and the international mod-97 check (ISO 13616). */
    public static boolean isValidTrIban(String value) {
        String iban = normalizeIban(value);
        if (iban == null || iban.length() != TR_IBAN_LENGTH || !iban.matches("TR\\d{24}") || iban.charAt(9) != '0') {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            numeric.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c));
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /** The five-digit bank code inside a Turkish IBAN, which says which bank the account is at. */
    public static String bankCode(String iban) {
        return normalizeIban(iban).substring(4, 9);
    }

    private static int[] digits(String value) {
        return value.chars().map(c -> c - '0').toArray();
    }
}
