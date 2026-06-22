package com.elitale.coldbirds.coldcalling.telephony.sip;

import com.elitale.coldbirds.coldcalling.telephony.rtp.srtp.SrtpKey;

import java.util.Objects;
import java.util.Optional;

/**
 * Minimal SDP (RFC 4566) reader: extracts the remote audio RTP destination
 * (IP + port) from an answer/offer body so RTP can be pointed at the peer.
 *
 * <p>Only what an audio call needs: the connection address ({@code c=IN IP4 ...})
 * and the audio media port ({@code m=audio <port> ...}). A media-level
 * {@code c=} line, when present, overrides the session-level one.
 */
public final class SdpParser {

    private SdpParser() {}

    /** Remote audio RTP destination. */
    public record MediaTarget(String ip, int port) {
        public MediaTarget {
            Objects.requireNonNull(ip, "ip must not be null");
            if (ip.isBlank()) {
                throw new IllegalArgumentException("ip must not be blank");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
        }
    }

    /**
     * Parse the audio RTP target from an SDP body.
     *
     * @param sdp the SDP body (may use CRLF or LF line endings); must not be null
     * @return the audio destination if both a connection IP and an audio port
     *         with a valid port were found; otherwise empty
     */
    public static Optional<MediaTarget> parseAudio(final String sdp) {
        Objects.requireNonNull(sdp, "sdp must not be null");

        String sessionIp = null;
        String mediaIp   = null;
        int    audioPort = -1;
        boolean inAudio  = false;

        for (final String raw : sdp.split("\\r?\\n")) {
            final String line = raw.strip();
            if (line.startsWith("m=")) {
                inAudio = line.startsWith("m=audio");
                if (inAudio) {
                    audioPort = parseAudioPort(line);
                }
            } else if (line.startsWith("c=")) {
                final String ip = parseConnectionIp(line);
                if (ip != null) {
                    if (inAudio) {
                        mediaIp = ip;
                    } else {
                        sessionIp = ip;
                    }
                }
            }
        }

        final String ip = (mediaIp != null) ? mediaIp : sessionIp;
        if (ip == null || audioPort < 1) {
            return Optional.empty();
        }
        return Optional.of(new MediaTarget(ip, audioPort));
    }

    /**
     * Parse the SRTP keying material from the first {@code a=crypto} line that
     * advertises the {@code AES_CM_128_HMAC_SHA1_80} suite.
     *
     * @param sdp the SDP body (may use CRLF or LF line endings); must not be null
     * @return the remote SRTP key if a matching crypto line with a valid
     *         {@code inline:} value was found; otherwise empty
     */
    public static Optional<SrtpKey> parseCrypto(final String sdp) {
        Objects.requireNonNull(sdp, "sdp must not be null");

        for (final String raw : sdp.split("\\r?\\n")) {
            final String line = raw.strip();
            if (!line.startsWith("a=crypto:")) {
                continue;
            }
            final String[] parts = line.split("\\s+");
            // a=crypto:<tag> <suite> inline:<key>[ ...]
            if (parts.length < 3 || !SrtpKey.SUITE.equals(parts[1])) {
                continue;
            }
            if (!parts[2].startsWith("inline:")) {
                continue;
            }
            // key-params: <base64>|<lifetime>|<MKI> — take the base64 before the first '|'.
            final String inline = parts[2].substring("inline:".length()).split("\\|", 2)[0];
            final Optional<SrtpKey> key = SrtpKey.fromInline(inline);
            if (key.isPresent()) {
                return key;
            }
        }
        return Optional.empty();
    }

    /** {@code m=audio <port> RTP/AVP ...} → port, or -1 if unparseable. */
    private static int parseAudioPort(final String line) {
        final String[] parts = line.split("\\s+");
        if (parts.length < 2) {
            return -1;
        }
        try {
            final int port = Integer.parseInt(parts[1]);
            return (port >= 1 && port <= 65535) ? port : -1;
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    /** {@code c=IN IP4 <ip>} → ip, or null if not an IP4 connection line. */
    private static String parseConnectionIp(final String line) {
        final String[] parts = line.split("\\s+");
        if (parts.length < 3) {
            return null;
        }
        // parts[0] = "c=IN", parts[1] = "IP4", parts[2] = address
        if (!"IP4".equalsIgnoreCase(parts[1])) {
            return null;
        }
        final String ip = parts[2];
        return ip.isBlank() ? null : ip;
    }
}
