package com.elitale.coldbirds.coldcalling.ui.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Honest 3-state internet-reachability health for the merged "can I call?" signal, fed by a periodic
 * {@code NetworkMonitor} probe (a real TCP connect to the SIP/Twilio host). Distinct from
 * {@link RegistrationHealth}, which tracks SIP registration, not the network.
 *
 * <p>States: {@code ONLINE} (green) · {@code UNSTABLE} (amber — Dial disabled, "reconnecting") ·
 * {@code OFFLINE} (red — loud). A <strong>single</strong> failed probe only drops to {@code UNSTABLE},
 * never straight to {@code OFFLINE} — so a slow-but-working café connection or a 2s roam isn't
 * murdered. Escalation to {@code OFFLINE} needs sustained loss past {@code offlineGrace}; recovery to
 * {@code ONLINE} needs sustained reachability past {@code stability} (anti-flap, so green/red never
 * strobe). Starts optimistic ({@code ONLINE}) so a normal launch doesn't flash "offline" before the
 * first probe lands.
 *
 * <p>Pure and time-injectable (no JavaFX) so every transition is unit-tested without sleeps.
 */
public final class ConnectivityHealth {

    /** The three reachability states the merged signal renders. */
    public enum State { ONLINE, UNSTABLE, OFFLINE }

    private static final Duration DEFAULT_OFFLINE_GRACE = Duration.ofSeconds(8);
    private static final Duration DEFAULT_STABILITY = Duration.ofSeconds(3);

    private final Duration offlineGrace;
    private final Duration stability;
    private final Supplier<Instant> clock;

    private State state = State.ONLINE;
    private Instant downSince;
    private Instant upSince;

    /** Production constructor: 8s offline grace, 3s recovery stability, wall-clock. */
    public ConnectivityHealth() {
        this(DEFAULT_OFFLINE_GRACE, DEFAULT_STABILITY, Instant::now);
    }

    public ConnectivityHealth(Duration offlineGrace, Duration stability, Supplier<Instant> clock) {
        this.offlineGrace = Objects.requireNonNull(offlineGrace, "offlineGrace must not be null");
        this.stability = Objects.requireNonNull(stability, "stability must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (offlineGrace.isNegative() || offlineGrace.isZero()) {
            throw new IllegalArgumentException("offlineGrace must be positive");
        }
        if (stability.isNegative() || stability.isZero()) {
            throw new IllegalArgumentException("stability must be positive");
        }
    }

    /** Feed one probe result (called from the monitor's scheduler). */
    public void onProbe(boolean reachable) {
        if (reachable) {
            if (state != State.ONLINE && upSince == null) {
                upSince = clock.get();   // start the recovery stability timer
            }
        } else {
            upSince = null;              // recovery interrupted
            if (state == State.ONLINE) {
                state = State.UNSTABLE;
                downSince = clock.get();
            }
        }
    }

    /** Recompute against the grace/stability windows (call on the periodic tick); return live state. */
    public State current() {
        final Instant now = clock.get();
        if ((state == State.UNSTABLE || state == State.OFFLINE) && upSince != null
                && Duration.between(upSince, now).compareTo(stability) >= 0) {
            state = State.ONLINE;
            downSince = null;
            upSince = null;
            return state;
        }
        if (state == State.UNSTABLE && downSince != null
                && Duration.between(downSince, now).compareTo(offlineGrace) >= 0) {
            state = State.OFFLINE;
        }
        return state;
    }
}
