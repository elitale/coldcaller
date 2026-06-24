package com.elitale.coldbirds.coldcalling.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OptOutDetectorTest {

    @Test
    void isOptOut_exactKeyword_anyCase() {
        assertThat(OptOutDetector.isOptOut("STOP")).isTrue();
        assertThat(OptOutDetector.isOptOut("stop")).isTrue();
        assertThat(OptOutDetector.isOptOut("Stop")).isTrue();
        assertThat(OptOutDetector.isOptOut("UNSUBSCRIBE")).isTrue();
        assertThat(OptOutDetector.isOptOut("Cancel")).isTrue();
        assertThat(OptOutDetector.isOptOut("END")).isTrue();
        assertThat(OptOutDetector.isOptOut("quit")).isTrue();
    }

    @Test
    void isOptOut_trimsSurroundingWhitespace() {
        assertThat(OptOutDetector.isOptOut("  stop  ")).isTrue();
        assertThat(OptOutDetector.isOptOut("\nSTOP\n")).isTrue();
    }

    @Test
    void isOptOut_keywordInsideSentence_doesNotMatch() {
        assertThat(OptOutDetector.isOptOut("STOP please")).isFalse();
        assertThat(OptOutDetector.isOptOut("please stop calling")).isFalse();
        assertThat(OptOutDetector.isOptOut("don't stop")).isFalse();
    }

    @Test
    void isOptOut_nonKeyword_isFalse() {
        assertThat(OptOutDetector.isOptOut("hello")).isFalse();
        assertThat(OptOutDetector.isOptOut("")).isFalse();
        assertThat(OptOutDetector.isOptOut("   ")).isFalse();
        assertThat(OptOutDetector.isOptOut(null)).isFalse();
    }

    @Test
    void isOptIn_startKeywords() {
        assertThat(OptOutDetector.isOptIn("START")).isTrue();
        assertThat(OptOutDetector.isOptIn(" start ")).isTrue();
        assertThat(OptOutDetector.isOptIn("UNSTOP")).isTrue();
        assertThat(OptOutDetector.isOptIn("STOP")).isFalse();
        assertThat(OptOutDetector.isOptIn(null)).isFalse();
    }
}
