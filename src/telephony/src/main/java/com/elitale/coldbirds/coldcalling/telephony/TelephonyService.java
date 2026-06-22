package com.elitale.coldbirds.coldcalling.telephony;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.telephony.codec.G711Codec;
import com.elitale.coldbirds.coldcalling.telephony.rtp.AudioPipeline;
import com.elitale.coldbirds.coldcalling.telephony.rtp.RtpSession;
import com.elitale.coldbirds.coldcalling.telephony.sip.*;
import com.elitale.coldbirds.coldcalling.telephony.stun.StunClient;
import com.elitale.coldbirds.coldcalling.telephony.stun.StunMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sip.*;
import javax.sip.address.*;
import javax.sip.header.*;
import javax.sip.message.*;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Top-level telephony orchestrator.
 *
 * <p>Wires together the STUN client, SIP engine, SIP registrar, RTP session,
 * and audio pipeline. Exposes a clean call-control API to the service layer.
 *
 * <p>All callbacks ({@link TelephonyListener}) are delivered on the JAIN-SIP
 * internal thread. Callers must dispatch to the FX thread via
 * {@code Platform.runLater()} if they update UI.
 *
 * <p><strong>Thread model:</strong>
 * <ul>
 *   <li>SIP callbacks → JAIN-SIP thread → dispatch to callers</li>
 *   <li>Audio capture → virtual thread (audio-capture)</li>
 *   <li>Audio playback → jlibrtp receive thread → speakerLine.write</li>
 * </ul>
 */
