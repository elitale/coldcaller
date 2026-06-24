package com.elitale.coldbirds.coldcalling.telephony;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Periodically checks whether the app can actually reach its calling backend, and exposes the same
 * check synchronously for a click-time pre-flight (so a Dial fired the instant wifi drops fails fast
 * instead of ringing dead for ten seconds).
 *
 * <p>Reachability is a <strong>real TCP connect</strong> to the SIP/Twilio host — not an HTTP 200
 * from anywhere (captive portals answer those) and not a ping to {@code google.com} (Twilio could be
 * down while the internet is up). The probe is injectable, so scheduling is tested without sockets.
 * Debounce/grace lives in {@code ConnectivityHealth}; this class only emits raw {@code reachable}.
 */
public final class NetworkMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkMonitor.class);

    private final BooleanSupplier probe;
    private final Duration interval;
    private final Consumer<Boolean> onResult;

    private ScheduledExecutorService scheduler;

    /**
     * @param probe    reachability check (true = backend reachable); injectable for tests
     * @param interval how often to probe
     * @param onResult receives each raw probe result (on a monitor thread)
     */
    public NetworkMonitor(BooleanSupplier probe, Duration interval, Consumer<Boolean> onResult) {
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
        this.onResult = Objects.requireNonNull(onResult, "onResult must not be null");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }

    /** Begin probing. Idempotent. */
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("net-monitor").factory());
        scheduler.scheduleAtFixedRate(this::tick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("Network monitor started ({}s interval)", interval.toSeconds());
    }

    /** Stop probing. No-op if not running. */
    public synchronized void stop() {
        if (scheduler == null) return;
        scheduler.shutdownNow();
        scheduler = null;
        LOG.info("Network monitor stopped");
    }

    /**
     * Run the reachability check once, synchronously, for a Dial/Send click-time pre-flight.
     * Blocking I/O — never call on the FX thread.
     */
    public boolean probeNow() {
        return probe.getAsBoolean();
    }

    private void tick() {
        try {
            onResult.accept(probe.getAsBoolean());
        } catch (RuntimeException e) {
            LOG.warn("Network probe tick failed: {}", e.getMessage());
        }
    }

    // ── Reachability probe factory ──────────────────────────────────────────────

    /**
     * A reachability probe: a non-loopback interface must be up <em>and</em> a TCP connect to
     * {@code host:port} must succeed within {@code timeout}. The interface gate is a cheap
     * short-circuit; the TCP connect is what defeats captive portals.
     */
    public static BooleanSupplier reachabilityProbe(String host, int port, Duration timeout) {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        return () -> anyNetworkUp() && tcpReachable(host, port, timeout);
    }

    /** True if any non-loopback network interface is up (cheap, no I/O to the backend). */
    public static boolean anyNetworkUp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isUp() && !ni.isLoopback()) return true;
            }
        } catch (SocketException e) {
            return false;
        }
        return false;
    }

    private static boolean tcpReachable(String host, int port, Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
