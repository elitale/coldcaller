package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.ui.support.CallbackWhen.Preset;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

final class CallbackWhenTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    // A fixed afternoon "now".
    private static final Instant NOW = Instant.parse("2026-06-24T18:00:00Z");

    @Test
    void laterTodayIsThreeHoursOut() {
        assertThat(CallbackWhen.resolve(Preset.LATER_TODAY, NOW, UTC))
                .isEqualTo(Instant.parse("2026-06-24T21:00:00Z"));
    }

    @Test
    void tomorrowAmIsNineNextDay() {
        assertThat(CallbackWhen.resolve(Preset.TOMORROW_AM, NOW, UTC))
                .isEqualTo(Instant.parse("2026-06-25T09:00:00Z"));
    }

    @Test
    void inTwoDaysIsNineTwoDaysOut() {
        assertThat(CallbackWhen.resolve(Preset.IN_TWO_DAYS, NOW, UTC))
                .isEqualTo(Instant.parse("2026-06-26T09:00:00Z"));
    }

    @Test
    void nextWeekIsNineSevenDaysOut() {
        assertThat(CallbackWhen.resolve(Preset.NEXT_WEEK, NOW, UTC))
                .isEqualTo(Instant.parse("2026-07-01T09:00:00Z"));
    }

    @Test
    void defaultWhenIsTomorrowAm() {
        assertThat(CallbackWhen.defaultWhen(NOW, UTC))
                .isEqualTo(CallbackWhen.resolve(Preset.TOMORROW_AM, NOW, UTC));
    }
}
