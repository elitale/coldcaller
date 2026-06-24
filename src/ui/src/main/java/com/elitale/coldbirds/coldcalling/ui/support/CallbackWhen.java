package com.elitale.coldbirds.coldcalling.ui.support;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Resolves a callback quick-preset ("Tomorrow AM", "Next week", …) to a concrete {@link Instant}
 * against a given "now" + timezone, so the default-and-go capture is deterministic and testable.
 */
public final class CallbackWhen {

    /** The quick presets offered when scheduling a callback. */
    public enum Preset { LATER_TODAY, TOMORROW_AM, IN_TWO_DAYS, NEXT_WEEK }

    private static final int MORNING_HOUR = 9;
    private static final int LATER_TODAY_HOURS = 3;

    private CallbackWhen() {
    }

    public static Instant resolve(Preset preset, Instant now, ZoneId zone) {
        Objects.requireNonNull(preset, "preset must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(zone, "zone must not be null");
        final ZonedDateTime n = now.atZone(zone);
        return switch (preset) {
            case LATER_TODAY -> n.plusHours(LATER_TODAY_HOURS).toInstant();
            case TOMORROW_AM -> morning(n.plusDays(1), zone);
            case IN_TWO_DAYS -> morning(n.plusDays(2), zone);
            case NEXT_WEEK   -> morning(n.plusWeeks(1), zone);
        };
    }

    /** The default committed by a one-tap "Callback" (Tomorrow AM). */
    public static Instant defaultWhen(Instant now, ZoneId zone) {
        return resolve(Preset.TOMORROW_AM, now, zone);
    }

    private static Instant morning(ZonedDateTime day, ZoneId zone) {
        return day.toLocalDate().atTime(MORNING_HOUR, 0).atZone(zone).toInstant();
    }
}
