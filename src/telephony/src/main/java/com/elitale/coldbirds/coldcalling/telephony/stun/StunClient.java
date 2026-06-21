package com.elitale.coldbirds.coldcalling.telephony.stun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.Optional;

/**
 * Minimal STUN client for NAT traversal (RFC 5389).
 *
 * <p>Sends a Binding Request to the configured STUN server and returns the
 * public (mapped) IP address and port discovered from the response.
 * Used on startup to populate the {@code c=} line in SDP offers.
 */
public final class StunClient {

    private static final Logger LOG = LoggerFactory.getLogger(StunClient.class);

    public static final String  DEFAULT_SERVER = "stun.telnyx.com";
    public static final int     DEFAULT_PORT   = 3478;
    private static final int    TIMEOUT_MS     = 3_000;
    private static final int    MAX_RETRIES    = 2;
    private static final int    BUFFER_SIZE    = 512;

    private final String server;
    private final int    port;

    public StunClient() {
        this(DEFAULT_SERVER, DEFAULT_PORT);
    }

    public StunClient(final String server, final int port) {
        this.server = server;
        this.port   = port;
    }

    /**
     * Perform a STUN Binding Request and return the mapped address.
     *
     * @return public IP + port, or {@link Optional#empty()} if unreachable
     */
    public Optional<StunMessage.MappedAddress> discover() {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                final Optional<StunMessage.MappedAddress> result = sendRequest();
                if (result.isPresent()) {
                    LOG.debug("STUN discovered public address: {}:{}", result.get().ip(), result.get().port());
                    return result;
                }
            } catch (final IOException e) {
                LOG.warn("STUN attempt {} failed: {}", attempt + 1, e.getMessage());
            }
        }
        LOG.warn("STUN discovery failed after {} attempts — NAT traversal may not work", MAX_RETRIES + 1);
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Private
    // ------------------------------------------------------------------

    private Optional<StunMessage.MappedAddress> sendRequest() throws IOException {
        final InetAddress remote = InetAddress.getByName(server);
        final byte[] request     = StunMessage.buildBindingRequest();
        final byte[] buf         = new byte[BUFFER_SIZE];

        try (final DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);

            final DatagramPacket send = new DatagramPacket(request, request.length, remote, port);
            socket.send(send);

            final DatagramPacket recv = new DatagramPacket(buf, buf.length);
            socket.receive(recv);

            final byte[] response = new byte[recv.getLength()];
            System.arraycopy(buf, 0, response, 0, recv.getLength());
            return StunMessage.parseMappedAddress(response);
        }
    }
}
