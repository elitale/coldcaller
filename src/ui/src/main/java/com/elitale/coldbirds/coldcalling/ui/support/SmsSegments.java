package com.elitale.coldbirds.coldcalling.ui.support;

/**
 * A lightweight SMS length warning for the compose bar. Not an always-on counter — it stays silent
 * until the message will either split into multiple segments or fall back to Unicode (UCS-2), the
 * two cases a cold caller actually cares about (it costs more / fewer characters per message).
 *
 * <p>Approximate by design: uses the GSM&nbsp;03.38 default + extension alphabet to decide GSM-7 vs
 * Unicode, and a 160-character single-segment threshold. Pure and side-effect free.
 */
public final class SmsSegments {

    /** ASCII characters that live in the GSM-7 default or extension alphabet. */
    private static final String GSM7_ASCII =
            " \n\r\f@$_!\"#%&'()*+,-./:;<=>?^{}\\[~]|";

    /** Non-ASCII GSM-7 characters (Latin accents, Greek capitals, symbols, euro). */
    private static final String GSM7_NON_ASCII =
            "\u00A3\u00A5\u00E8\u00E9\u00F9\u00EC\u00F2\u00C7\u00D8\u00F8\u00C5\u00E5"
          + "\u0394\u03A6\u0393\u039B\u03A9\u03A0\u03A8\u03A3\u0398\u039E\u001B"
          + "\u00C6\u00E6\u00DF\u00C9\u00A4\u00A1\u00C4\u00D6\u00D1\u00DC\u00A7\u00BF"
          + "\u00E4\u00F6\u00F1\u00FC\u00E0\u20AC";

    /** Single-segment limit for a GSM-7 message. */
    private static final int GSM7_SINGLE = 160;

    private SmsSegments() {}

    /** A compose-bar warning level. */
    public enum Warning {
        NONE(""),
        WILL_SPLIT("Will split into multiple messages"),
        UNICODE("Unicode \u2014 fewer characters per message");

        private final String message;

        Warning(String message) {
            this.message = message;
        }

        /** Human-readable hint, empty for {@link #NONE}. */
        public String message() {
            return message;
        }
    }

    /** Classify {@code body}: NONE while a single GSM-7 segment, else WILL_SPLIT or UNICODE. */
    public static Warning warn(String body) {
        if (body == null || body.isEmpty()) {
            return Warning.NONE;
        }
        for (int i = 0; i < body.length(); i++) {
            if (!isGsm7(body.charAt(i))) {
                return Warning.UNICODE;
            }
        }
        return body.length() > GSM7_SINGLE ? Warning.WILL_SPLIT : Warning.NONE;
    }

    private static boolean isGsm7(char c) {
        if (c >= 'a' && c <= 'z') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (c >= '0' && c <= '9') return true;
        return GSM7_ASCII.indexOf(c) >= 0 || GSM7_NON_ASCII.indexOf(c) >= 0;
    }
}
