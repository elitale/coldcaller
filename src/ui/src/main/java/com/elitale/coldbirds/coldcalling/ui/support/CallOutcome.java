package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;

import java.util.Locale;
import java.util.Optional;

/**
 * The at-a-glance outcome of a call — for the one-scan color/icon in Call History.
 *
 * <p>Derived (not stored): a call connected when it was answered or had any talk duration; the
 * disposition refines it. Pure and exhaustively unit-tested.
 */
public enum CallOutcome {

    CONNECTED("Connected"),
    NO_ANSWER("No answer"),
    VOICEMAIL("Voicemail"),
    FAILED("Failed"),
    DNC("DNC");

    private final String label;

    CallOutcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** AtlantaFX-style style class, e.g. {@code outcome-no-answer}. */
    public String styleClass() {
        return "outcome-" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Classify an attempt. {@code connected} is whether the call was answered / had talk time
     * (used only when no disposition was recorded).
     */
    public static CallOutcome classify(Optional<CallDisposition> disposition, boolean connected) {
        if (disposition.isPresent()) {
            return switch (disposition.get()) {
                case CallDisposition.DNC ignored           -> DNC;
                case CallDisposition.Failed ignored        -> FAILED;
                case CallDisposition.Voicemail ignored     -> VOICEMAIL;
                case CallDisposition.NoAnswer ignored      -> NO_ANSWER;
                case CallDisposition.Busy ignored          -> NO_ANSWER;
                case CallDisposition.Interested ignored    -> CONNECTED;
                case CallDisposition.NotInterested ignored -> CONNECTED;
                case CallDisposition.Callback ignored      -> CONNECTED;
            };
        }
        return connected ? CONNECTED : NO_ANSWER;
    }
}
