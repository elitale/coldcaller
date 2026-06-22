package com.elitale.coldbirds.coldcalling.telephony.rtp;

import com.elitale.coldbirds.coldcalling.telephony.codec.G711Codec;
import com.elitale.coldbirds.coldcalling.telephony.rtp.srtp.SrtpContext;
import com.elitale.coldbirds.coldcalling.telephony.rtp.srtp.SrtpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Secure RTP transport for a single call, used when the peer (e.g. Twilio
 * Secure Media) negotiates SRTP. Plain jlibrtp cannot encrypt, so this is a
 * raw {@link DatagramSocket} session that builds RTP packets itself and runs
 * each direction through an {@link SrtpContext}.
 *
 * <p>Outbound frames are PCMU-encoded, packed into a 12-byte RTP header, then
 * protected with the local SRTP context. Inbound packets are unprotected with
 * the remote SRTP context, the header stripped, and the PCMU payload decoded.
 *
 * <p>Assumes basic PCMU media: no CSRC list and no header extension (12-byte
 * RTP header), which matches the offer this app makes.
 */
public final class SecureRtpSession implements RtpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(SecureRtpSession.class);

    private static final int RTP_HEADER_LEN   = 12;
    private static final int PCMU_PAYLOAD_TYPE = 0;
    private static final int SAMPLES_PER_PACKET = G711Codec.SAMPLES_PER_PACKET; // 160
    private static final int RECEIVE_BUFFER    = 2048;

    private final int               localPort;
    private final G711Codec         codec;
    private final SrtpContext       sendContext;
    private final SrtpContext       recvContext;
    private final Consumer<short[]> audioReceiver; // called on the receive thread

    private final int  ssrc;
    private final SecureRandom random = new SecureRandom();

    private DatagramSocket socket;
    private InetAddress    remoteAddr;
    private int            remotePort;
    private Thread         receiveThread;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private int seq;
    private long timestamp;

    /**
     * @param localPort     local RTP port to bind
     * @param codec         G.711 codec for encode/decode
     * @param sendContext   SRTP context built from our offered key (outbound)
     * @param recvContext   SRTP context built from the peer's answered key (inbound)
     * @param audioReceiver decoded inbound PCM sink; called on the receive thread — must not block
     */
    public SecureRtpSession(
            final int               localPort,
            final G711Codec         codec,
            final SrtpContext       sendContext,
            final SrtpContext       recvContext,
            final Consumer<short[]> audioReceiver) {

        this.localPort     = localPort;
        this.codec         = Objects.requireNonNull(codec,         "codec must not be null");
        this.sendContext   = Objects.requireNonNull(sendContext,   "sendContext must not be null");
        this.recvContext   = Objects.requireNonNull(recvContext,   "recvContext must not be null");
        this.audioReceiver = Objects.requireNonNull(audioReceiver, "audioReceiver must not be null");
        this.ssrc          = random.nextInt();
        this.seq           = random.nextInt(0x10000);
        this.timestamp     = random.nextInt() & 0xFFFFFFFFL;
    }

    @Override
    public void start(final String remoteIp, final int remotePort) {
        Objects.requireNonNull(remoteIp, "remoteIp must not be null");
        try {
            this.socket     = new DatagramSocket(localPort);
            this.remoteAddr = InetAddress.getByName(remoteIp);
            this.remotePort = remotePort;
            started.set(true);

            receiveThread = Thread.ofVirtual()
                    .name("srtp-receive")
                    .start(this::receiveLoop);

            LOG.debug("SRTP session started on port {} → {}:{}", localPort, remoteIp, remotePort);
        } catch (final Exception e) {
            LOG.error("Failed to start SRTP session: {}", e.getMessage(), e);
        }
    }

    @Override
    public void sendAudio(final short[] pcmSamples) {
        if (!started.get() || socket == null) {
            return;
        }
        try {
            final byte[] payload = codec.encode(pcmSamples);
            final byte[] rtp = new byte[RTP_HEADER_LEN + payload.length];
            writeHeader(rtp);
            System.arraycopy(payload, 0, rtp, RTP_HEADER_LEN, payload.length);

            final byte[] srtp = sendContext.protect(rtp);
            socket.send(new DatagramPacket(srtp, srtp.length, remoteAddr, remotePort));

            seq = (seq + 1) & 0xFFFF;
            timestamp = (timestamp + SAMPLES_PER_PACKET) & 0xFFFFFFFFL;
        } catch (final Exception e) {
            LOG.debug("SRTP send failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        started.set(false);
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
            LOG.debug("SRTP session closed");
        }
    }

    // ------------------------------------------------------------------
    // Private
    // ------------------------------------------------------------------

    private void writeHeader(final byte[] rtp) {
        rtp[0] = (byte) 0x80;                 // V=2, P=0, X=0, CC=0
        rtp[1] = (byte) PCMU_PAYLOAD_TYPE;    // M=0, PT=0 (PCMU)
        rtp[2] = (byte) (seq >>> 8);
        rtp[3] = (byte) seq;
        rtp[4] = (byte) (timestamp >>> 24);
        rtp[5] = (byte) (timestamp >>> 16);
        rtp[6] = (byte) (timestamp >>> 8);
        rtp[7] = (byte) timestamp;
        rtp[8]  = (byte) (ssrc >>> 24);
        rtp[9]  = (byte) (ssrc >>> 16);
        rtp[10] = (byte) (ssrc >>> 8);
        rtp[11] = (byte) ssrc;
    }

    private void receiveLoop() {
        final byte[] buffer = new byte[RECEIVE_BUFFER];
        while (started.get() && !Thread.currentThread().isInterrupted()) {
            try {
                final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(Arrays.copyOf(buffer, packet.getLength()));
            } catch (final Exception e) {
                if (started.get()) {
                    LOG.debug("SRTP receive error: {}", e.getMessage());
                }
                return; // socket closed or interrupted
            }
        }
    }

    private void handlePacket(final byte[] srtp) {
        if (srtp.length < RTP_HEADER_LEN) {
            return;
        }
        try {
            final byte[] rtp = recvContext.unprotect(srtp);
            if (rtp.length <= RTP_HEADER_LEN) {
                return;
            }
            final byte[] payload = Arrays.copyOfRange(rtp, RTP_HEADER_LEN, rtp.length);
            audioReceiver.accept(codec.decode(payload));
        } catch (final SrtpException e) {
            LOG.debug("Dropping unauthenticated SRTP packet: {}", e.getMessage());
        }
    }
}
