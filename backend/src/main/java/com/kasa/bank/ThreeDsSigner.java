package com.kasa.bank;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.kasa.security.JwtProperties;

/**
 * The key the card bank and we share. The bank's answer travels through the customer's browser, where anyone
 * could rewrite "failed" into "passed"; the signature is what makes that pointless.
 */
@Component
public class ThreeDsSigner {

    /** The bank's verdict as it reaches us: Y authenticated, N not. */
    public record SignedResult(UUID authenticationId, String status, String eci, long issuedAt, String signature) {
    }

    private final byte[] key;

    public ThreeDsSigner(@Value("${kasa.three-ds.secret:}") String configured, JwtProperties jwt) {
        // Never a fixed default: a key that is in the public repository would let anyone forge a passed check.
        String source = configured == null || configured.isBlank() ? "three-ds:" + jwt.secret() : configured;
        try {
            this.key = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public SignedResult sign(UUID authenticationId, String status, String eci, long issuedAt) {
        return new SignedResult(authenticationId, status, eci, issuedAt, mac(authenticationId, status, eci, issuedAt));
    }

    /** Compared in constant time, so the answer's timing does not reveal how much of a forged signature was right. */
    public boolean verify(SignedResult result) {
        if (result.signature() == null) {
            return false;
        }
        byte[] expected = mac(result.authenticationId(), result.status(), result.eci(), result.issuedAt()).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, result.signature().getBytes(StandardCharsets.US_ASCII));
    }

    private String mac(UUID authenticationId, String status, String eci, long issuedAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String payload = authenticationId + "|" + status + "|" + (eci == null ? "" : eci) + "|" + issuedAt;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
