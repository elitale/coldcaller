package com.elitale.coldbirds.coldcalling.telephony.sip;

import java.util.Objects;

/**
 * Builds Session Description Protocol (SDP) offer/answer bodies (RFC 4566).
 *
 * <p>Supports G.711 PCMU (payload type 0) only, 8000 Hz, mono.
 * All lines use CRLF line endings as required by the spec.
 */
public final class SdpBuilder {

    private static final String CRLF = "\r\n";

    /** RTP payload type for G.711 PCMU. */
    private static final int PCMU_PAYLOAD_TYPE = 0;

    private SdpBuilder() {}

    /**
     * Build an SDP offer.
     *
     * @param localIp   local (or STUN-discovered public) IP address; must not be null
     * @param rtpPort   local RTP port; must be in range [1, 65535]
     * @param sessionId unique session identifier (typically epoch seconds or a long id); must not be null
     * @return SDP body as a CRLF-terminated string
     */
    public static String buildOffer(
            final String localIp,
            final int    rtpPort,
            final String sessionId) {

        Objects.requireNonNull(localIp,   "localIp must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        validatePort(rtpPort);

        return buildSdp(localIp, rtpPort, sessionId, "sendrecv");
    }

    /**
     * Build an SDP answer (typically in response to an inbound INVITE).
     *
     * @param localIp   local RTP address; must not be null
     * @param rtpPort   local RTP port; must be in range [1, 65535]
     * @param sessionId unique session identifier; must not be null
     * @return SDP body as a CRLF-terminated string
     */
    public static String buildAnswer(
            final String localIp,
            final int    rtpPort,
            final String sessionId) {

        Objects.requireNonNull(localIp,   "localIp must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        validatePort(rtpPort);

        return buildSdp(localIp, rtpPort, sessionId, "sendrecv");
    }

    // ------------------------------------------------------------------
    // Private
    // ------------------------------------------------------------------

    private static String buildSdp(
            final String localIp,
            final int    rtpPort,
            final String sessionId,
            final String direction) {

        final StringBuilder sb = new StringBuilder(256);

        // Session description
        sb.append("v=0").append(CRLF);
        sb.append("o=coldcalling ").append(sessionId)
          .append(" 1 IN IP4 ").append(localIp).append(CRLF);
        sb.append("s=coldCalling").append(CRLF);
        sb.append("c=IN IP4 ").append(localIp).append(CRLF);
        sb.append("t=0 0").append(CRLF);

        // Media description — audio only, PCMU
        sb.append("m=audio ").append(rtpPort)
          .append(" RTP/AVP ").append(PCMU_PAYLOAD_TYPE).append(CRLF);
        sb.append("a=rtpmap:").append(PCMU_PAYLOAD_TYPE)
          .append(" PCMU/8000").append(CRLF);
        sb.append("a=ptime:20").append(CRLF);
        sb.append("a=").append(direction).append(CRLF);

        return sb.toString();
    }

    private static void validatePort(final int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("RTP port out of range: " + port);
        }
    }
}
