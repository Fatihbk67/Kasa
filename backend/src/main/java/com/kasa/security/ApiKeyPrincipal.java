package com.kasa.security;

import java.util.UUID;

/** The public prefix identifies a key in the audit log without exposing the key itself. */
public record ApiKeyPrincipal(UUID tenantId, String prefix) {
}
