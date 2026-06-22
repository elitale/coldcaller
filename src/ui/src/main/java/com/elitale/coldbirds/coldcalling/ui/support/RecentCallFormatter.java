package com.elitale.coldbirds.coldcalling.ui.support;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure formatting helpers for the dialer's "Recent Calls" rows: a humanized
 * "time ago" label and a pluralized call-count label.
 */
public final class RecentCallFormatter {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private RecentCallFormatter() {}

    /**
     * Humanize how long ago {@code ref} was, relative to {@code now}.
     *
     * <p>Examples: {@code "Just now"}, {@code "5 min ago"}, {@code "2 hours ago"},
     * {@code "Yesterday"}, {@code "3 days ago"}, {@code "Jun 12"}.
     *
     * @param ref  the past instant (e.g. last call time); must not be null
     * @param now  the reference "now"; must not be null
     * @param zone zone used to render absolute dates for older entries; must not be null
     */
    public static String timeAgo(final Instant ref, final Instant now, final ZoneId zone) {
        Objects.requireNonNull(ref, "ref must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(zone, "zone must not be null");

        final long seconds = Math.max(0L, Duration.between(ref, now).getSeconds());
        if (seconds < 45) {
            return "Just now";
        }
        final long minutes = seconds / 60L;
        if (minutes < 60) {
            return minutes <= 1 ? "1 min ago" : minutes + " min ago";
        }
        final long hours = minutes / 60L;
        if (hours < 24) {
            return hours == 1 ? "1 hour ago" : hours + " hours ago";
        }
        final long days = hours / 24L;
        if (days == 1) {
            return "Yesterday";
        }
        if (days < 7) {
            return days + " days ago";
        }
        return DAY.format(ref.atZone(zone));
    }

    /** Pluralized total-calls label: {@code "1 call"} / {@code "N calls"}. */
    public static String callCountLabel(final int count) {
        return count == 1 ? "1 call" : count + " calls";
    }
}

