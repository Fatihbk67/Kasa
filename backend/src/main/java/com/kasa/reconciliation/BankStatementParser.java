package com.kasa.reconciliation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads a bank statement strictly: a file with any bad line is rejected whole, never partly trusted. */
final class BankStatementParser {

    record Line(String reference, String type, long amount, String currency) {
    }

    static class MalformedStatementException extends RuntimeException {
        MalformedStatementException(String message) {
            super(message);
        }
    }

    private static final String HEADER = "reference,type,amount,currency,booked_at";
    private static final Set<String> TYPES = Set.of("CAPTURE", "REFUND", "PAYOUT");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private BankStatementParser() {
    }

    static List<Line> parse(String content) {
        String[] rows = content.split("\\R", -1);
        if (!rows[0].equals(HEADER)) {
            throw new MalformedStatementException("unexpected header");
        }
        List<Line> lines = new ArrayList<>();
        for (int i = 1; i < rows.length; i++) {
            if (!rows[i].isBlank()) {
                lines.add(parseRow(rows[i], i + 1));
            }
        }
        return lines;
    }

    private static Line parseRow(String row, int rowNumber) {
        String[] columns = row.split(",", -1);
        if (columns.length != 5 || columns[0].isBlank()) {
            throw new MalformedStatementException("row " + rowNumber + " is malformed");
        }
        if (!TYPES.contains(columns[1]) || !CURRENCY.matcher(columns[3]).matches()) {
            throw new MalformedStatementException("row " + rowNumber + " has an unknown type or currency");
        }
        long amount;
        try {
            amount = Long.parseLong(columns[2]);
        } catch (NumberFormatException e) {
            throw new MalformedStatementException("row " + rowNumber + " has an invalid amount");
        }
        if (amount <= 0) {
            throw new MalformedStatementException("row " + rowNumber + " has a non-positive amount");
        }
        return new Line(columns[0], columns[1], amount, columns[3]);
    }
}
