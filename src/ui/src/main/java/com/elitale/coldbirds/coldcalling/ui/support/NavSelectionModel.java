package com.elitale.coldbirds.coldcalling.ui.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Which sidebar destination is currently active. Exactly one is active at a time; the active
 * item is driven by the visible centre view (not click-only), so navigating by keyboard or by
 * a deep link keeps the rail in sync.
 *
 * <p>Pure (no JavaFX) and unit-tested. The view subscribes via {@link #addListener} to re-style
 * on change.
 */
public final class NavSelectionModel {

    /** The sidebar's navigation destinations, top to bottom. */
    public enum Destination { DIALER, LEADS, CALL_HISTORY, MESSAGES, POWER_DIALER, SETTINGS }

    private final List<Consumer<Destination>> listeners = new ArrayList<>();
    private Destination active;

    public NavSelectionModel() {
        this(Destination.DIALER);
    }

    public NavSelectionModel(Destination initial) {
        this.active = Objects.requireNonNull(initial, "initial must not be null");
    }

    public Destination active() {
        return active;
    }

    public boolean isActive(Destination destination) {
        return active == destination;
    }

    /** Make {@code destination} active; notifies listeners only on an actual change. */
    public void select(Destination destination) {
        Objects.requireNonNull(destination, "destination must not be null");
        if (destination == active) {
            return;
        }
        active = destination;
        for (Consumer<Destination> listener : List.copyOf(listeners)) {
            listener.accept(destination);
        }
    }

    public void addListener(Consumer<Destination> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }
}
