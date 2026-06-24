package com.elitale.coldbirds.coldcalling.services;

import java.util.Locale;
import java.util.Set;

/**
 * Detects carrier-standard SMS opt-out / opt-in keywords in an inbound message body.
 *
 * <p>Matching mirrors carrier behaviour: the keyword must be the <em>entire</em> message once
 * trimmed (case-insensitive). "STOP texting me" does not opt out — only a bare "STOP" does — so
 * a casual mention can never silently suppress a lead. Pure and side-effect free.
 */
public final class OptOutDetector {

    private static final Set<String> OPT_OUT =
            Set.of("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT");

    private static final Set<String> OPT_IN =
            Set.of("START", "UNSTOP", "YES");

    private OptOutDetector() {}

    /** {@code true} if the trimmed body is exactly an opt-out keyword (TCPA STOP handling). */
    public static boolean isOptOut(String body) {
        return OPT_OUT.contains(normalize(body));
    }

    /** {@code true} if the trimmed body is exactly an opt-in keyword (re-subscribe). */
    public static boolean isOptIn(String body) {
        return OPT_IN.contains(normalize(body));
    }

    private static String normalize(String body) {
        if (body == null) return "";
        return body.strip().toUpperCase(Locale.ROOT);
    }
}
