package com.elitale.coldbirds.coldcalling.telephony.sip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sip.*;
import javax.sip.address.*;
import javax.sip.header.*;
import javax.sip.message.*;
import java.util.Objects;
import java.util.Properties;
import java.util.TooManyListenersException;

/**
 * Low-level JAIN-SIP stack wrapper.
 *
 * <p>Manages the SIP stack lifecycle and provides primitives to send
 * requests and responses. Higher-level SIP logic (REGISTER, INVITE)
 * is implemented in {@link SipRegistrar} and {@code CallManager}.
 *
 * <p><strong>Thread safety:</strong> the JAIN-SIP stack delivers all
 * {@link SipListener} callbacks on its own internal thread. All
 * callbacks must be non-blocking and must dispatch any UI updates via
 * {@code Platform.runLater()}.
 */
public final class SipEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SipEngine.class);

    /** Default SIP transport. Twilio Secure Media requires TLS. */
    private static final String DEFAULT_TRANSPORT = "tls";

    private final String            localIp;
    private final int               localPort;
    private final String            transport;
    private final SipListener       listener;

    private SipStack       sipStack;
    private SipProvider    sipProvider;
    private MessageFactory messageFactory;
    private AddressFactory addressFactory;
    private HeaderFactory  headerFactory;

    /**
     * Create a SipEngine bound to the given local address using TLS transport.
     *
     * @param localIp  local bindable IP; must not be null
     * @param localPort local SIP port
     * @param listener  callback receiver for all SIP events; must not be null
     */
    public SipEngine(
            final String      localIp,
            final int         localPort,
            final SipListener listener) {
        this(localIp, localPort, DEFAULT_TRANSPORT, listener);
    }

    /**
     * Create a SipEngine bound to the given local address and transport.
     *
     * @param localIp   local bindable IP; must not be null
     * @param localPort local SIP port
     * @param transport SIP transport ("tls", "tcp", or "udp"); must not be null
     * @param listener  callback receiver for all SIP events; must not be null
     */
    public SipEngine(
            final String      localIp,
            final int         localPort,
            final String      transport,
            final SipListener listener) {

        this.localIp   = Objects.requireNonNull(localIp,   "localIp must not be null");
        this.localPort = localPort;
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.listener  = Objects.requireNonNull(listener,  "listener must not be null");
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Initialise and start the JAIN-SIP stack.
     *
     * @throws SipException if the stack cannot be created or bound
     */
    public void start() throws SipException {
        try {
            final SipFactory factory = SipFactory.getInstance();
            factory.setPathName("gov.nist");

            final Properties props = buildStackProperties();
            sipStack = factory.createSipStack(props);

            messageFactory = factory.createMessageFactory();
            addressFactory = factory.createAddressFactory();
            headerFactory  = factory.createHeaderFactory();

            final ListeningPoint lp = sipStack.createListeningPoint(localIp, localPort, transport);
            sipProvider = sipStack.createSipProvider(lp);
            sipProvider.addSipListener(listener);

            LOG.info("SIP stack started on {}:{}/{}", localIp, localPort, transport);
        } catch (final TooManyListenersException e) {
            throw new SipException("Failed to register SIP listener", e);
        } catch (final Exception e) {
            throw new SipException("Failed to start SIP stack: " + e.getMessage(), e);
        }
    }

    /**
     * Stop the SIP stack and release all resources.
     */
    @Override
    public void close() {
        if (sipStack != null) {
            sipStack.stop();
            LOG.info("SIP stack stopped");
        }
    }

    // ------------------------------------------------------------------
    // Send
    // ------------------------------------------------------------------

    /**
     * Send a SIP request outside of an existing dialog.
     *
     * @param request the request to send; must not be null
     * @return the client transaction managing the request
     * @throws SipException if the request cannot be sent
     */
    public ClientTransaction sendRequest(final Request request) throws SipException {
        Objects.requireNonNull(request, "request must not be null");
        assertStarted();
        final ClientTransaction tx = sipProvider.getNewClientTransaction(request);
        tx.sendRequest();
        return tx;
    }

    /**
     * Send a SIP response on an existing server transaction.
     *
     * @param response the response to send; must not be null
     * @param tx       the server transaction; must not be null
     * @throws SipException if the response cannot be sent
     */
    public void sendResponse(final Response response, final ServerTransaction tx) throws SipException {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(tx,       "tx must not be null");
        assertStarted();
        try {
            tx.sendResponse(response);
        } catch (final InvalidArgumentException e) {
            throw new SipException("Invalid SIP response: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Factory accessors (for use by SipRegistrar, CallManager, etc.)
    // ------------------------------------------------------------------

    public MessageFactory messageFactory() {
        assertStarted();
        return messageFactory;
    }

    public AddressFactory addressFactory() {
        assertStarted();
        return addressFactory;
    }

    public HeaderFactory headerFactory() {
        assertStarted();
        return headerFactory;
    }

    public SipProvider sipProvider() {
        assertStarted();
        return sipProvider;
    }

    public String localIp() {
        return localIp;
    }

    public int localPort() {
        return localPort;
    }

    /** SIP transport in use ("tls", "tcp", or "udp"). */
    public String transport() {
        return transport;
    }

    // ------------------------------------------------------------------
    // Private
    // ------------------------------------------------------------------

    private Properties buildStackProperties() {
        final Properties props = new Properties();
        props.setProperty("javax.sip.STACK_NAME",          "coldcalling-sip");
        props.setProperty("javax.sip.IP_ADDRESS",          localIp);
        props.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "0"); // 0 = production
        props.setProperty("gov.nist.javax.sip.LOG_MESSAGE_CONTENT", "false");
        props.setProperty("gov.nist.javax.sip.DELIVER_TERMINATED_EVENT_FOR_ACK", "true");
        // TLS client config: we are the client (no client cert); verify Twilio's
        // server cert via the JVM default trust store (cacerts trusts Twilio's CA).
        props.setProperty("gov.nist.javax.sip.TLS_CLIENT_AUTH_TYPE",  "Disabled");
        props.setProperty("gov.nist.javax.sip.TLS_CLIENT_PROTOCOLS", "TLSv1.2,TLSv1.3");
        // Route the stack's logger through SLF4J. The bundled default
        // (gov.nist.core.LogWriter) hard-depends on log4j 1.x, which is absent,
        // so without this the stack fails to instantiate with NoClassDefFoundError.
        props.setProperty("gov.nist.javax.sip.STACK_LOGGER", Slf4jStackLogger.class.getName());
        return props;
    }

    private void assertStarted() {
        if (sipProvider == null) {
            throw new IllegalStateException("SipEngine has not been started — call start() first");
        }
    }
}
