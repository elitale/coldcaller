package com.elitale.coldbirds.coldcalling.ui.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregates the sidebar's ambient signals — SIP registration state, an inbound ring, an
 * ongoing live call, a running power-dialer session, and unread-Messages presence — into the
 * tiny view-model the rail binds to.
 *
 * <p>The single non-trivial rule lives here and is unit-tested: the **return row** precedence
 * is {@code inbound ring → live call → power dialer} (most safety-critical first), so a call
 * ringing in while power-dialing always wins. Messages activity is presence-only (a dot, never
 * a count) — the truthful count is a later, seen-model-backed concern.
 *
 * <p>Pure (no JavaFX). Setters fire listeners only on a real change.
 */
public final class SidebarStatusModel {

    /** Which return affordance the rail shows below the nav list. */
    public enum ReturnKind { NONE, INBOUND_RING, LIVE_CALL, POWER_DIALER }

    /** The resolved return row: its kind plus a context label (caller / "Live" / progress). */
    public record ReturnRow(ReturnKind kind, String label) {
        public ReturnRow {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(label, "label must not be null");
        }

        public static final ReturnRow NONE = new ReturnRow(ReturnKind.NONE, "");
    }

    private final List<Runnable> listeners = new ArrayList<>();

    private RegistrationHealth.State registration = RegistrationHealth.State.OFFLINE;
    private ConnectivityHealth.State connectivity = ConnectivityHealth.State.ONLINE;
    private Optional<String> inboundRing = Optional.empty();
    private boolean liveCall;
    private Optional<String> powerDialer = Optional.empty();
    private boolean messagesActivity;

    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    private void fire() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    public void setRegistration(RegistrationHealth.State state) {
        Objects.requireNonNull(state, "state must not be null");
        if (state != registration) {
            registration = state;
            fire();
        }
    }

    public void setConnectivity(ConnectivityHealth.State state) {
        Objects.requireNonNull(state, "state must not be null");
        if (state != connectivity) {
            connectivity = state;
            fire();
        }
    }

    /** Set/clear an inbound call ringing; {@code caller} is the number/client label. */
    public void setInboundRing(Optional<String> caller) {
        Objects.requireNonNull(caller, "caller must not be null");
        if (!caller.equals(inboundRing)) {
            inboundRing = caller;
            fire();
        }
    }

    public void setLiveCall(boolean live) {
        if (live != liveCall) {
            liveCall = live;
            fire();
        }
    }

    /** Set/clear a running power-dialer session; {@code label} is its progress text. */
    public void setPowerDialer(Optional<String> label) {
        Objects.requireNonNull(label, "label must not be null");
        if (!label.equals(powerDialer)) {
            powerDialer = label;
            fire();
        }
    }

    public void setMessagesActivity(boolean activity) {
        if (activity != messagesActivity) {
            messagesActivity = activity;
            fire();
        }
    }

    public RegistrationHealth.State registration() {
        return registration;
    }

    public ConnectivityHealth.State connectivity() {
        return connectivity;
    }

    /** The merged "can I call right now?" signal (internet reachability + SIP registration). */
    public CallReadiness.Readiness readiness() {
        return CallReadiness.resolve(connectivity, registration);
    }

    public boolean messagesActivity() {
        return messagesActivity;
    }

    /** Resolve the highest-priority return affordance to show, or {@link ReturnRow#NONE}. */
    public ReturnRow returnRow() {
        if (inboundRing.isPresent()) {
            return new ReturnRow(ReturnKind.INBOUND_RING, inboundRing.get());
        }
        if (liveCall) {
            return new ReturnRow(ReturnKind.LIVE_CALL, "Live");
        }
        if (powerDialer.isPresent()) {
            return new ReturnRow(ReturnKind.POWER_DIALER, powerDialer.get());
        }
        return ReturnRow.NONE;
    }
}
