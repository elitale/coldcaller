package com.elitale.coldbirds.coldcalling.telephony.rtp;

/**
 * Outbound audio transport for a single call: the audio pipeline pushes 20 ms
 * PCM frames here without knowing whether they travel over plain RTP or SRTP.
 */
public interface RtpTransport extends AutoCloseable {

    /**
     * Open sockets and point the session at the remote peer.
     *
     * @param remoteIp   remote peer IP address
     * @param remotePort remote peer RTP port
     */
    void start(String remoteIp, int remotePort);

    /**
     * Send 160 PCM samples (20 ms) as a G.711 PCMU packet.
     *
     * @param pcmSamples 160 signed 16-bit samples; ignored if not started
     */
    void sendAudio(short[] pcmSamples);

    /** Release sockets/threads. Must be called on every call termination. */
    @Override
    void close();
}
