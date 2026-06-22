package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.Country;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Formats the calling screen's location line — country name and the remote
 * party's current local time — from a {@link Country} and an instant. Pure and
 * side-effect free so it can be unit tested without the FX toolkit.
 */
public final class LocalTimeFormatter {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private LocalTimeFormatter() {}

    /** e.g. {@code "United States · 4:07 PM local"}. */
    public static String describe(Country country, Instant now) {
        Objects.requireNonNull(country, "country must not be null");
        Objects.requireNonNull(now, "now must not be null");
        final String time = CLOCK.format(now.atZone(country.zone()));
        return country.displayName() + " · " + time + " local";
    }
}
