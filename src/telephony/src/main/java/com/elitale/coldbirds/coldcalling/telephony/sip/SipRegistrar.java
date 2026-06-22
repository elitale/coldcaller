package com.elitale.coldbirds.coldcalling.telephony.sip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sip.*;
import javax.sip.address.*;
import javax.sip.header.*;
import javax.sip.message.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SIP REGISTER manager.
 *
 * <p>Sends an initial REGISTER on {@link #start()} and refreshes every
 * {@value #REFRESH_SECONDS} seconds using a virtual-thread-backed
 * {@link ScheduledExecutorService}.
 *
 * <p>Re-registers automatically after authentication challenges (401/407).
 * A {@link RegistrationListener} is notified on success or terminal failure.
 */
public final class SipRegistrar implements SipListener, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SipRegistrar.class);

    /** SIP REGISTER refresh interval in seconds (RFC 3261 recommends ≤ Expires/2). */
    public static final int REFRESH_SECONDS = 1800;

    /** SIP Contact header Expires value. Twilio rejects anything below its Min-Expires (600) with 423. */
    private static final int CONTACT_EXPIRES = 3600;

    /** Maximum authentication retry count per transaction to avoid infinite loops. */
    private static final int MAX_AUTH_RETRIES = 1;

    /** Callback for registration state changes. */
    public interface RegistrationListener {
        void onRegistered();
        void onRegistrationFailed(int statusCode, String reason);
        void onUnregistered();
    }

    private final SipEngine            engine;
    private final SipCredentials       credentials;
    private final RegistrationListener registrationListener;

    private final AtomicInteger          cseq        = new AtomicInteger(1);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    private ScheduledFuture<?> refreshTask;
    private boolean            isRegistered = false;
    private int                authRetries  = 0;

    public SipRegistrar(
            final SipEngine            engine,
            final SipCredentials       credentials,
            final RegistrationListener registrationListener) {

        this.engine               = Objects.requireNonNull(engine,               "engine must not be null");
        this.credentials          = Objects.requireNonNull(credentials,          "credentials must not be null");
        this.registrationListener = Objects.requireNonNull(registrationListener, "registrationListener must not be null");
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Send initial REGISTER and schedule periodic refresh.
     */
    public void start() {
        LOG.info("Starting SIP registration for sip:{}@{}", credentials.username(), credentials.domain());
        sendRegister(null);
        refreshTask = scheduler.scheduleAtFixedRate(
                () -> sendRegister(null),
                REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Unregister (Expires: 0) and stop the refresh scheduler.
     */
    @Override
    public void close() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
        }
        scheduler.shutdownNow();
        try {
            sendRegisterWithExpires(0, null);
        } catch (final Exception e) {
            LOG.warn("Error sending un-REGISTER: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // SipListener — only processResponse is relevant for REGISTER
    // ------------------------------------------------------------------

    @Override
    public void processResponse(final ResponseEvent event) {
        final Response response = event.getResponse();
        final int      status   = response.getStatusCode();

        if (!(event.getClientTransaction().getRequest().getMethod().equals(Request.REGISTER))) {
            return;
        }

        switch (status) {
            case Response.OK -> {
                LOG.info("SIP registration successful (200 OK)");
                isRegistered = true;
                registrationListener.onRegistered();
            }
            case Response.UNAUTHORIZED, Response.PROXY_AUTHENTICATION_REQUIRED -> {
                LOG.debug("SIP authentication challenge ({})", status);
                handleAuthChallenge(response, event.getClientTransaction());
            }
            default -> {
                LOG.warn("SIP registration failed: {} {}", status, response.getReasonPhrase());
                isRegistered = false;
                registrationListener.onRegistrationFailed(status, response.getReasonPhrase());
            }
        }
    }

    @Override public void processRequest(final RequestEvent event)  {}
    @Override public void processTimeout(final TimeoutEvent event)  {
        LOG.warn("SIP REGISTER timeout — will retry on next scheduled refresh");
        isRegistered = false;
    }
    @Override public void processIOException(final IOExceptionEvent event) {
        LOG.error("SIP IO error: {}:{}", event.getHost(), event.getPort());
    }
    @Override public void processTransactionTerminated(final TransactionTerminatedEvent event) {}
    @Override public void processDialogTerminated(final DialogTerminatedEvent event)           {}

    public boolean isRegistered() {
        return isRegistered;
    }

    // ------------------------------------------------------------------
    // Private — send REGISTER
    // ------------------------------------------------------------------

    private void sendRegister(final Header authHeader) {
        try {
            authRetries = 0;
            sendRegisterWithExpires(CONTACT_EXPIRES, authHeader);
        } catch (final Exception e) {
            LOG.error("Failed to send REGISTER: {}", e.getMessage(), e);
        }
    }

    private void sendRegisterWithExpires(final int expires, final Header authHeader)
            throws Exception {

        final AddressFactory af = engine.addressFactory();
        final HeaderFactory  hf = engine.headerFactory();
        final MessageFactory mf = engine.messageFactory();

        final String domain   = credentials.domain();
        final String username = credentials.username();

        final SipURI requestUri = af.createSipURI(null, domain);
        requestUri.setTransportParam(engine.transport());
        final Address toAddr    = af.createAddress(af.createSipURI(username, domain));
        final Address fromAddr  = af.createAddress(af.createSipURI(username, domain));
        final Address contactAddr = af.createAddress(
                af.createSipURI(username, engine.localIp()));
        ((SipURI) contactAddr.getURI()).setPort(engine.localPort());
        ((SipURI) contactAddr.getURI()).setTransportParam(engine.transport());

        final List<ViaHeader> via = List.of(hf.createViaHeader(
                engine.localIp(), engine.localPort(), engine.transport(), null));

        final MaxForwardsHeader maxFwd    = hf.createMaxForwardsHeader(70);
        final CallIdHeader      callId    = engine.sipProvider().getNewCallId();
        final CSeqHeader        cseqHdr   = hf.createCSeqHeader(cseq.getAndIncrement(), Request.REGISTER);
        final ToHeader          toHdr     = hf.createToHeader(toAddr, null);
        final FromHeader        fromHdr   = hf.createFromHeader(fromAddr, "reg-" + System.currentTimeMillis());
        final ExpiresHeader     expiresHdr = hf.createExpiresHeader(expires);
        final ContactHeader     contactHdr = hf.createContactHeader(contactAddr);
        contactHdr.setExpires(expires);

        final Request request = mf.createRequest(
                requestUri, Request.REGISTER, callId, cseqHdr,
                fromHdr, toHdr, via, maxFwd);

        request.addHeader(expiresHdr);
        request.addHeader(contactHdr);
        if (authHeader != null) {
            request.addHeader(authHeader);
        }

        engine.sendRequest(request);
    }

    private void handleAuthChallenge(final Response response, final ClientTransaction tx) {
        if (authRetries >= MAX_AUTH_RETRIES) {
            LOG.warn("SIP authentication failed after {} attempt(s)", authRetries);
            registrationListener.onRegistrationFailed(response.getStatusCode(), "Authentication failed");
            return;
        }
        authRetries++;
        try {
            final boolean proxy = response.getStatusCode() == Response.PROXY_AUTHENTICATION_REQUIRED;
            final Header authHeader = buildDigestHeader(response, proxy);
            sendRegisterWithExpires(CONTACT_EXPIRES, authHeader);
        } catch (final Exception e) {
            LOG.error("Failed to answer SIP auth challenge: {}", e.getMessage(), e);
            registrationListener.onRegistrationFailed(response.getStatusCode(), "Authentication error");
        }
    }

    /**
     * Build a Digest {@code Authorization}/{@code Proxy-Authorization} header in
     * answer to a 401/407 challenge (MD5, RFC 2617, optional {@code qop=auth}).
     */
    private Header buildDigestHeader(final Response response, final boolean proxy) throws Exception {
        return SipDigestAuth.answer(
                response, proxy,
                credentials.username(), credentials.password(),
                Request.REGISTER, "sip:" + credentials.domain(),
                engine.headerFactory());
    }
}
