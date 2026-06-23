package com.elitale.coldbirds.coldcalling.telephony.rtp;

import com.elitale.coldbirds.coldcalling.telephony.audio.AudioLevels;
import com.elitale.coldbirds.coldcalling.telephony.codec.G711Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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

    /** Normalized RMS below which a mic frame is treated as silence (≈0.6% amplitude). */
    private static final double SILENCE_LEVEL = 200.0 / 32768.0;

    private final RtpTransport  rtpSession;
    private final Mixer.Info    inputDevice;  // null = system default
    private final Mixer.Info    outputDevice; // null = system default

    private TargetDataLine  micLine;
    private SourceDataLine  speakerLine;
    private Thread          captureThread;

    /** Optional call recorder; when set, both audio directions are tapped. */
    private volatile CallRecorder recorder;

    /**
     * Pre-recorded greeting frames pending injection into the outbound RTP stream.
     * While {@link #greetingActive} is true the capture loop sends these frames in
     * place of live mic audio (voicemail drop). The recorder and RTP session are
     * left untouched.
     */
    private final Queue<short[]> greetingFrames = new ConcurrentLinkedQueue<>();
    private volatile boolean greetingActive = false;

    /** When true the live mic is suppressed on the outbound stream (user pressed Mute). */
    private volatile boolean muted = false;

    /** When true both directions are suppressed locally (user pressed Hold). */
    private volatile boolean held = false;

    /** Live normalized RMS levels (0..1), polled by the UI meter; reset to 0 on close. */
    private volatile double micLevel;
    private volatile double remoteLevel;

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

    /**
     * Live microphone level for the UI meter.
     *
     * @return normalized RMS (0..1) of the most recent captured frame, or 0 when idle
     */
    public double micLevel() {
        return micLevel;
    }

    /**
     * Live remote-party level for the UI meter.
     *
     * @return normalized RMS (0..1) of the most recent received frame, or 0 when idle
     */
    public double remoteLevel() {
        return remoteLevel;
    }

    /**
     * Queue a pre-recorded greeting for injection into the outbound RTP stream
     * (voicemail drop). The capture loop sends these frames in place of live mic
     * audio for their duration, then restores the mic. Ignored when the list is
     * empty or a greeting is already playing.
     *
     * @param frames 20 ms PCM frames (160 samples each); must not be null
     */
    public void playGreeting(final List<short[]> frames) {
        Objects.requireNonNull(frames, "frames must not be null");
        if (frames.isEmpty() || greetingActive) {
            return;
        }
        greetingFrames.clear();
        greetingFrames.addAll(frames);
        greetingActive = true;
    }

    /**
     * @return {@code true} while a queued greeting is still being injected
     */
    public boolean isPlayingGreeting() {
        return greetingActive;
    }

    /**
     * Mute or unmute the outbound microphone. While muted no live mic audio is sent
     * to the remote party; a queued voicemail greeting still plays, and the mic level
     * meter and recorder are unaffected.
     *
     * @param muted {@code true} to stop transmitting live mic audio
     */
    public void setMuted(final boolean muted) {
        this.muted = muted;
    }

    /** @return {@code true} while the outbound mic is muted */
    public boolean isMuted() {
        return muted;
    }

    /**
     * Place the call on local hold, or resume it. While held the mic is not
     * transmitted and received audio is not played, so neither party hears the
     * other. Clearing hold resumes both directions.
     *
     * @param held {@code true} to hold the call, {@code false} to resume
     */
    public void setHeld(final boolean held) {
        this.held = held;
    }

    /** @return {@code true} while the call is on local hold */
    public boolean isHeld() {
        return held;
    }

    /**
     * Decide which frame to push to RTP for one 20 ms capture tick. While a
     * greeting is active its frames take precedence over the mic; once exhausted
     * the mic resumes (with silence suppression). Package-private so the
     * injection logic is unit-tested without audio hardware.
     *
     * @param micPcm the freshly captured mic frame
     * @param level  the mic frame's normalized RMS level
     * @return the frame to send, or {@code null} to suppress (silence)
     */
    short[] selectOutboundFrame(final short[] micPcm, final double level) {
        if (greetingActive) {
            final short[] frame = greetingFrames.poll();
            if (frame != null) {
                return frame;
            }
            greetingActive = false; // exhausted → restore live mic
        }
        if (muted || held) {
            return null; // suppress live mic while muted or on hold
        }
        return level >= SILENCE_LEVEL ? micPcm : null;
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

        micLevel = 0.0;
        remoteLevel = 0.0;

        greetingActive = false;
        greetingFrames.clear();

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

        remoteLevel = AudioLevels.rms(pcm);

        final CallRecorder rec = recorder;
        if (rec != null) {
            rec.onRemoteFrame(pcm);
        }

        if (held) return; // on hold → record continuity is kept, but don't play the remote party

        final byte[] bytes = shortsToBytes(pcm);
        speakerLine.write(bytes, 0, bytes.length);
    }

    // ------------------------------------------------------------------
    // Private — capture loop
    // ------------------------------------------------------------------

    private void captureLoop() {
        final byte[] buffer = new byte[BUFFER_BYTES];
        micLine.start();
        boolean micActive = true;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            // Mute / hold physically release the mic line: the OS "microphone in use"
            // indicator goes off and the level meter falls to zero — not just silent
            // RTP. A queued voicemail greeting still needs the loop running, so it
            // overrides the release.
            final boolean releaseMic = (muted || held) && !greetingActive;
            if (releaseMic) {
                if (micActive) {
                    micLine.stop();
                    micLine.flush();
                    micActive = false;
                    micLevel = 0.0;
                }
                sleepQuietly();
                continue;
            }
            if (!micActive) {
                micLine.start(); // resume after unmute / off-hold
                micActive = true;
            }

            final int read = micLine.read(buffer, 0, buffer.length);
            if (read < BUFFER_BYTES) continue;

            final short[] pcm = bytesToShorts(buffer);
            final double level = AudioLevels.rms(pcm);
            micLevel = level;

            // Record every captured frame so the recording timeline is continuous,
            // independent of silence suppression on the outbound RTP stream.
            final CallRecorder rec = recorder;
            if (rec != null) {
                rec.onMicFrame(pcm);
            }

            final short[] outbound = selectOutboundFrame(pcm, level);
            if (outbound != null) {
                rtpSession.sendAudio(outbound);
            }
        }
    }

    /** Idle one frame interval while the mic line is released (muted / on hold). */
    private static void sleepQuietly() {
        try {
            Thread.sleep(20L); // one 20 ms frame; cheap on the audio virtual thread
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Private — device open
    // ------------------------------------------------------------------

    private void openMicrophone() throws LineUnavailableException {
        final DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        final LineFactory<TargetDataLine> configured =
                inputDevice == null ? null : () -> openCapture(inputDevice, info);
        micLine = openWithFallback(configured, () -> openCapture(null, info),
                msg -> LOG.warn("Configured microphone unavailable ({}); using system default", msg));
    }

    private void openSpeaker() throws LineUnavailableException {
        final DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
        final LineFactory<SourceDataLine> configured =
                outputDevice == null ? null : () -> openPlayback(outputDevice, info);
        speakerLine = openWithFallback(configured, () -> openPlayback(null, info),
                msg -> LOG.warn("Configured speaker unavailable ({}); using system default", msg));
        speakerLine.start();
    }

    private static TargetDataLine openCapture(final Mixer.Info device, final DataLine.Info info)
            throws LineUnavailableException {
        final TargetDataLine line = device == null
                ? (TargetDataLine) AudioSystem.getLine(info)
                : (TargetDataLine) AudioSystem.getMixer(device).getLine(info);
        line.open(FORMAT, BUFFER_BYTES * 4);
        return line;
    }

    private static SourceDataLine openPlayback(final Mixer.Info device, final DataLine.Info info)
            throws LineUnavailableException {
        final SourceDataLine line = device == null
                ? (SourceDataLine) AudioSystem.getLine(info)
                : (SourceDataLine) AudioSystem.getMixer(device).getLine(info);
        line.open(FORMAT, BUFFER_BYTES * 4);
        return line;
    }

    /**
     * Open the configured device, falling back to the system default when it is
     * unavailable. macOS Continuity devices (e.g. an "iPhone Microphone") enumerate
     * transiently, so a cached {@link Mixer.Info} can go stale and make
     * {@link AudioSystem#getMixer} throw {@link IllegalArgumentException}; an unplugged
     * or busy device throws {@link LineUnavailableException}. Either way the call stays
     * audible on the default device instead of failing the whole media session.
     *
     * <p>Package-private and static so the fallback path is unit-tested without hardware.
     *
     * @param configured    factory for the configured device, or null to use the default directly
     * @param systemDefault factory for the system-default device
     * @param onFallback    receives the failure message when a fallback occurs
     * @return the opened line
     * @throws LineUnavailableException if the system-default device also cannot be opened
     */
    static <T> T openWithFallback(
            final LineFactory<T> configured,
            final LineFactory<T> systemDefault,
            final Consumer<String> onFallback) throws LineUnavailableException {
        if (configured != null) {
            try {
                return configured.open();
            } catch (final IllegalArgumentException | LineUnavailableException e) {
                onFallback.accept(e.getMessage());
            }
        }
        return systemDefault.open();
    }

    /** Opens an audio line, propagating {@link LineUnavailableException}. */
    @FunctionalInterface
    interface LineFactory<T> {
        T open() throws LineUnavailableException;
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
