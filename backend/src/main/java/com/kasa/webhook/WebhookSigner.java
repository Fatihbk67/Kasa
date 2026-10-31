package com.kasa.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class WebhookSigner {

    private WebhookSigner() {
    }

    /** Signs the timestamp with the body, so a captured request cannot be replayed later with a new timestamp. */
    static String signatureHeader(String secret, long epochSeconds, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal((epochSeconds + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + epochSeconds + ",v1=" + HexFormat.of().formatHex(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
