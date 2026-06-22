package com.elitale.coldbirds.coldcalling.telephony;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.telephony.codec.G711Codec;
import com.elitale.coldbirds.coldcalling.telephony.rtp.AudioPipeline;
import com.elitale.coldbirds.coldcalling.telephony.rtp.CallRecorder;
import com.elitale.coldbirds.coldcalling.telephony.rtp.RecordingPaths;
import com.elitale.coldbirds.coldcalling.telephony.rtp.RtpSession;
import com.elitale.coldbirds.coldcalling.telephony.rtp.RtpTransport;
import com.elitale.coldbirds.coldcalling.telephony.rtp.SecureRtpSession;
import com.elitale.coldbirds.coldcalling.telephony.rtp.srtp.SrtpContext;
import com.elitale.coldbirds.coldcalling.telephony.rtp.srtp.SrtpKey;
import com.elitale.coldbirds.coldcalling.telephony.sip.*;
import com.elitale.coldbirds.coldcalling.telephony.sip.SdpParser.MediaTarget;
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
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

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

    /** Max INVITE auth resends per call to avoid challenge loops. */
    private static final int MAX_INVITE_AUTH_RETRIES = 1;

    /**
     * Marker prefix on the {@code onCallEnded} reason that flags a failure the UI
     * should surface to the user (as opposed to a normal hangup or unanswered call).
     */
    public static final String FAILURE_PREFIX = "failed:";

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

    private RtpTransport activeRtp;
    private AudioPipeline activePipeline;
    private String        activeCallId;
    private volatile Dialog        activeDialog;
    private volatile CallRecorder  activeRecorder;
    private volatile ScheduledFuture<?> callTimeoutTask;

    /** Holds pending inbound ServerTransactions keyed by SIP Call-ID. */
    private final Map<String, ServerTransaction> pendingInbound = new ConcurrentHashMap<>();

    /** In-flight outbound INVITEs awaiting auth/answer, keyed by SIP Call-ID. */
    private final Map<String, PendingInvite> pendingInvites = new ConcurrentHashMap<>();

    /** Recording file paths for finished calls, keyed by SIP Call-ID, until consumed. */
    private final Map<String, String> recordingPaths = new ConcurrentHashMap<>();

    /** Resolves the country for a remote E.164 number (for recording folders). */
    private volatile Function<String, Optional<Country>> countryResolver = number -> Optional.empty();

    /** Mutable context for an in-flight outbound INVITE awaiting auth/answer. */
    private static final class PendingInvite {
        final PhoneNumber  from;
        final PhoneNumber  to;
        final CallIdHeader callId;
        final String       fromTag;
        final String       requestUri;
        final SrtpKey      offerKey;
        int                attempts = 0;

        PendingInvite(final PhoneNumber from, final PhoneNumber to, final CallIdHeader callId,
                      final String fromTag, final String requestUri, final SrtpKey offerKey) {
            this.from = from; this.to = to; this.callId = callId;
            this.fromTag = fromTag; this.requestUri = requestUri; this.offerKey = offerKey;
        }
    }

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
     * Set the resolver that maps a remote E.164 number to its {@link Country},
     * used to choose the country folder for call recordings. Defaults to a
     * resolver that always returns empty (recordings land under {@code unknown}).
     *
     * @param resolver number → optional country; must not be null
     */
    public void setCountryResolver(final Function<String, Optional<Country>> resolver) {
        this.countryResolver = Objects.requireNonNull(resolver, "resolver must not be null");
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
        final String bindIp = discoverLocalIp();
        LOG.info("Using public IP {} for media; binding SIP to local IP {}", publicIp, bindIp);

        sipEngine = new SipEngine(bindIp, LOCAL_SIP_PORT, this);
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
            callTimeoutTask = scheduleCallTimeout(callId);
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
            final Request invite = serverTx.getRequest();
            final String offerSdp = sdpString(invite);
            final Optional<SrtpKey> remoteKey =
                    (offerSdp == null) ? Optional.empty() : SdpParser.parseCrypto(offerSdp);
            final SrtpKey answerKey = SrtpKey.random();

            final String sdp = SdpBuilder.buildAnswer(publicIp, LOCAL_RTP_PORT,
                    String.valueOf(System.currentTimeMillis()), answerKey.toInline());
            final Response ok = sipEngine.messageFactory()
                    .createResponse(Response.OK, invite);
            ok.setContent(sdp.getBytes(), sipEngine.headerFactory()
                    .createContentTypeHeader("application", "sdp"));
            sipEngine.sendResponse(ok, serverTx);

            activeDialog = serverTx.getDialog();
            activeCallId = callId;

            final String remoteNumber = sanitizeNumber(extractFromNumber(invite));
            final Optional<MediaTarget> target =
                    (offerSdp == null) ? Optional.empty() : SdpParser.parseAudio(offerSdp);
            if (target.isPresent() && remoteKey.isPresent()) {
                final MediaTarget t = target.get();
                final RtpTransport secure = new SecureRtpSession(
                        LOCAL_RTP_PORT, codec, answerKey.context(), remoteKey.get().context(),
                        this::onAudioReceived);
                startAudio(callId, remoteNumber, secure, t.ip(), t.port());
            } else if (target.isPresent()) {
                LOG.warn("Inbound INVITE {} had no SRTP crypto — using plain RTP", callId);
                startAudio(callId, remoteNumber, target.get().ip(), target.get().port());
            } else {
                startAudio(callId, remoteNumber, null, 0);
            }
        } catch (final Exception e) {
            LOG.error("Failed to answer call {}: {}", callId, e.getMessage(), e);
        }
    }

    /**
     * Hang up the active call: send BYE on the dialog (if any) and notify the listener.
     */
    public void hangUp() {
        final String callId = activeCallId;
        final Dialog dialog = activeDialog;
        cancelCallTimeout();
        stopAudio();
        if (dialog != null) {
            try {
                final Request bye = dialog.createRequest(Request.BYE);
                final ClientTransaction tx = sipEngine.sipProvider().getNewClientTransaction(bye);
                dialog.sendRequest(tx);
            } catch (final Exception e) {
                LOG.warn("Error sending BYE: {}", e.getMessage());
            }
        }
        activeDialog = null;
        if (callId != null) {
            LOG.info("Hanging up call {}", callId);
            activeCallId = null;
            listener.onCallEnded(callId, "hangup");
        }
    }

    /**
     * Remove and return the on-disk recording path for a finished call, if any.
     *
     * @param callId SIP Call-ID
     * @return the recording file path, or empty if the call was not recorded
     */
    public Optional<String> takeRecordingPath(final String callId) {
        return Optional.ofNullable(recordingPaths.remove(callId));
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

        if (isInviteResponse(event)) {
            if (status == Response.OK) {
                handleInvite200Ok(response, callId, event.getClientTransaction());
            } else if (status == Response.UNAUTHORIZED
                    || status == Response.PROXY_AUTHENTICATION_REQUIRED) {
                handleInviteAuthChallenge(response, callId);
            } else if (status >= 300) {
                final String failure = describeFailure(response);
                LOG.warn("Call {} failed: {}", callId, failure);
                pendingInvites.remove(callId);
                cancelCallTimeout();
                stopAudio();
                activeCallId = null;
                listener.onCallEnded(callId, FAILURE_PREFIX + failure);
            }
            // 1xx provisional (Trying/Ringing/Session Progress) — keep waiting
        }
    }

    @Override
    public void processTimeout(final TimeoutEvent event) {
        LOG.warn("SIP timeout — call may not have connected");
        if (activeCallId != null) {
            listener.onCallEnded(activeCallId, FAILURE_PREFIX + "No response from the server. Check your internet and try again.");
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
        cancelCallTimeout();
        stopAudio();
        activeDialog = null;
        activeCallId = null;
        listener.onCallEnded(callId, "bye");
    }

    private void handleInvite200Ok(
            final Response response,
            final String callId,
            final ClientTransaction tx) {

        try {
            final PendingInvite pending = pendingInvites.remove(callId);
            cancelCallTimeout();

            final Dialog dialog = tx.getDialog();
            if (dialog != null) {
                final Request ack = dialog.createAck(
                        ((CSeqHeader) response.getHeader(CSeqHeader.NAME)).getSeqNumber());
                dialog.sendAck(ack);
                activeDialog = dialog;
            }

            final String remoteNumber = sanitizeNumber(extractToNumber(response));
            final SrtpKey offerKey = (pending != null) ? pending.offerKey : null;
            startAnsweredMedia(callId, remoteNumber, sdpString(response), offerKey);
            listener.onCallAnswered(callId);
        } catch (final Exception e) {
            LOG.error("Error handling 200 OK for call {}: {}", callId, e.getMessage(), e);
        }
    }

    private void handleInviteAuthChallenge(final Response response, final String callId) {
        final PendingInvite pending = pendingInvites.get(callId);
        if (pending == null) {
            LOG.warn("Auth challenge for unknown call {}", callId);
            return;
        }
        if (pending.attempts >= MAX_INVITE_AUTH_RETRIES) {
            LOG.warn("INVITE authentication failed for {} after {} attempt(s)", callId, pending.attempts);
            pendingInvites.remove(callId);
            cancelCallTimeout();
            activeCallId = null;
            listener.onCallEnded(callId, FAILURE_PREFIX + "Sign-in failed. Check your SIP username and password in Settings.");
            return;
        }
        pending.attempts++;
        try {
            final boolean proxy = response.getStatusCode() == Response.PROXY_AUTHENTICATION_REQUIRED;
            final Header auth = SipDigestAuth.answer(
                    response, proxy, credentials.username(), credentials.password(),
                    Request.INVITE, pending.requestUri, sipEngine.headerFactory());
            final Request invite = buildInvite(
                    pending.from, pending.to, pending.callId, pending.fromTag,
                    cseq.getAndIncrement(), auth, pending.offerKey);
            sipEngine.sendRequest(invite);
            LOG.debug("Re-sent INVITE for {} with digest credentials", callId);
        } catch (final Exception e) {
            LOG.error("Failed to answer INVITE auth challenge for {}: {}", callId, e.getMessage(), e);
            pendingInvites.remove(callId);
            cancelCallTimeout();
            activeCallId = null;
            listener.onCallEnded(callId, FAILURE_PREFIX + "Couldn't sign in to the calling service. Check your SIP credentials in Settings.");
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

    private void startAudio(final String callId, final String remoteNumber,
                            final String remoteIp, final int remotePort) {
        startAudio(callId, remoteNumber,
                new RtpSession(LOCAL_RTP_PORT, codec, this::onAudioReceived),
                remoteIp, remotePort);
    }

    private void startAudio(final String callId, final String remoteNumber,
                            final RtpTransport transport,
                            final String remoteIp, final int remotePort) {
        stopAudio();
        activeRtp      = transport;
        activePipeline = new AudioPipeline(activeRtp, inputDevice, outputDevice);

        final CallRecorder recorder = createRecorder(callId, remoteNumber);
        if (recorder != null) {
            activePipeline.setRecorder(recorder);
            activeRecorder = recorder;
        }

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
        if (activeRecorder != null) { activeRecorder.close(); activeRecorder = null; }
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

    /**
     * Discover the primary local IPv4 address the OS would use to reach the
     * internet. The SIP listening point must bind to a real local interface
     * address (binding to the STUN-discovered public IP fails behind NAT).
     * No packets are sent — a connected UDP socket only selects a route.
     */
    private String discoverLocalIp() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            final String ip = socket.getLocalAddress().getHostAddress();
            return (ip == null || ip.isBlank() || "0.0.0.0".equals(ip)) ? FALLBACK_IP : ip;
        } catch (final Exception e) {
            LOG.warn("Could not determine local IP, binding to {}: {}", FALLBACK_IP, e.getMessage());
            return FALLBACK_IP;
        }
    }

    // ------------------------------------------------------------------
    // Private — INVITE building
    // ------------------------------------------------------------------

    private Request buildInvite(
            final PhoneNumber from, final PhoneNumber to, final CallIdHeader callId,
            final String fromTag, final long cseqNum, final Header authHeader,
            final SrtpKey offerKey) throws Exception {

        final AddressFactory af = sipEngine.addressFactory();
        final HeaderFactory  hf = sipEngine.headerFactory();
        final MessageFactory mf = sipEngine.messageFactory();

        final SipURI  requestUri = af.createSipURI(to.value(), credentials.proxyHost());
        requestUri.setTransportParam(sipEngine.transport());
        final Address fromAddr   = af.createAddress(af.createSipURI(from.value(), credentials.domain()));
        final Address toAddr     = af.createAddress(af.createSipURI(to.value(), credentials.domain()));

        final List<ViaHeader> via = List.of(hf.createViaHeader(
                sipEngine.localIp(), sipEngine.localPort(), sipEngine.transport(), null));
        final MaxForwardsHeader maxFwd  = hf.createMaxForwardsHeader(70);
        final CSeqHeader        cseqHdr = hf.createCSeqHeader(cseqNum, Request.INVITE);
        final FromHeader        fromHdr = hf.createFromHeader(fromAddr, fromTag);
        final ToHeader          toHdr   = hf.createToHeader(toAddr, null);

        final Request invite = mf.createRequest(
                requestUri, Request.INVITE, callId, cseqHdr, fromHdr, toHdr, via, maxFwd);

        // INVITE requires a Contact header so the peer can address in-dialog
        // requests (ACK, BYE) back to this UA. Mirror the registration Contact.
        final SipURI contactUri = af.createSipURI(credentials.username(), sipEngine.localIp());
        contactUri.setPort(sipEngine.localPort());
        contactUri.setTransportParam(sipEngine.transport());
        invite.addHeader(hf.createContactHeader(af.createAddress(contactUri)));

        final String sdp = SdpBuilder.buildOffer(publicIp, LOCAL_RTP_PORT,
                String.valueOf(System.currentTimeMillis()), offerKey.toInline());
        invite.setContent(sdp.getBytes(), hf.createContentTypeHeader("application", "sdp"));

        if (authHeader != null) {
            invite.addHeader(authHeader);
        }
        return invite;
    }

    private String sendInvite(final PhoneNumber from, final PhoneNumber to) throws Exception {
        final CallIdHeader callId  = sipEngine.sipProvider().getNewCallId();
        final String       fromTag = "call-" + System.currentTimeMillis();
        final SrtpKey      offerKey = SrtpKey.random();

        final Request invite     = buildInvite(from, to, callId, fromTag, cseq.getAndIncrement(), null, offerKey);
        final String  requestUri = invite.getRequestURI().toString();

        pendingInvites.put(callId.getCallId(),
                new PendingInvite(from, to, callId, fromTag, requestUri, offerKey));
        sipEngine.sendRequest(invite);
        return callId.getCallId();
    }

    /** Extract the SDP body of a message as a string, or {@code null} if absent. */
    private String sdpString(final Message message) {
        final byte[] raw = message.getRawContent();
        return (raw == null || raw.length == 0)
                ? null
                : new String(raw, StandardCharsets.UTF_8);
    }

    /**
     * Build a human-readable description of a final SIP error response (status
     * &ge; 300) for display to the user: a short, plain-language message that
     * tells the user what went wrong and what to do next.
     */
    private String describeFailure(final Response response) {
        return humanizeSipStatus(response.getStatusCode());
    }

    /** Translate a SIP status code into a short, plain-language message with a next step. */
    private static String humanizeSipStatus(final int code) {
        return switch (code) {
            case 400 -> "Something went wrong starting the call. Please try again.";
            case 401, 407 -> "Sign-in failed. Check your SIP username and password in Settings.";
            case 403 -> "Calls to this number aren't allowed. Enable this country in your account settings.";
            case 404, 604 -> "This number doesn't exist. Check the number and try again.";
            case 406, 488, 606 -> "Couldn't connect the call audio. Please try again.";
            case 408 -> "The call timed out. Check your internet and try again.";
            case 410 -> "This number is no longer active. Use a different number.";
            case 480 -> "They're unavailable right now. Try again later.";
            case 484 -> "This number is incomplete. Enter the full number and try again.";
            case 486, 600 -> "The line is busy. Try again later.";
            case 487 -> "The call was cancelled.";
            case 503 -> "Calling is temporarily unavailable. Try again in a few minutes.";
            case 500, 502, 504 -> "The calling service had a problem. Try again in a few minutes.";
            case 603, 607 -> "They declined the call.";
            default -> switch (code / 100) {
                case 3 -> "The call couldn't be connected. Please try again.";
                case 4 -> "The call couldn't be completed. Check the number and try again.";
                case 5 -> "The calling service had a problem. Try again in a few minutes.";
                case 6 -> "The call was declined.";
                default -> "The call failed. Please try again.";
            };
        };
    }

    /**
     * Start media for an answered outbound call. Prefers SRTP when the answer
     * carries an {@code a=crypto} line matching our offer; otherwise falls back
     * to plain RTP (or no audio when no media target is present).
     */
    private void startAnsweredMedia(final String callId, final String remoteNumber,
                                    final String sdp, final SrtpKey offerKey) {
        final Optional<MediaTarget> target =
                (sdp == null) ? Optional.empty() : SdpParser.parseAudio(sdp);
        if (target.isEmpty()) {
            LOG.warn("200 OK for {} carried no audio SDP — audio not started", callId);
            startAudio(callId, remoteNumber, null, 0);
            return;
        }
        final MediaTarget t = target.get();
        final Optional<SrtpKey> remoteKey = SdpParser.parseCrypto(sdp);
        if (offerKey != null && remoteKey.isPresent()) {
            try {
                final SrtpContext send = offerKey.context();
                final SrtpContext recv = remoteKey.get().context();
                final RtpTransport secure = new SecureRtpSession(
                        LOCAL_RTP_PORT, codec, send, recv, this::onAudioReceived);
                startAudio(callId, remoteNumber, secure, t.ip(), t.port());
                return;
            } catch (final GeneralSecurityException e) {
                LOG.error("Failed to set up SRTP for {}: {}", callId, e.getMessage(), e);
            }
        }
        LOG.warn("Answer for {} had no SRTP crypto — using plain RTP", callId);
        startAudio(callId, remoteNumber, t.ip(), t.port());
    }

    private CallRecorder createRecorder(final String callId, final String remoteNumber) {
        try {
            final Instant when = Instant.now();
            final String countryFolder =
                    RecordingPaths.countryFolder(countryResolver.apply(remoteNumber));
            final Path path = RecordingPaths.resolve(
                    RecordingPaths.defaultBaseDir(), when,
                    ZoneId.systemDefault(), countryFolder, remoteNumber);
            final CallRecorder recorder = new CallRecorder(path);
            recordingPaths.put(callId, path.toString());
            return recorder;
        } catch (final IOException e) {
            LOG.warn("Could not start call recording: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Private — helpers
    // ------------------------------------------------------------------

    private ScheduledFuture<?> scheduleCallTimeout(final String callId) {
        return timeoutScheduler.schedule(() -> {
            if (callId.equals(activeCallId)) {
                LOG.info("Call {} timed out after {}s", callId, CALL_TIMEOUT_SECONDS);
                pendingInvites.remove(callId);
                stopAudio();
                activeDialog = null;
                activeCallId = null;
                listener.onCallEnded(callId, "no-answer");
            }
        }, CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void cancelCallTimeout() {
        final ScheduledFuture<?> task = callTimeoutTask;
        if (task != null) {
            task.cancel(false);
            callTimeoutTask = null;
        }
    }

    private boolean isInviteResponse(final ResponseEvent event) {
        return event.getClientTransaction() != null
                && Request.INVITE.equals(
                        event.getClientTransaction().getRequest().getMethod());
    }

    private String extractFromNumber(final Message request) {
        final FromHeader from = (FromHeader) request.getHeader(FromHeader.NAME);
        if (from == null) return "unknown";
        final Address addr = from.getAddress();
        if (addr.getURI() instanceof final SipURI sipUri) {
            return sipUri.getUser() != null ? sipUri.getUser() : "unknown";
        }
        return "unknown";
    }

    private String extractToNumber(final Message request) {
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
