package com.kasa.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TurkishIdentifiersTest {

    // The example IBAN that banks and iban.com publish for Turkey.
    private static final String PUBLISHED_IBAN = "TR330006100519786457841326";
    // A number widely used for testing, valid under the official rule.
    private static final String PUBLISHED_TCKN = "10000000146";

    @Test
    void thePublishedIbanIsAcceptedAsPeopleTypeIt() {
        assertThat(TurkishIdentifiers.isValidTrIban(PUBLISHED_IBAN)).isTrue();
        assertThat(TurkishIdentifiers.isValidTrIban("tr33 0006 1005 1978 6457 8413 26")).isTrue();
        assertThat(TurkishIdentifiers.bankCode(PUBLISHED_IBAN)).isEqualTo("00061");
    }

    @Test
    void anyMistypedIbanDigitIsCaught() {
        for (int position = 2; position < PUBLISHED_IBAN.length(); position++) {
            char original = PUBLISHED_IBAN.charAt(position);
            char changed = original == '9' ? '0' : (char) (original + 1);
            String typo = PUBLISHED_IBAN.substring(0, position) + changed + PUBLISHED_IBAN.substring(position + 1);
            assertThat(TurkishIdentifiers.isValidTrIban(typo)).as("typo at %d", position).isFalse();
        }
    }

    @Test
    void anIbanOfTheWrongShapeIsRefused() {
        assertThat(TurkishIdentifiers.isValidTrIban("DE89370400440532013000")).isFalse();
        assertThat(TurkishIdentifiers.isValidTrIban(PUBLISHED_IBAN.substring(0, 25))).isFalse();
        assertThat(TurkishIdentifiers.isValidTrIban(null)).isFalse();
    }

    @Test
    void thePublishedTcknIsAcceptedAndMistypesAreNot() {
        assertThat(TurkishIdentifiers.isValidTckn(PUBLISHED_TCKN)).isTrue();
        assertThat(TurkishIdentifiers.isValidTckn("10000000147")).isFalse();
        assertThat(TurkishIdentifiers.isValidTckn("10000000156")).isFalse();
        assertThat(TurkishIdentifiers.isValidTckn("00000000146")).isFalse();
        assertThat(TurkishIdentifiers.isValidTckn("1000000014")).isFalse();
    }

    @Test
    void aVknWithTheRightCheckDigitIsAcceptedAndEveryOtherLastDigitRefused() {
        String prefix = "123456789";
        int accepted = 0;
        for (int last = 0; last <= 9; last++) {
            if (TurkishIdentifiers.isValidVkn(prefix + last)) {
                accepted++;
            }
        }
        assertThat(accepted).isEqualTo(1);
        assertThat(TurkishIdentifiers.isValidVkn("12345678")).isFalse();
        assertThat(TurkishIdentifiers.isValidVkn("abcdefghij")).isFalse();
    }
}
