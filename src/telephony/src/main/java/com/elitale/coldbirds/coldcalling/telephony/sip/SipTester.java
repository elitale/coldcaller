package com.elitale.coldbirds.coldcalling.telephony.sip;

import com.elitale.coldbirds.coldcalling.domain.value.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot SIP credential validator used during onboarding.
 *
 * <p>Performs a single REGISTER against the configured SIP proxy and reports
 * whether the credentials were accepted, without leaving any long-lived
 * registration running. The probe is closed as soon as a terminal result
 * (success, failure, or timeout) is reached.
 *
 * <p>The actual network probe is abstracted behind {@link RegistrationProbe}
 * so tests can inject a synchronous fake.
 */
public final class SipTester {

    private static final Logger LOG = LoggerFactory.getLogger(SipTester.class);

    private static final String FALLBACK_IP = "127.0.0.1";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Seam for the underlying registration attempt. Implementations start a
     * REGISTER, drive the supplied {@code listener}, and return a handle that
     * tears everything down when closed.
     */
    @FunctionalInterface
    public interface RegistrationProbe {
        AutoCloseable start(SipCredentials credentials,
                            SipRegistrar.RegistrationListener listener) throws Exception;
    }

    private final RegistrationProbe probe;

    /** Production constructor — registers against the real SIP proxy. */
    public SipTester() {
        this(SipTester::defaultProbe);
    }

    /** Test constructor — inject a fake probe. */
    public SipTester(final RegistrationProbe probe) {
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
    }

    /** Test the credentials using the default timeout. */
    public CompletableFuture<Result<Void>> test(final SipCredentials credentials) {
        return test(credentials, DEFAULT_TIMEOUT);
    }

    /**
     * Attempt a single REGISTER and report the outcome.
     *
     * @param credentials SIP credentials to validate
     * @param timeout     how long to wait for a registration response
     * @return a future completing with {@link Result.Ok} on success or
     *         {@link Result.Err} with a human-readable message on failure
     */
    public CompletableFuture<Result<Void>> test(final SipCredentials credentials, final Duration timeout) {
        Objects.requireNonNull(credentials, "credentials must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        final CompletableFuture<Result<Void>> future = new CompletableFuture<>();
        final AtomicReference<AutoCloseable> handle = new AtomicReference<>();

        final SipRegistrar.RegistrationListener listener = new SipRegistrar.RegistrationListener() {
            @Override public void onRegistered() {
                future.complete(Result.ok(null));
            }
            @Override public void onRegistrationFailed(final int statusCode, final String reason) {
                future.complete(Result.err(describeFailure(statusCode, reason)));
            }
            @Override public void onUnregistered() { /* not relevant for a one-shot test */ }
        };

        try {
            handle.set(probe.start(credentials, listener));
        } catch (final Exception e) {
            LOG.warn("SIP test failed to start", e);
            future.complete(Result.err("Could not start SIP test: " + rootMessage(e), e));
        }

        future.completeOnTimeout(
                Result.err("No response — check the SIP domain, proxy, and your network connection."),
                timeout.toMillis(), TimeUnit.MILLISECONDS);

        future.whenComplete((result, error) -> closeQuietly(handle.get()));
        return future;
    }

    private static String describeFailure(final int statusCode, final String reason) {
        return switch (statusCode) {
            case 401, 403, 407 -> "Authentication failed — check your SIP username and password.";
            case 404           -> "SIP user not found — check the username and domain.";
            default            -> "Registration failed (" + statusCode + " " + reason + ").";
        };
    }

    /** Walk the cause chain so the surfaced message reflects the real failure, not the JAIN-SIP wrapper. */
    private static String rootMessage(final Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message != null ? message : cause.getClass().getSimpleName();
    }

    private static void closeQuietly(final AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (final Exception e) {
            LOG.debug("Error closing SIP test resources: {}", e.getMessage());
        }
    }

    // ── Default real-network probe ──────────────────────────────────────────────

    private static AutoCloseable defaultProbe(final SipCredentials credentials,
                                              final SipRegistrar.RegistrationListener listener) throws Exception {
        // Bind to the local egress interface — NOT the STUN public IP, which is
        // not assigned to any local interface behind NAT and fails to bind.
        final String localIp = localBindAddress(credentials);
        final int localPort = freeUdpPort();

        // SipEngine needs a listener at construction, but the registrar that
        // handles responses can only be built once the engine exists. Forward
        // through a mutable reference and wire the registrar in afterwards.
        final AtomicReference<javax.sip.SipListener> forwarder = new AtomicReference<>();
        final SipEngine engine = new SipEngine(localIp, localPort, new DelegatingSipListener(forwarder));
        engine.start();

        final SipRegistrar registrar = new SipRegistrar(engine, credentials, listener);
        forwarder.set(registrar);
        registrar.start();

        return () -> {
            registrar.close();
            engine.close();
        };
    }

    /**
     * Resolve the local interface address that routes toward the SIP proxy.
     * Connecting a UDP socket selects the egress interface without sending any
     * packets, yielding the LAN IP the OS would use to reach the proxy.
     */
    private static String localBindAddress(final SipCredentials credentials) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(credentials.proxyHost()), credentials.proxyPort());
            final InetAddress local = socket.getLocalAddress();
            if (local != null && !local.isAnyLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (final Exception e) {
            LOG.debug("Could not resolve local bind address, falling back to {}: {}", FALLBACK_IP, e.getMessage());
        }
        return FALLBACK_IP;
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Forwards every SIP callback to a {@link javax.sip.SipListener} resolved lazily. */
    private record DelegatingSipListener(AtomicReference<javax.sip.SipListener> target)
            implements javax.sip.SipListener {

        @Override public void processRequest(final javax.sip.RequestEvent e) {
            final javax.sip.SipListener t = target.get();
            if (t != null) t.processRequest(e);
        }
        @Override public void processResponse(final javax.sip.ResponseEvent e) {
            final javax.sip.SipListener t = target.get();
            if (t != null) t.processResponse(e);
        }
        @Override public void processTimeout(final javax.sip.TimeoutEvent e) {
            final javax.sip.SipListener t = target.get();
            if (t != null) t.processTimeout(e);
        }
        @Override public void processIOException(final javax.sip.IOExceptionEvent e) {
            final javax.sip.SipListener t = target.get();
            if (t != null) t.processIOException(e);
        }
        @Override public void processTransactionTerminated(final javax.sip.TransactionTerminatedEvent e) {
            final javax.sip.SipListener t = target.get();
            if (t != null) t.processTransactionTerminated(e);
        }
        @Override public void processDialogTerminated(final javax.sip.DialogTerminatedEvent e) {
            final javax.sip.SipListener t = target.get();
            if (t != null) t.processDialogTerminated(e);
        }
    }
}
