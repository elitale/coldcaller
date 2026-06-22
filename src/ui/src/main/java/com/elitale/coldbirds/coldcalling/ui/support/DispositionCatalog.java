package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The fixed set of call dispositions offered on the calling screen, with the
 * Bootstrap-icon glyph and one-key digit shortcut for each, plus the mapping
 * from a chip label to its domain {@link CallDisposition} value.
 * <p>
 * Pure data + mapping — no JavaFX toolkit required, so it is unit-testable
 * directly. The calling screen builds one chip per {@link Option} in order.
 */
public final class DispositionCatalog {

    /** A single selectable disposition chip. */
    public record Option(String label, String iconLiteral, String digit) {
        public Option {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(iconLiteral, "iconLiteral must not be null");
            Objects.requireNonNull(digit, "digit must not be null");
        }
    }

    /** Default look-ahead used when a {@code Callback} disposition is chosen via one tap. */
    public static final Duration DEFAULT_CALLBACK_DELAY = Duration.ofDays(1);

    /** Ordered chips shown left-to-right, top-to-bottom. */
    public static final List<Option> ALL = List.of(
            new Option("Interested",     "bi-hand-thumbs-up",      "1"),
            new Option("Not Interested", "bi-hand-thumbs-down",    "2"),
            new Option("Callback",       "bi-arrow-counterclockwise", "3"),
            new Option("Voicemail",      "bi-soundwave",           "4"),
            new Option("No Answer",      "bi-telephone-x",         "5"),
            new Option("Busy",           "bi-dash-circle",         "6"),
            new Option("DNC",            "bi-slash-circle",        "7"),
            new Option("Failed",         "bi-exclamation-triangle", "8")
    );

    private DispositionCatalog() {}

    /**
     * Map a chip label to its domain {@link CallDisposition}.
     * <p>
     * {@code Callback} is scheduled {@link #DEFAULT_CALLBACK_DELAY} ahead of
     * {@code now} (a one-tap default; a richer scheduler can override later) and
     * {@code Failed} carries a {@code "manual"} reason since the operator chose it.
     *
     * @param label chip label (case-insensitive, trimmed)
     * @param now   reference instant for time-relative dispositions
     * @return the disposition, or empty if the label is unknown
     */
    public static Optional<CallDisposition> toDisposition(String label, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (label == null) return Optional.empty();
        return switch (label.strip().toLowerCase()) {
            case "interested"     -> Optional.of(new CallDisposition.Interested());
            case "not interested" -> Optional.of(new CallDisposition.NotInterested());
            case "callback"       -> Optional.of(new CallDisposition.Callback(now.plus(DEFAULT_CALLBACK_DELAY)));
            case "voicemail"      -> Optional.of(new CallDisposition.Voicemail());
            case "no answer"      -> Optional.of(new CallDisposition.NoAnswer());
            case "busy"           -> Optional.of(new CallDisposition.Busy());
            case "dnc"            -> Optional.of(new CallDisposition.DNC());
            case "failed"         -> Optional.of(new CallDisposition.Failed("manual"));
            default               -> Optional.empty();
        };
    }
}
