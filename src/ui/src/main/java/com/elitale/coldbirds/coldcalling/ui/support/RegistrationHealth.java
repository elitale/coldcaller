package com.elitale.coldbirds.coldcalling.ui.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Honest 3-state SIP registration health for the sidebar status dot, derived from the
 * boolean {@code CallService.setOnRegistrationChanged} seam plus a grace window.
 *
 * <p>States: {@code REGISTERED} (green) · {@code RECONNECTING} (amber) · {@code OFFLINE} (red).
 * Pessimistic bias: a never-registered drop is {@code OFFLINE}, not amber. A registered line
 * that drops shows {@code RECONNECTING} for {@code grace}; if it has not recovered by then it
 * escalates to {@code OFFLINE}. A healthy 60s re-REGISTER only ever emits {@code true} (the
 * registrar does not signal a drop on success), so amber never flickers on a normal refresh.
 *
 * <p>Pure and time-injectable (no JavaFX) so every transition is unit-tested without sleeps.
 */
public final class RegistrationHealth {

    /** The three connection states the status dot renders. */
    public enum State { REGISTERED, RECONNECTING, OFFLINE }

    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(90);

    private final Duration grace;
    private final Supplier<Instant> clock;

    private State state = State.OFFLINE;
    private Instant reconnectingSince;
    private boolean everRegistered;

    /** Production constructor: 90s grace, wall-clock. */
    public RegistrationHealth() {
        this(DEFAULT_GRACE, Instant::now);
    }

    public RegistrationHealth(Duration grace, Supplier<Instant> clock) {
        this.grace = Objects.requireNonNull(grace, "grace must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (grace.isNegative() || grace.isZero()) {
            throw new IllegalArgumentException("grace must be positive");
        }
    }

    /** Map the boolean registration seam onto the state machine. */
    public void onRegistrationChanged(boolean registered) {
        if (registered) {
            onRegistered();
        } else {
            onUnregistered();
        }
    }

    public void onRegistered() {
        state = State.REGISTERED;
        reconnectingSince = null;
        everRegistered = true;
    }

    public void onUnregistered() {
        if (!everRegistered) {
            state = State.OFFLINE;
            reconnectingSince = null;
            return;
        }
        if (state != State.RECONNECTING) {
            state = State.RECONNECTING;
            reconnectingSince = clock.get();
        }
    }

    /** No SIP credentials configured — force the honest "offline" state. */
    public void onCredentialsAbsent() {
        state = State.OFFLINE;
        reconnectingSince = null;
        everRegistered = false;
    }

    /** Recompute against the grace window (call on the periodic tick) and return the live state. */
    public State current() {
        if (state == State.RECONNECTING && reconnectingSince != null
                && Duration.between(reconnectingSince, clock.get()).compareTo(grace) >= 0) {
            state = State.OFFLINE;
            reconnectingSince = null;
        }
        return state;
    }
}
