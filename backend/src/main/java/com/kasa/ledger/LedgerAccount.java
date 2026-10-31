package com.kasa.ledger;

import java.util.UUID;

public record LedgerAccount(
        UUID id,
        UUID tenantId,
        String code,
        AccountType type,
        String currency,
        boolean allowNegative,
        long balance) {
}
