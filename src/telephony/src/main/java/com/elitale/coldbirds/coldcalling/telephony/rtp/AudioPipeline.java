package com.elitale.coldbirds.coldcalling.telephony.rtp;

import com.elitale.coldbirds.coldcalling.telephony.codec.G711Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Audio I/O pipeline for a live call.
 *
 * <p>Mic → 16-bit PCM capture → G.711 encode → {@link RtpSession#sendAudio}
 * <br>
 * {@link RtpSession} callback → G.711 decode → speaker playback
 *
 * <p><strong>Runs on a dedicated virtual thread.</strong> Never call on FX thread.
 * Always call {@link #close()} when the call ends to release audio devices.
 *
 * <p>Audio format: 8000 Hz, 16-bit signed, mono, little-endian — matches G.711 PCM input.
 */
public final class AudioPipeline implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AudioPipeline.class);

    private static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            8000f,   // sample rate
            16,      // sample size bits
            1,       // channels (mono)
            2,       // frame size (2 bytes = 16-bit mono)
            8000f,   // frame rate
            false    // little-endian
    );

    private static final int BUFFER_FRAMES  = G711Codec.SAMPLES_PER_PACKET; // 160 frames = 20 ms
    private static final int BUFFER_BYTES   = BUFFER_FRAMES * FORMAT.getFrameSize(); // 320 bytes

    private final RtpTransport  rtpSession;
    private final Mixer.Info    inputDevice;  // null = system default
    private final Mixer.Info    outputDevice; // null = system default

    private TargetDataLine  micLine;
    private SourceDataLine  speakerLine;
    private Thread          captureThread;

    /** Optional call recorder; when set, both audio directions are tapped. */
    private volatile CallRecorder recorder;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * @param rtpSession   active RTP session; must not be null
     * @param inputDevice  microphone mixer info, or null for system default
     * @param outputDevice speaker mixer info, or null for system default
     */
    public AudioPipeline(
            final RtpTransport rtpSession,
            final Mixer.Info  inputDevice,
            final Mixer.Info  outputDevice) {

        this.rtpSession   = Objects.requireNonNull(rtpSession, "rtpSession must not be null");
        this.inputDevice  = inputDevice;
        this.outputDevice = outputDevice;
    }

    /**
     * Attach a recorder to capture both call directions. Must be set before
     * {@link #start()}. The pipeline does not own the recorder's lifecycle —
     * the caller is responsible for closing it when the call ends.
     *
     * @param recorder the recorder, or null to disable recording
     */
    public void setRecorder(final CallRecorder recorder) {
        this.recorder = recorder;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Open audio devices and begin capture/playback.
     *
     * @throws LineUnavailableException if the audio device cannot be opened
     */
    public void start() throws LineUnavailableException {
        openMicrophone();
        openSpeaker();

        running.set(true);

        captureThread = Thread.ofVirtual()
                .name("audio-capture")
                .start(this::captureLoop);

        LOG.debug("Audio pipeline started");
    }

    /**
     * Stop capture and release all audio resources.
     * Registered as the {@link RtpSession} receive callback — do not block.
     */
    @Override
    public void close() {
        running.set(false);

        if (captureThread != null) {
            captureThread.interrupt();
        }
        if (micLine != null) {
            micLine.stop();
            micLine.close();
        }
        if (speakerLine != null) {
            speakerLine.drain();
            speakerLine.stop();
            speakerLine.close();
        }

        LOG.debug("Audio pipeline closed");
    }

    /**
     * Write decoded PCM received from the network to the speaker.
     * Called by {@link RtpSession} on the jlibrtp receive thread.
     *
     * @param pcm 160 signed 16-bit samples
     */
    public void receiveAudio(final short[] pcm) {
        if (!running.get() || speakerLine == null) return;

        final CallRecorder rec = recorder;
        if (rec != null) {
            rec.onRemoteFrame(pcm);
        }

        final byte[] bytes = shortsToBytes(pcm);
        speakerLine.write(bytes, 0, bytes.length);
    }

    // ------------------------------------------------------------------
    // Private — capture loop
    // ------------------------------------------------------------------

    private void captureLoop() {
        final byte[] buffer = new byte[BUFFER_BYTES];
        micLine.start();

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            final int read = micLine.read(buffer, 0, buffer.length);
            if (read < BUFFER_BYTES) continue;

            final short[] pcm = bytesToShorts(buffer);

            // Record every captured frame so the recording timeline is continuous,
            // independent of silence suppression on the outbound RTP stream.
            final CallRecorder rec = recorder;
            if (rec != null) {
                rec.onMicFrame(pcm);
            }

            if (!isSilent(pcm)) {
                rtpSession.sendAudio(pcm);
            }
        }
    }

    // ------------------------------------------------------------------
    // Private — device open
    // ------------------------------------------------------------------

    private void openMicrophone() throws LineUnavailableException {
        final DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (inputDevice != null) {
            micLine = (TargetDataLine) AudioSystem.getMixer(inputDevice).getLine(info);
        } else {
            micLine = (TargetDataLine) AudioSystem.getLine(info);
        }
        micLine.open(FORMAT, BUFFER_BYTES * 4);
    }

    private void openSpeaker() throws LineUnavailableException {
        final DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
        if (outputDevice != null) {
            speakerLine = (SourceDataLine) AudioSystem.getMixer(outputDevice).getLine(info);
        } else {
            speakerLine = (SourceDataLine) AudioSystem.getLine(info);
        }
        speakerLine.open(FORMAT, BUFFER_BYTES * 4);
        speakerLine.start();
    }

    // ------------------------------------------------------------------
    // Private — silence detection
    // ------------------------------------------------------------------

    /** Suppress RTP transmission if signal RMS is below threshold. */
    private static boolean isSilent(final short[] pcm) {
        long sum = 0;
        for (final short s : pcm) { sum += (long) s * s; }
        final double rms = Math.sqrt((double) sum / pcm.length);
        return rms < 200.0; // ~200/32767 ≈ 0.6% amplitude threshold
    }

    // ------------------------------------------------------------------
    // Private — PCM byte conversion (little-endian, 16-bit mono)
    // ------------------------------------------------------------------

    private static byte[] shortsToBytes(final short[] samples) {
        final byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2]     = (byte)  (samples[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return bytes;
    }

    private static short[] bytesToShorts(final byte[] bytes) {
        final short[] samples = new short[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
        }
        return samples;
    }
}
