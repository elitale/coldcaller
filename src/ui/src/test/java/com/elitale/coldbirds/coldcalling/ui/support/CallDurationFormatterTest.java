package com.elitale.coldbirds.coldcalling.ui.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class CallDurationFormatterTest {

    @Test
    void formatsSubMinute() {
        assertThat(CallDurationFormatter.format(Duration.ofSeconds(7))).isEqualTo("0:07");
    }

    @Test
    void formatsMinutesAndSeconds() {
        assertThat(CallDurationFormatter.format(Duration.ofSeconds(12 * 60 + 45))).isEqualTo("12:45");
    }

    @Test
    void formatsHoursWhenAtLeastOneHour() {
        assertThat(CallDurationFormatter.format(Duration.ofSeconds(3600 + 2 * 60 + 9)))
                .isEqualTo("1:02:09");
    }

    @Test
    void clampsNegativeToZero() {
        assertThat(CallDurationFormatter.format(Duration.ofSeconds(-5))).isEqualTo("0:00");
    }

    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> CallDurationFormatter.format(null));
    }
}