public final class TelephonyService implements SipListener, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TelephonyService.class);

    private static final int    LOCAL_SIP_PORT   = 5060;
    private static final int    LOCAL_RTP_PORT   = 20000;
    private static final String FALLBACK_IP      = "127.0.0.1";

    /** Outbound call ring timeout in seconds. */
    private static final int CALL_TIMEOUT_SECONDS = 30;

    /** Callback interface for call lifecycle events. */
    public interface TelephonyListener {
        /**
         * An inbound call is ringing.
         *
         * @param callId       SIP Call-ID
         * @param callerNumber E.164 number of the remote caller
         * @param calledNumber E.164 number of the local line that was dialed
         */
        void onIncomingCall(String callId, PhoneNumber callerNumber, PhoneNumber calledNumber);
        /** An outbound call was answered (200 OK + SDP). */
        void onCallAnswered(String callId);
        /** A call ended (BYE, CANCEL, error, or timeout). */
        void onCallEnded(String callId, String reason);
        /** SIP registration state changed. */
        void onRegistrationChanged(boolean registered);
    }

    private final SipCredentials      credentials;
    private volatile TelephonyListener listener;
    private volatile Mixer.Info       inputDevice;
    private volatile Mixer.Info       outputDevice;

    private final G711Codec codec = new G711Codec();
    private final AtomicInteger cseq = new AtomicInteger(1);

    private SipEngine    sipEngine;
    private SipRegistrar sipRegistrar;
    private String       publicIp;

    private RtpSession   activeRtp;
    private AudioPipeline activePipeline;
    private String        activeCallId;

    /** Holds pending inbound ServerTransactions keyed by SIP Call-ID. */
    private final Map<String, ServerTransaction> pendingInbound = new ConcurrentHashMap<>();

    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    /**
     * @param credentials  SIP auth credentials; must not be null
     * @param listener     event callback; must not be null
     * @param inputDevice  microphone to use, or null for system default
     * @param outputDevice speaker to use, or null for system default
     */
    public TelephonyService(
            final SipCredentials    credentials,
            final TelephonyListener listener,
            final Mixer.Info        inputDevice,
            final Mixer.Info        outputDevice) {

        this.credentials  = Objects.requireNonNull(credentials, "credentials must not be null");
        this.listener     = Objects.requireNonNull(listener,    "listener must not be null");
        this.inputDevice  = inputDevice;
        this.outputDevice = outputDevice;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Replace the event listener after construction (used to break the
     * CallService ↔ TelephonyService circular dependency during DI wiring).
     *
     * @param listener new listener; must not be null
     */
    public void setListener(final TelephonyListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
    }

    /**
     * Update the audio devices used for the <em>next</em> call. Applied immediately without
     * a restart; an in-progress call keeps the devices it started with.
     *
     * @param inputDevice  microphone to use, or null for system default
     * @param outputDevice speaker to use, or null for system default
     */
    public void setAudioDevices(final Mixer.Info inputDevice, final Mixer.Info outputDevice) {
        this.inputDevice  = inputDevice;
        this.outputDevice = outputDevice;
    }

    /**
     * Initialise: STUN discovery → SIP engine start → SIP registration.
     *
     * @throws SipException if the SIP stack cannot be started
     */
    public void start() throws SipException {
        publicIp = discoverPublicIp();
        LOG.info("Using public IP: {}", publicIp);

        sipEngine = new SipEngine(publicIp, LOCAL_SIP_PORT, this);
        sipEngine.start();

        sipRegistrar = new SipRegistrar(sipEngine, credentials, new SipRegistrar.RegistrationListener() {
            @Override public void onRegistered()                              { listener.onRegistrationChanged(true);  }
            @Override public void onRegistrationFailed(int code, String r)   { listener.onRegistrationChanged(false); }
            @Override public void onUnregistered()                            { listener.onRegistrationChanged(false); }
        });
        sipRegistrar.start();
    }

    /**
     * Hang up any active call, unregister, and release all resources.
     */
    @Override
    public void close() {
        timeoutScheduler.shutdownNow();
        hangUp();
        if (sipRegistrar != null) sipRegistrar.close();
        if (sipEngine    != null) sipEngine.close();
    }

    // ------------------------------------------------------------------
    // Call control
    // ------------------------------------------------------------------

    /**
     * Initiate an outbound call.
     *
     * @param from the local phone number to show as caller ID
     * @param to   the destination E.164 number
     * @return the SIP Call-ID for the new call
     */
    public String dial(final PhoneNumber from, final PhoneNumber to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to,   "to must not be null");

        final String callId;
        try {
            callId = sendInvite(from, to);
            activeCallId = callId;
            scheduleCallTimeout(callId);
        } catch (final Exception e) {
            LOG.error("Failed to dial {}: {}", to.value(), e.getMessage(), e);
            return "";
        }
        return callId;
    }

    /**
     * Answer an inbound call identified by {@code callId}.
     * The corresponding {@link ServerTransaction} was stored when the INVITE arrived.
     *
     * @param callId the call ID from {@link TelephonyListener#onIncomingCall}
     */
    public void answer(final String callId) {
        Objects.requireNonNull(callId, "callId must not be null");
        final ServerTransaction serverTx = pendingInbound.remove(callId);
        if (serverTx == null) {
            LOG.warn("answer() called for unknown callId {}", callId);
            return;
        }
        try {
            final String sdp = SdpBuilder.buildAnswer(publicIp, LOCAL_RTP_PORT,
                    String.valueOf(System.currentTimeMillis()));
            final Response ok = sipEngine.messageFactory()
                    .createResponse(Response.OK, serverTx.getRequest());
            ok.setContent(sdp.getBytes(), sipEngine.headerFactory()
                    .createContentTypeHeader("application", "sdp"));
            sipEngine.sendResponse(ok, serverTx);

            startAudio(null, 0); // remote addr resolved from SDP in real impl
            activeCallId = callId;
        } catch (final Exception e) {
            LOG.error("Failed to answer call {}: {}", callId, e.getMessage(), e);
        }
    }

    /**
     * Hang up the active call.
     */
    public void hangUp() {
        stopAudio();
        if (activeCallId != null) {
            LOG.info("Hanging up call {}", activeCallId);
            activeCallId = null;
        }
    }

    // ------------------------------------------------------------------
    // SipListener
    // ------------------------------------------------------------------

    @Override
    public void processRequest(final RequestEvent event) {
        final Request req    = event.getRequest();
        final String  method = req.getMethod();

        switch (method) {
            case Request.INVITE -> handleInvite(event);
            case Request.BYE    -> handleBye(event);
            case Request.ACK    -> {}   // ACK on 200 OK — media already flowing
            default -> sendMethodNotAllowed(event);
        }
    }

    @Override
    public void processResponse(final ResponseEvent event) {
        // Delegate REGISTER responses to SipRegistrar
        if (event.getClientTransaction() != null
                && Request.REGISTER.equals(
                        event.getClientTransaction().getRequest().getMethod())) {
            sipRegistrar.processResponse(event);
            return;
        }

        final Response response = event.getResponse();
        final int      status   = response.getStatusCode();
        final String   callId   = ((CallIdHeader) response.getHeader(CallIdHeader.NAME))
                                    .getCallId();

        if (status == Response.OK && isInviteResponse(event)) {
            handleInvite200Ok(response, callId, event.getClientTransaction());
        } else if (status >= 400) {
            LOG.warn("Call {} failed with {}", callId, status);
            listener.onCallEnded(callId, String.valueOf(status));
            stopAudio();
        }
    }

    @Override
    public void processTimeout(final TimeoutEvent event) {
        LOG.warn("SIP timeout — call may not have connected");
        if (activeCallId != null) {
            listener.onCallEnded(activeCallId, "timeout");
        }
    }

    @Override public void processIOException(final IOExceptionEvent event)               {}
    @Override public void processTransactionTerminated(final TransactionTerminatedEvent e) {}
    @Override public void processDialogTerminated(final DialogTerminatedEvent event)       {}

    // ------------------------------------------------------------------
    // Private — SIP request handlers
    // ------------------------------------------------------------------

    private void handleInvite(final RequestEvent event) {
        try {
            final ServerTransaction serverTx = event.getServerTransaction() != null
                    ? event.getServerTransaction()
                    : sipEngine.sipProvider().getNewServerTransaction(event.getRequest());

            // Send 180 Ringing
            final Response ringing = sipEngine.messageFactory()
                    .createResponse(Response.RINGING, event.getRequest());
            serverTx.sendResponse(ringing);

            final String callId = ((CallIdHeader)
                    event.getRequest().getHeader(CallIdHeader.NAME)).getCallId();
            final String fromNumber = extractFromNumber(event.getRequest());
            final String toNumber   = extractToNumber(event.getRequest());

            // Store server transaction for answer()
            pendingInbound.put(callId, serverTx);

            listener.onIncomingCall(
                    callId,
                    new PhoneNumber(sanitizeNumber(fromNumber)),
                    new PhoneNumber(sanitizeNumber(toNumber)));
        } catch (final Exception e) {
            LOG.error("Error processing INVITE: {}", e.getMessage(), e);
        }
    }

    private void handleBye(final RequestEvent event) {
        try {
            final ServerTransaction serverTx = event.getServerTransaction() != null
                    ? event.getServerTransaction()
                    : sipEngine.sipProvider().getNewServerTransaction(event.getRequest());
            serverTx.sendResponse(sipEngine.messageFactory()
                    .createResponse(Response.OK, event.getRequest()));
        } catch (final Exception e) {
            LOG.warn("Error sending BYE 200 OK: {}", e.getMessage());
        }

        final String callId = ((CallIdHeader)
                event.getRequest().getHeader(CallIdHeader.NAME)).getCallId();
        stopAudio();
        listener.onCallEnded(callId, "bye");
    }

    private void handleInvite200Ok(
            final Response response,
            final String callId,
            final ClientTransaction tx) {

        try {
            // Send ACK
            final Dialog dialog = tx.getDialog();
            if (dialog != null) {
                final Request ack = dialog.createAck(
                        ((CSeqHeader) response.getHeader(CSeqHeader.NAME)).getSeqNumber());
                dialog.sendAck(ack);
            }
            // TODO: parse remote SDP to get RTP address/port
            startAudio(null, 0);
            listener.onCallAnswered(callId);
        } catch (final Exception e) {
            LOG.error("Error handling 200 OK for call {}: {}", callId, e.getMessage(), e);
        }
    }

    private void sendMethodNotAllowed(final RequestEvent event) {
        try {
            final ServerTransaction tx = event.getServerTransaction() != null
                    ? event.getServerTransaction()
                    : sipEngine.sipProvider().getNewServerTransaction(event.getRequest());
            tx.sendResponse(sipEngine.messageFactory()
                    .createResponse(Response.METHOD_NOT_ALLOWED, event.getRequest()));
        } catch (final Exception e) {
            LOG.warn("Error sending 405: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Private — audio
    // ------------------------------------------------------------------

    private void startAudio(final String remoteIp, final int remotePort) {
        stopAudio();
        activeRtp      = new RtpSession(LOCAL_RTP_PORT, codec, this::onAudioReceived);
        activePipeline = new AudioPipeline(activeRtp, inputDevice, outputDevice);

        if (remoteIp != null) {
            activeRtp.start(remoteIp, remotePort);
        }
        try {
            activePipeline.start();
        } catch (final LineUnavailableException e) {
            LOG.error("Cannot open audio devices: {}", e.getMessage(), e);
        }
    }

    private void stopAudio() {
        if (activePipeline != null) { activePipeline.close(); activePipeline = null; }
        if (activeRtp      != null) { activeRtp.close();      activeRtp      = null; }
    }

    private void onAudioReceived(final short[] pcm) {
        if (activePipeline != null) {
            activePipeline.receiveAudio(pcm);
        }
    }

    // ------------------------------------------------------------------
    // Private — STUN
    // ------------------------------------------------------------------

    private String discoverPublicIp() {
        return new StunClient().discover()
                .map(StunMessage.MappedAddress::ip)
                .orElse(FALLBACK_IP);
    }

    // ------------------------------------------------------------------
    // Private — INVITE building
    // ------------------------------------------------------------------

    private String sendInvite(final PhoneNumber from, final PhoneNumber to) throws Exception {
        final AddressFactory af = sipEngine.addressFactory();
        final HeaderFactory  hf = sipEngine.headerFactory();
        final MessageFactory mf = sipEngine.messageFactory();

        final SipURI requestUri = af.createSipURI(to.value(), credentials.proxyHost());
        final Address fromAddr  = af.createAddress(af.createSipURI(from.value(), credentials.domain()));
        final Address toAddr    = af.createAddress(af.createSipURI(to.value(), credentials.domain()));

        final List<ViaHeader> via = List.of(hf.createViaHeader(
                sipEngine.localIp(), sipEngine.localPort(), "udp", null));
        final MaxForwardsHeader maxFwd  = hf.createMaxForwardsHeader(70);
        final CallIdHeader      callId  = sipEngine.sipProvider().getNewCallId();
        final CSeqHeader        cseqHdr = hf.createCSeqHeader(cseq.getAndIncrement(), Request.INVITE);
        final FromHeader        fromHdr = hf.createFromHeader(fromAddr, "call-" + System.currentTimeMillis());
        final ToHeader          toHdr   = hf.createToHeader(toAddr, null);

        final Request invite = mf.createRequest(
                requestUri, Request.INVITE, callId, cseqHdr, fromHdr, toHdr, via, maxFwd);

        final String sdp = SdpBuilder.buildOffer(publicIp, LOCAL_RTP_PORT,
                String.valueOf(System.currentTimeMillis()));
        invite.setContent(sdp.getBytes(),
                hf.createContentTypeHeader("application", "sdp"));

        sipEngine.sendRequest(invite);
        return callId.getCallId();
    }

    // ------------------------------------------------------------------
    // Private — helpers
    // ------------------------------------------------------------------

    private void scheduleCallTimeout(final String callId) {
        timeoutScheduler.schedule(() -> {
            if (callId.equals(activeCallId)) {
                LOG.info("Call {} timed out after {}s", callId, CALL_TIMEOUT_SECONDS);
                hangUp();
                listener.onCallEnded(callId, "no-answer");
            }
        }, CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private boolean isInviteResponse(final ResponseEvent event) {
        return event.getClientTransaction() != null
                && Request.INVITE.equals(
                        event.getClientTransaction().getRequest().getMethod());
    }

    private String extractFromNumber(final Request request) {
        final FromHeader from = (FromHeader) request.getHeader(FromHeader.NAME);
        if (from == null) return "unknown";
        final Address addr = from.getAddress();
        if (addr.getURI() instanceof final SipURI sipUri) {
            return sipUri.getUser() != null ? sipUri.getUser() : "unknown";
        }
        return "unknown";
    }

    private String extractToNumber(final Request request) {
        final ToHeader to = (ToHeader) request.getHeader(ToHeader.NAME);
        if (to == null) return "unknown";
        final Address addr = to.getAddress();
        if (addr.getURI() instanceof final SipURI sipUri) {
            return sipUri.getUser() != null ? sipUri.getUser() : "unknown";
        }
        return "unknown";
    }

    /** Ensure the number is E.164 — prefix with + if missing. */
    private static String sanitizeNumber(final String raw) {
        if (raw.startsWith("+")) return raw;
        return "+" + raw;
    }
}
