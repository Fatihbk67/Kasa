package com.kasa.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

public record NewJournalEntry(String idempotencyKey, String description, List<Posting> postings) {

    public NewJournalEntry {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException("an entry needs at least two postings");
        }
        postings = List.copyOf(postings);
    }

    /** Identifies the request's content, so reusing a key for a different request can be detected. */
    String fingerprint() {
        String lines = postings.stream()
                .map(p -> p.accountId() + ":" + p.direction() + ":" + p.amount())
                .sorted()
                .collect(Collectors.joining("|"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((description + "|" + lines).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
