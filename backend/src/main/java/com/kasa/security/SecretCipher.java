package com.kasa.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts secrets the application must read back, such as webhook signing keys, so a stolen database backup
 * does not hand them over. The context ties a value to its owner: copying one merchant's secret onto another
 * merchant's row makes it fail to decrypt rather than quietly work.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String PREFIX = "enc1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final List<SecretKey> keys = new ArrayList<>();

    public SecretCipher(@Value("${kasa.encryption.key:}") String configuredKey, JwtProperties jwtProperties) {
        // The first key encrypts and all of them decrypt, so setting a real key later does not strand old values.
        if (configuredKey != null && !configuredKey.isBlank()) {
            keys.add(derive(configuredKey));
        } else {
            log.warn("KASA_ENCRYPTION_KEY is not set; deriving the encryption key from the JWT secret");
        }
        keys.add(derive(jwtProperties.secret()));
    }

    public String encrypt(String context, String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.getFirst(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] stored = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            return PREFIX + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt a secret", e);
        }
    }

    /** Throws if the value was altered, was encrypted for another context, or matches no known key. */
    public String decrypt(String context, String stored) {
        if (!isEncrypted(stored)) {
            throw new IllegalArgumentException("value is not encrypted");
        }
        byte[] bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        for (SecretKey key : keys) {
            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
                return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
            } catch (AEADBadTagException wrongKey) {
                log.debug("A key did not open this value; trying the next one");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("could not decrypt a secret", e);
            }
        }
        throw new IllegalStateException("no known key opens this secret");
    }

    public static boolean isEncrypted(String stored) {
        return stored.startsWith(PREFIX);
    }

    // Any length of configured text becomes a proper 256-bit key, separate from the key the text signs JWTs with.
    private static SecretKey derive(String material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(material.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal("kasa-secret-encryption-v1".getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not derive the encryption key", e);
        }
    }
}
