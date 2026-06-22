package com.elitale.coldbirds.coldcalling.ui.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class RecentCallFormatterTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant NOW = Instant.parse("2026-06-22T14:00:00Z");

    private static String ago(final Duration before) {
        return RecentCallFormatter.timeAgo(NOW.minus(before), NOW, UTC);
    }

    @Test
    void secondsAgoReadsJustNow() {
        assertThat(ago(Duration.ofSeconds(10))).isEqualTo("Just now");
    }

    @Test
    void singleMinuteReadsOneMinAgo() {
        assertThat(ago(Duration.ofSeconds(70))).isEqualTo("1 min ago");
    }

    @Test
    void minutesAgoReadsPlural() {
        assertThat(ago(Duration.ofMinutes(5))).isEqualTo("5 min ago");
    }

    @Test
    void singleHourReadsOneHourAgo() {
        assertThat(ago(Duration.ofMinutes(60))).isEqualTo("1 hour ago");
    }

    @Test
    void hoursAgoReadsPlural() {
        assertThat(ago(Duration.ofHours(3))).isEqualTo("3 hours ago");
    }

    @Test
    void oneDayReadsYesterday() {
        assertThat(ago(Duration.ofHours(25))).isEqualTo("Yesterday");
    }

    @Test
    void daysAgoReadsPlural() {
        assertThat(ago(Duration.ofDays(3))).isEqualTo("3 days ago");
    }

    @Test
    void olderThanAWeekReadsAbsoluteDate() {
        assertThat(ago(Duration.ofDays(10))).isEqualTo("Jun 12");
    }

    @Test
    void callCountLabelIsSingularForOne() {
        assertThat(RecentCallFormatter.callCountLabel(1)).isEqualTo("1 call");
    }

    @Test
    void callCountLabelIsPluralForMany() {
        assertThat(RecentCallFormatter.callCountLabel(12)).isEqualTo("12 calls");
    }
}
