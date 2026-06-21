package com.elitale.coldbirds.coldcalling.telephony.stun;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds and parses minimal STUN messages (RFC 5389).
 *
 * <p>Only Binding Request / Binding Success Response with
 * XOR-MAPPED-ADDRESS are supported — sufficient for NAT discovery.
 */
public final class StunMessage {

    /** RFC 5389 magic cookie: 0x2112A442. */
    public static final int MAGIC_COOKIE = 0x2112A442;

    /** STUN message type: Binding Request. */
    private static final short MSG_BINDING_REQUEST  = 0x0001;

    /** STUN message type: Binding Success Response. */
    private static final short MSG_BINDING_RESPONSE = 0x0101;

    /** XOR-MAPPED-ADDRESS attribute type. */
    private static final short ATTR_XOR_MAPPED_ADDRESS = 0x0020;

    /** MAPPED-ADDRESS attribute type (RFC 3489 compat). */
    private static final short ATTR_MAPPED_ADDRESS = 0x0001;

    /** IPv4 address family in STUN attribute. */
    private static final byte FAMILY_IPV4 = 0x01;

    /** STUN header size in bytes. */
    private static final int HEADER_SIZE = 20;

    private static final SecureRandom RNG = new SecureRandom();

    /** Resolved mapped address (public IP + port) returned by the STUN server. */
    public record MappedAddress(String ip, int port) {
        public MappedAddress {
            Objects.requireNonNull(ip, "ip must not be null");
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid port: " + port);
            }
        }
    }

    private StunMessage() {}

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    /**
     * Build a 20-byte STUN Binding Request with a random transaction ID.
     *
     * <p>Format (RFC 5389 §6):
     * <pre>
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |0 0|  STUN Message Type (14)   |    Message Length (16)        |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                     Magic Cookie (32)                         |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                     Transaction ID (96 bits)                  |
     * |                                                               |
     * |                                                               |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * </pre>
     */
    public static byte[] buildBindingRequest() {
        final ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.putShort(MSG_BINDING_REQUEST);
        buf.putShort((short) 0);         // no attributes
        buf.putInt(MAGIC_COOKIE);

        final byte[] txId = new byte[12];
        RNG.nextBytes(txId);
        buf.put(txId);

        return buf.array();
    }

    // ------------------------------------------------------------------
    // Parse
    // ------------------------------------------------------------------

    /**
     * Parse a STUN Binding Success Response and extract the mapped address.
     *
     * @param response the raw UDP payload from the STUN server; must not be null
     * @return the public IP + port, or {@link Optional#empty()} if the response
     *         is not a Binding Success Response or contains no address attribute
     */
    public static Optional<MappedAddress> parseMappedAddress(final byte[] response) {
        Objects.requireNonNull(response, "response must not be null");

        if (response.length < HEADER_SIZE) {
            return Optional.empty();
        }

        final ByteBuffer buf = ByteBuffer.wrap(response);
        final short msgType  = buf.getShort();

        if (msgType != MSG_BINDING_RESPONSE) {
            return Optional.empty();
        }

        final int msgLength = buf.getShort() & 0xFFFF;
        buf.getInt(); // skip magic cookie (already validated structurally)
        buf.position(HEADER_SIZE); // skip transaction ID

        // Walk attributes
        final int end = HEADER_SIZE + msgLength;
        while (buf.position() + 4 <= end && buf.position() + 4 <= response.length) {
            final short attrType   = buf.getShort();
            final int   attrLength = buf.getShort() & 0xFFFF;

            if (attrType == ATTR_XOR_MAPPED_ADDRESS) {
                return parseXorMappedAddress(buf);
            } else if (attrType == ATTR_MAPPED_ADDRESS) {
                return parsePlainMappedAddress(buf);
            } else {
                // Skip unknown attribute (STUN attributes are 4-byte aligned)
                final int skip = attrLength + ((4 - (attrLength % 4)) % 4);
                if (buf.position() + skip > response.length) break;
                buf.position(buf.position() + skip);
            }
        }

        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private static Optional<MappedAddress> parseXorMappedAddress(final ByteBuffer buf) {
        buf.get();                             // reserved byte
        final byte family = buf.get();
        if (family != FAMILY_IPV4) {
            return Optional.empty();           // IPv6 not supported
        }

        final int xoredPort = buf.getShort() & 0xFFFF;
        final int xoredIp   = buf.getInt();

        final int port = xoredPort ^ (MAGIC_COOKIE >>> 16);
        final int ip   = xoredIp   ^ MAGIC_COOKIE;

        return Optional.of(new MappedAddress(intToIpv4(ip), port));
    }

    private static Optional<MappedAddress> parsePlainMappedAddress(final ByteBuffer buf) {
        buf.get();                             // reserved
        final byte family = buf.get();
        if (family != FAMILY_IPV4) {
            return Optional.empty();
        }

        final int port = buf.getShort() & 0xFFFF;
        final int ip   = buf.getInt();

        return Optional.of(new MappedAddress(intToIpv4(ip), port));
    }

    private static String intToIpv4(final int ip) {
        return ((ip >>> 24) & 0xFF) + "."
             + ((ip >>> 16) & 0xFF) + "."
             + ((ip >>>  8) & 0xFF) + "."
             +  (ip         & 0xFF);
    }
}
