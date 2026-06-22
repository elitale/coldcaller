package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.Country;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTimeFormatterTest {

    private static final Country US =
            new Country("US", "United States", "+1", "America/New_York");

    @Test
    void describe_includesCountryNameAndLocalTime() {
        // 2026-01-15T17:00:00Z → 12:00 PM in America/New_York (EST, UTC-5).
        Instant noonEst = Instant.parse("2026-01-15T17:00:00Z");

        String text = LocalTimeFormatter.describe(US, noonEst);

        assertThat(text).isEqualTo("United States · 12:00 PM local");
    }

    @Test
    void describe_formatsMorningTime() {
        // 2026-06-15T13:30:00Z → 9:30 AM in America/New_York (EDT, UTC-4).
        Instant morningEdt = Instant.parse("2026-06-15T13:30:00Z");

        String text = LocalTimeFormatter.describe(US, morningEdt);

        assertThat(text).isEqualTo("United States · 9:30 AM local");
    }
}
