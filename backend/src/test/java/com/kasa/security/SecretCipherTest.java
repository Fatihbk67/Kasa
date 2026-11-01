package com.kasa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final JwtProperties JWT = new JwtProperties("a-jwt-secret-long-enough-for-the-test", Duration.ofHours(1));

    private final SecretCipher cipher = new SecretCipher("the-real-key", JWT);

    @Test
    void aValueComesBackAsItWas() {
        String stored = cipher.encrypt("ctx", "whsec_abc");

        assertThat(stored).startsWith("enc1:").doesNotContain("whsec_abc");
        assertThat(cipher.decrypt("ctx", stored)).isEqualTo("whsec_abc");
    }

    @Test
    void theSameValueEncryptsDifferentlyEachTime() {
        assertThat(cipher.encrypt("ctx", "same")).isNotEqualTo(cipher.encrypt("ctx", "same"));
    }

    @Test
    void aValueCopiedToAnotherOwnerDoesNotOpen() {
        String stored = cipher.encrypt("merchant-a", "secret");

        assertThatThrownBy(() -> cipher.decrypt("merchant-b", stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAlteredValueDoesNotOpen() {
        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("ctx", "secret").substring(5));
        bytes[bytes.length - 1] ^= 1;
        String altered = "enc1:" + Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> cipher.decrypt("ctx", altered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKeyDoesNotOpen() {
        String stored = cipher.encrypt("ctx", "secret");
        SecretCipher other = new SecretCipher("a-different-key", new JwtProperties("another-jwt-secret-long-enough-here", Duration.ofHours(1)));

        assertThatThrownBy(() -> other.decrypt("ctx", stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void settingARealKeyLaterStillOpensWhatTheFallbackEncrypted() {
        String stored = new SecretCipher("", JWT).encrypt("ctx", "secret");

        assertThat(cipher.decrypt("ctx", stored)).isEqualTo("secret");
    }

    @Test
    void plainTextIsNotMistakenForEncrypted() {
        assertThat(SecretCipher.isEncrypted("whsec_abc")).isFalse();
        assertThatThrownBy(() -> cipher.decrypt("ctx", "whsec_abc")).isInstanceOf(IllegalArgumentException.class);
    }
}
