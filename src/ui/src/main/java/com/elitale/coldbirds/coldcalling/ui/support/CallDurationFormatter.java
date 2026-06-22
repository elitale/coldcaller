package com.elitale.coldbirds.coldcalling.ui.support;

import java.time.Duration;
import java.util.Objects;

/**
 * Formats a live call duration as a compact clock string.
 * <p>
 * {@code m:ss} under one hour, {@code h:mm:ss} at or beyond one hour. Negative
 * durations clamp to zero. Pure logic — no JavaFX toolkit required.
 */
public final class CallDurationFormatter {

    private CallDurationFormatter() {}

    /**
     * @param elapsed time since the call connected
     * @return e.g. {@code "0:07"}, {@code "12:45"}, {@code "1:02:09"}
     */
    public static String format(Duration elapsed) {
        Objects.requireNonNull(elapsed, "elapsed must not be null");
        long total = Math.max(0, elapsed.getSeconds());
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%d:%02d", minutes, seconds);
    }
}
