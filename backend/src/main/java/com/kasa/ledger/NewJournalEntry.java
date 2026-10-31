package com.kasa.ledger;

import java.util.List;
import java.util.stream.Collectors;

import com.kasa.common.Hashes;

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
        return Hashes.sha256Hex(description + "|" + lines);
    }
}
