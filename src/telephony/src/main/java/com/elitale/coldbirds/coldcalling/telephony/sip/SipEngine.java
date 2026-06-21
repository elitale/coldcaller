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

    private static final String TRANSPORT = "udp";

    private final String            localIp;
    private final int               localPort;
    private final SipListener       listener;

    private SipStack       sipStack;
    private SipProvider    sipProvider;
    private MessageFactory messageFactory;
    private AddressFactory addressFactory;
    private HeaderFactory  headerFactory;

    /**
     * Create a SipEngine bound to the given local address.
     *
     * @param localIp  local IP (may be STUN-discovered public IP); must not be null
     * @param localPort local SIP UDP port (typically 5060 or an ephemeral port)
     * @param listener  callback receiver for all SIP events; must not be null
     */
    public SipEngine(
            final String      localIp,
            final int         localPort,
            final SipListener listener) {

        this.localIp   = Objects.requireNonNull(localIp,   "localIp must not be null");
        this.localPort = localPort;
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

            final ListeningPoint lp = sipStack.createListeningPoint(localIp, localPort, TRANSPORT);
            sipProvider = sipStack.createSipProvider(lp);
            sipProvider.addSipListener(listener);

            LOG.info("SIP stack started on {}:{}", localIp, localPort);
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
        return props;
    }

    private void assertStarted() {
        if (sipProvider == null) {
            throw new IllegalStateException("SipEngine has not been started — call start() first");
        }
    }
}
