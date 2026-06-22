package com.elitale.coldbirds.coldcalling.telephony.rtp;

import com.elitale.coldbirds.coldcalling.telephony.codec.G711Codec;
import org.jlibrtp.Participant;
import org.jlibrtp.RTPAppIntf;
import org.jlibrtp.DataFrame;
import org.jlibrtp.RTPSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Wraps a {@link RTPSession} from jlibrtp for a single call.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Open local UDP sockets for RTP (and RTCP on port+1).</li>
 *   <li>Register the remote participant.</li>
 *   <li>Send outbound PCMU-encoded RTP packets.</li>
 *   <li>Deliver inbound decoded PCM samples via a callback.</li>
 * </ul>
 *
 * <p>Must call {@link #close()} on every call termination to avoid thread leaks.
 */
public final class RtpSession implements RtpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(RtpSession.class);

    /** G.711 PCMU payload type (RTP dynamic payload type 0). */
    private static final int PCMU_PAYLOAD_TYPE = 0;

    private final int                localPort;
    private final G711Codec          codec;
    private final Consumer<short[]>  audioReceiver; // called on the jlibrtp receive thread

    private RTPSession  rtpSession;
    private boolean     started = false;

    /**
     * Create an RTP session.
     *
     * @param localPort     local RTP port (RTCP will use localPort+1)
     * @param codec         G711 codec for encode/decode
     * @param audioReceiver callback invoked with decoded PCM when audio arrives;
     *                      called on the jlibrtp thread — must not block
     */
    public RtpSession(
            final int             localPort,
            final G711Codec       codec,
            final Consumer<short[]> audioReceiver) {

        this.localPort     = localPort;
        this.codec         = Objects.requireNonNull(codec,         "codec must not be null");
        this.audioReceiver = Objects.requireNonNull(audioReceiver, "audioReceiver must not be null");
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Open sockets, register the remote peer, and start receiving.
     *
     * @param remoteIp   remote peer IP address
     * @param remotePort remote peer RTP port
     */
    public void start(final String remoteIp, final int remotePort) {
        Objects.requireNonNull(remoteIp, "remoteIp must not be null");
        try {
            final DatagramSocket rtpSocket  = new DatagramSocket(localPort);
            final DatagramSocket rtcpSocket = new DatagramSocket(localPort + 1);

            rtpSession = new RTPSession(rtpSocket, rtcpSocket);
            rtpSession.naivePktReception(true);

            final Participant remote = new Participant(remoteIp, remotePort, remotePort + 1);
            rtpSession.addParticipant(remote);

            rtpSession.registerRTPSession(buildAppIntf(), null, null);

            started = true;
            LOG.debug("RTP session started on port {} → {}:{}", localPort, remoteIp, remotePort);
        } catch (final Exception e) {
            LOG.error("Failed to start RTP session: {}", e.getMessage(), e);
        }
    }

    /**
     * Send 160 PCM samples (20 ms) as a G.711 PCMU RTP packet.
     *
     * @param pcmSamples 160 signed 16-bit samples; ignored if session not started
     */
    public void sendAudio(final short[] pcmSamples) {
        if (!started || rtpSession == null) return;
        final byte[] pcmu = codec.encode(pcmSamples);
        rtpSession.sendData(pcmu);
    }

    /**
     * End the RTP session and release sockets/threads.
     * <strong>Must be called on every call termination.</strong>
     */
    @Override
    public void close() {
        if (rtpSession != null) {
            rtpSession.endSession();
            rtpSession = null;
            started = false;
            LOG.debug("RTP session closed");
        }
    }

    public boolean isStarted() {
        return started;
    }

    // ------------------------------------------------------------------
    // Private — jlibrtp app interface
    // ------------------------------------------------------------------

    private RTPAppIntf buildAppIntf() {
        return new RTPAppIntf() {
            @Override
            public void receiveData(final DataFrame frame, final Participant participant) {
                if (frame == null || frame.getConcatenatedData() == null) return;
                final short[] pcm = codec.decode(frame.getConcatenatedData());
                audioReceiver.accept(pcm);
            }

            @Override
            public void userEvent(final int type, final Participant[] participants) {}

            @Override
            public int frameSize(final int payloadType) {
                return G711Codec.SAMPLES_PER_PACKET;
            }
        };
    }
}
