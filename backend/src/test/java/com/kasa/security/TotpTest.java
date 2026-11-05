package com.kasa.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TotpTest {

    // The published test secret from RFC 6238, appendix B.
    private static final String RFC_SECRET = Totp.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void theCodesMatchThePublishedTestVectors() {
        assertThat(Totp.code(RFC_SECRET, 59L / 30)).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void base32MatchesItsPublishedVectorsAndRoundTrips() {
        assertThat(Totp.base32Encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(new String(Totp.base32Decode("MZXW6YTBOI"), StandardCharsets.US_ASCII)).startsWith("foobar");
    }

    @Test
    void aCodeWorksOneStepEitherWayForClockDifferences() {
        String secret = Totp.newSecret();
        long now = 1_000_000;

        assertThat(Totp.matchingStep(secret, Totp.code(secret, now - 1), 0, now)).isEqualTo(now - 1);
        assertThat(Totp.matchingStep(secret, Totp.code(secret, now), 0, now)).isEqualTo(now);
        assertThat(Totp.matchingStep(secret, Totp.code(secret, now + 1), 0, now)).isEqualTo(now + 1);
    }

    @Test
    void aCodeFurtherAwayDoesNotWork() {
        String secret = "JBSWY3DPEHPK3PXP";
        long now = 1_000_000;

        assertThat(Totp.matchingStep(secret, Totp.code(secret, now - 5), 0, now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(secret, Totp.code(secret, now + 5), 0, now)).isEqualTo(-1);
    }

    @Test
    void aCodeAlreadyUsedDoesNotWorkAgain() {
        String secret = Totp.newSecret();
        long now = 1_000_000;

        assertThat(Totp.matchingStep(secret, Totp.code(secret, now), now, now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(secret, Totp.code(secret, now - 1), now, now)).isEqualTo(-1);
    }

    @Test
    void aCodeTypedWithTheSpaceTheAppShowsStillWorks() {
        String secret = Totp.newSecret();
        long now = 1_000_000;
        String code = Totp.code(secret, now);

        assertThat(Totp.matchingStep(secret, code.substring(0, 3) + " " + code.substring(3), 0, now)).isEqualTo(now);
    }

    @Test
    void aMalformedCodeIsSimplyWrong() {
        assertThat(Totp.matchingStep(Totp.newSecret(), "12345", 0, 1_000_000)).isEqualTo(-1);
        assertThat(Totp.matchingStep(Totp.newSecret(), "abcdef", 0, 1_000_000)).isEqualTo(-1);
    }

    @Test
    void theEnrollmentUriIsWhatAuthenticatorAppsExpect() {
        String uri = Totp.enrollmentUri("ABC", "a b@example.com", "Kasa");

        assertThat(uri).startsWith("otpauth://totp/Kasa%3Aa%20b%40example.com?secret=ABC&issuer=Kasa");
    }
}
