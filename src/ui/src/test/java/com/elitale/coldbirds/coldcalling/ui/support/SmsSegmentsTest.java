package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.ui.support.SmsSegments.Warning;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmsSegmentsTest {

    @Test
    void empty_orNull_isQuiet() {
        assertThat(SmsSegments.warn("")).isEqualTo(Warning.NONE);
        assertThat(SmsSegments.warn(null)).isEqualTo(Warning.NONE);
    }

    @Test
    void shortGsm7_isQuiet() {
        assertThat(SmsSegments.warn("Quick follow-up?")).isEqualTo(Warning.NONE);
    }

    @Test
    void gsm7_upTo160_isQuiet_butOver160Splits() {
        assertThat(SmsSegments.warn("a".repeat(160))).isEqualTo(Warning.NONE);
        assertThat(SmsSegments.warn("a".repeat(161))).isEqualTo(Warning.WILL_SPLIT);
    }

    @Test
    void euroAndAccents_stayGsm7() {
        assertThat(SmsSegments.warn("Price is \u20AC5")).isEqualTo(Warning.NONE);   // euro sign
        assertThat(SmsSegments.warn("Caf\u00E9")).isEqualTo(Warning.NONE);          // e-acute
    }

    @Test
    void nonGsm7_isUnicode() {
        assertThat(SmsSegments.warn("\u4F60\u597D")).isEqualTo(Warning.UNICODE);    // Chinese
        assertThat(SmsSegments.warn("Thanks \uD83D\uDE4F")).isEqualTo(Warning.UNICODE); // emoji
    }

    @Test
    void warningMessages_areEmptyOnlyForNone() {
        assertThat(Warning.NONE.message()).isEmpty();
        assertThat(Warning.WILL_SPLIT.message()).isNotEmpty();
        assertThat(Warning.UNICODE.message()).isNotEmpty();
    }
}
