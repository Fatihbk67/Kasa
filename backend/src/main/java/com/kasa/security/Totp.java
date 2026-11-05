package com.kasa.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Time-based one-time passwords as in RFC 6238, which every authenticator app understands. */
public final class Totp {

    public static final int STEP_SECONDS = 30;

    private static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static String newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return base32Encode(secret);
    }

    public static long currentStep() {
        return Instant.now().getEpochSecond() / STEP_SECONDS;
    }

    public static String code(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(base32Secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int value = ByteBuffer.wrap(hash, offset, Integer.BYTES).getInt() & 0x7FFFFFFF;
            return String.format("%06d", value % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not compute a one-time password", e);
        }
    }

    /**
     * Returns the step the code belongs to, or -1. Allows one step either way for clock differences, and refuses
     * anything at or before {@code lastUsedStep}, because a code seen once must never work a second time.
     */
    public static long matchingStep(String base32Secret, String typed, long lastUsedStep, long now) {
        // Authenticator apps show "123 456", and people type it that way.
        String code = typed.replaceAll("\\s", "");
        if (code.length() != DIGITS) {
            return -1;
        }
        for (long step = now - 1; step <= now + 1; step++) {
            boolean same = MessageDigest.isEqual(code(base32Secret, step).getBytes(StandardCharsets.UTF_8),
                    code.getBytes(StandardCharsets.UTF_8));
            if (same && step > lastUsedStep) {
                return step;
            }
        }
        return -1;
    }

    public static String enrollmentUri(String base32Secret, String accountEmail, String issuer) {
        String label = URLEncoder.encode(issuer + ":" + accountEmail, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer="
                + URLEncoder.encode(issuer, StandardCharsets.UTF_8) + "&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    static String base32Encode(byte[] bytes) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xFF);
            bits += 8;
            while (bits >= 5) {
                encoded.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            encoded.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return encoded.toString();
    }

    static byte[] base32Decode(String encoded) {
        ByteBuffer decoded = ByteBuffer.allocate(encoded.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char letter : encoded.toCharArray()) {
            int value = BASE32.indexOf(letter);
            if (value < 0) {
                throw new IllegalArgumentException("not base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                decoded.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return decoded.array();
    }
}
