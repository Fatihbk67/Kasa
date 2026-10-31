package com.kasa.ledger;

import java.util.UUID;

/** {@code replayed} is true when the idempotency key had already been processed. */
public record PostedEntry(UUID entryId, boolean replayed) {
}
