package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;

import java.util.Objects;

/**
 * Formats an owned caller-ID number for display: the friendly name and E.164
 * when named ("Main Line  ·  +12025550100"), or the bare E.164 otherwise.
 *
 * <p>Used wherever the user needs to see which of their numbers a call used —
 * the calling screen, the dialer's recent calls, the number-detail timeline,
 * and the Settings calling-number pool — so the presentation stays identical.
 */
public final class CallerIdLabel {

    private CallerIdLabel() {}

    /** Friendly-name + E.164 label, or just the number when unnamed. */
    public static String describe(final OwnedNumber number) {
        Objects.requireNonNull(number, "number must not be null");
        return number.friendlyName()
                .filter(name -> !name.isBlank())
                .map(name -> name + "  \u00b7  " + number.number().value())
                .orElse(number.number().value());
    }
}
