package com.elitale.coldbirds.coldcalling.telephony.sip;

import org.junit.jupiter.api.Test;

import javax.sip.SipListener;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression test for the JAIN-SIP log4j decoupling. The bundled default logger
 * requires {@code org.apache.log4j.*}; without {@link Slf4jStackLogger} the SIP
 * stack fails to instantiate with a {@link NoClassDefFoundError}.
 */
final class SipEngineStartTest {

    private static final SipListener NO_OP_LISTENER = new SipListener() {
        @Override public void processRequest(final javax.sip.RequestEvent e) { }
        @Override public void processResponse(final javax.sip.ResponseEvent e) { }
        @Override public void processTimeout(final javax.sip.TimeoutEvent e) { }
        @Override public void processIOException(final javax.sip.IOExceptionEvent e) { }
        @Override public void processTransactionTerminated(final javax.sip.TransactionTerminatedEvent e) { }
        @Override public void processDialogTerminated(final javax.sip.DialogTerminatedEvent e) { }
    };

    @Test
    void start_instantiatesStackWithoutLog4j() {
        final SipEngine engine = new SipEngine("127.0.0.1", freePort(), NO_OP_LISTENER);
        assertThatCode(() -> {
            engine.start();
            engine.close();
        }).doesNotThrowAnyException();
    }

    private static int freePort() {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (final java.net.SocketException e) {
            throw new IllegalStateException(e);
        }
    }
}
