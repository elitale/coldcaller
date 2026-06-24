package com.elitale.coldbirds.coldcalling.ui.support;

/**
 * Merges internet reachability ({@link ConnectivityHealth}) and SIP registration
 * ({@link RegistrationHealth}) into one honest "can I call right now?" signal — so reps never see two
 * conflicting "offline" labels. Internet-down <strong>absorbs</strong> SIP state (no point saying
 * "SIP reconnecting" when there's no internet to reconnect over). Pure.
 */
public final class CallReadiness {

    /** What the single sidebar dot (and the Dial/Send disable gate) read from. */
    public enum Readiness {
        /** Internet up + SIP registered — green, calls allowed. */
        READY,
        /** Network wobbling or SIP re-registering — amber, calls held. */
        RECONNECTING,
        /** No internet (or up but not registered) — red, calls unavailable. */
        OFFLINE
    }

    private CallReadiness() {}

    public static Readiness resolve(ConnectivityHealth.State connectivity,
                                    RegistrationHealth.State registration) {
        return switch (connectivity) {
            case OFFLINE  -> Readiness.OFFLINE;        // internet down absorbs everything
            case UNSTABLE -> Readiness.RECONNECTING;   // network wobbling
            case ONLINE   -> switch (registration) {
                case REGISTERED   -> Readiness.READY;
                case RECONNECTING -> Readiness.RECONNECTING;
                case OFFLINE      -> Readiness.OFFLINE; // internet up but not registered — can't call
            };
        };
    }

    /** {@code true} only when a call/text can actually be placed right now. */
    public static boolean callable(ConnectivityHealth.State connectivity,
                                   RegistrationHealth.State registration) {
        return resolve(connectivity, registration) == Readiness.READY;
    }
}
