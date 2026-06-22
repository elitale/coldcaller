package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.Country;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DialNumberFormatterTest {

    private static final Country US = new Country("US", "United States", "+1", "-05:00");
    private static final Country MX = new Country("MX", "Mexico", "+52", "-06:00");

    // --- sanitize ---------------------------------------------------------

    @Test
    void sanitizeKeepsLeadingPlusAndDigitsOnly() {
        assertThat(DialNumberFormatter.sanitize("+1 (202) 555-0142")).isEqualTo("+12025550142");
    }

    @Test
    void sanitizeStripsLettersAndEmoji() {
        assertThat(DialNumberFormatter.sanitize("call 202\uD83D\uDCDE555a0142")).isEqualTo("2025550142");
    }

    @Test
    void sanitizeKeepsPlusOnlyWhenLeading() {
        assertThat(DialNumberFormatter.sanitize("1+2+3")).isEqualTo("123");
        assertThat(DialNumberFormatter.sanitize("+123")).isEqualTo("+123");
    }

    @Test
    void sanitizeKeepsStarAndHashForDialpadKeys() {
        assertThat(DialNumberFormatter.sanitize("*123#")).isEqualTo("*123#");
    }

    @Test
    void sanitizeTrimsSurroundingWhitespaceAndSeparators() {
        assertThat(DialNumberFormatter.sanitize("  +44 20 7946 0958 ")).isEqualTo("+442079460958");
    }

    @Test
    void sanitizeCapsDigitsAtFifteen() {
        assertThat(DialNumberFormatter.sanitize("1234567890123456789")).isEqualTo("123456789012345");
        assertThat(DialNumberFormatter.sanitize("+1234567890123456789")).isEqualTo("+123456789012345");
    }

    @Test
    void sanitizeReturnsEmptyForNoUsableChars() {
        assertThat(DialNumberFormatter.sanitize("abc")).isEmpty();
        assertThat(DialNumberFormatter.sanitize("")).isEmpty();
    }

    @Test
    void sanitizeRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> DialNumberFormatter.sanitize(null));
    }

    // --- toE164 -----------------------------------------------------------

    @Test
    void toE164KeepsExplicitPlusPrefixAndStripsSeparators() {
        assertThat(DialNumberFormatter.toE164("+1 (202) 555-0142", null)).isEqualTo("+12025550142");
        assertThat(DialNumberFormatter.toE164("+52 55 1234 5678", US)).isEqualTo("+525512345678");
    }

    @Test
    void toE164PrependsSelectedDialCodeForLocalNumber() {
        assertThat(DialNumberFormatter.toE164("2025550142", US)).isEqualTo("+12025550142");
        assertThat(DialNumberFormatter.toE164("5512345678", MX)).isEqualTo("+525512345678");
    }

    @Test
    void toE164StripsStarAndHashFromDialedNumber() {
        assertThat(DialNumberFormatter.toE164("*123#", US)).isEqualTo("+1123");
    }

    @Test
    void toE164WithoutCountryAndNoPlusReturnsDigitsOnly() {
        assertThat(DialNumberFormatter.toE164("2025550142", null)).isEqualTo("2025550142");
    }

    // --- isDialable -------------------------------------------------------

    @Test
    void isDialableRequiresAtLeastFiveDigits() {
        assertThat(DialNumberFormatter.isDialable("+5255123")).isTrue();
        assertThat(DialNumberFormatter.isDialable("12345")).isTrue();
        assertThat(DialNumberFormatter.isDialable("+12025550142")).isTrue();
    }

    @Test
    void isDialableRejectsBareDialCodeOrShortInput() {
        assertThat(DialNumberFormatter.isDialable("+52 ")).isFalse();
        assertThat(DialNumberFormatter.isDialable("1234")).isFalse();
        assertThat(DialNumberFormatter.isDialable("+")).isFalse();
        assertThat(DialNumberFormatter.isDialable("")).isFalse();
    }
}
