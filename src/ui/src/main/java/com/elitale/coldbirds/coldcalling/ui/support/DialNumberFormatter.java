package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.Country;

import java.util.Objects;

/**
 * Pure formatting/normalisation for the dialer's number field.
 * <p>
 * The field shows the full visible string the user dials (including a leading
 * {@code +} or a country dial-code prefix). This helper keeps the field's
 * character set tight, derives the E.164 destination, and decides when the
 * number is long enough to dial.
 * <p>
 * {@code *} and {@code #} are accepted into the field so the dial-pad keys stay
 * functional, but they are <em>stripped</em> from the dialed E.164 number
 * ({@link Country} dial codes plus a national number are all that
 * {@code PhoneNumber} accepts).
 */
public final class DialNumberFormatter {

    /** E.164 allows at most 15 digits. */
    private static final int MAX_DIGITS = 15;

    /**
     * Minimum digit count before {@link #isDialable} returns {@code true}.
     * A bare dial code is at most four digits ({@code \\+\\d{1,4}}), so a
     * five-digit floor guarantees there is a national number beyond the prefix.
     */
    private static final int MIN_DIALABLE_DIGITS = 5;

    private DialNumberFormatter() {}

    /**
     * Keep a single leading {@code +}, the digits {@code 0-9} (capped at
     * {@value #MAX_DIGITS}), and the dial-pad keys {@code *}/{@code #}. Every
     * other character (separators, letters, emoji, stray {@code +}) is dropped.
     */
    public static String sanitize(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");
        StringBuilder out = new StringBuilder(raw.length());
        int digits = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '+') {
                if (out.length() == 0) {
                    out.append('+');
                }
            } else if (c >= '0' && c <= '9') {
                if (digits < MAX_DIGITS) {
                    out.append(c);
                    digits++;
                }
            } else if (c == '*' || c == '#') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Resolve the visible string to an E.164 destination. A leading {@code +}
     * is honoured as written; otherwise the {@code selected} country's dial
     * code is prepended (or nothing when no country is selected). {@code *} and
     * {@code #} are removed.
     */
    public static String toE164(String visible, Country selected) {
        String clean = sanitize(visible);
        String digits = clean.replaceAll("\\D", "");
        if (clean.startsWith("+")) {
            return "+" + digits;
        }
        String prefix = selected == null ? "" : selected.dialCode();
        return prefix + digits;
    }

    /**
     * {@code true} once the visible string carries at least
     * {@value #MIN_DIALABLE_DIGITS} digits — enough to be more than a bare dial
     * code. Used to gate the Call button.
     */
    public static boolean isDialable(String visible) {
        return digitCount(visible) >= MIN_DIALABLE_DIGITS;
    }

    private static int digitCount(String visible) {
        return sanitize(visible).replaceAll("\\D", "").length();
    }
}
