package com.kasa.webhook;

import java.util.UUID;

final class WebhookSecrets {

    private WebhookSecrets() {
    }

    /** Ties a stored secret to its merchant, so a value copied onto another merchant's row cannot be decrypted. */
    static String context(UUID tenantId) {
        return "webhook-secret:" + tenantId;
    }
}
