package com.elitale.coldbirds.coldcalling.telephony.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

/**
 * Google-Meet-style audio device testing: a live microphone level meter and a
 * speaker test tone. All device I/O runs on virtual threads; never call on the FX thread.
 *
 * <p>The pure helper {@link #generateTone(int, int)} carries the tone-generation logic and is
 * unit-tested without hardware; level math lives in {@link AudioLevels}.
 */
public final class AudioDeviceTester {

    private static final Logger LOG = LoggerFactory.getLogger(AudioDeviceTester.class);

    private static final AudioFormat FORMAT = AudioDeviceManager.FORMAT;
    private static final int    SAMPLE_RATE = 8000;
    private static final int    FRAME_SAMPLES = 160;               // 20 ms
    private static final int    FRAME_BYTES   = FRAME_SAMPLES * 2; // 16-bit mono
    private static final double FULL_SCALE    = 32768.0;
    private static final int    TONE_FREQ_HZ  = 440;
    private static final int    TONE_MS       = 700;
    private static final double TONE_GAIN     = 0.5;               // half full-scale

    private final AtomicBoolean playing = new AtomicBoolean(false);

    /**
     * Open the given microphone and stream normalized RMS levels (0..1) to {@code onLevel}
     * roughly every 20 ms until the returned handle is closed.
     *
     * @param input   microphone mixer, or null for the system default
     * @param onLevel receives a level in [0,1]; invoked on the capture thread
     * @return a handle; call {@link AutoCloseable#close()} to stop and release the device
     * @throws LineUnavailableException if the microphone cannot be opened
     */
    public AutoCloseable startMicMeter(final Mixer.Info input, final DoubleConsumer onLevel)
            throws LineUnavailableException {
        Objects.requireNonNull(onLevel, "onLevel must not be null");

        final DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        final TargetDataLine line = input != null
                ? (TargetDataLine) AudioSystem.getMixer(input).getLine(info)
                : (TargetDataLine) AudioSystem.getLine(info);
        line.open(FORMAT, FRAME_BYTES * 4);
        line.start();

        final AtomicBoolean active = new AtomicBoolean(true);
        final Thread reader = Thread.ofVirtual().name("mic-meter").start(() -> {
            final byte[] buffer = new byte[FRAME_BYTES];
            while (active.get() && !Thread.currentThread().isInterrupted()) {
                final int read = line.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                onLevel.accept(AudioLevels.rms(bytesToShorts(buffer, read)));
            }
        });

        return () -> {
            active.set(false);
            reader.interrupt();
            line.stop();
            line.close();
        };
    }

    /**
     * Play a short test tone through the given speaker. Ignored if a tone is already playing.
     *
     * @param output speaker mixer, or null for the system default
     */
    public void playTestTone(final Mixer.Info output) {
        if (!playing.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("speaker-test").start(() -> {
            try {
                writeTone(output);
            } catch (final LineUnavailableException e) {
                LOG.warn("Speaker test failed: {}", e.getMessage());
            } finally {
                playing.set(false);
            }
        });
    }

    private void writeTone(final Mixer.Info output) throws LineUnavailableException {
        final DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
        final SourceDataLine line = output != null
                ? (SourceDataLine) AudioSystem.getMixer(output).getLine(info)
                : (SourceDataLine) AudioSystem.getLine(info);
        line.open(FORMAT, FRAME_BYTES * 8);
        line.start();
        try {
            final byte[] bytes = shortsToBytes(generateTone(TONE_FREQ_HZ, TONE_MS));
            line.write(bytes, 0, bytes.length);
            line.drain();
        } finally {
            line.stop();
            line.close();
        }
    }

    // ── Pure signal helpers (unit-tested without hardware) ────────────────────────

    /**
     * Generate a mono 8 kHz PCM sine tone with short linear fade-in/out to avoid clicks.
     *
     * @param freqHz tone frequency in hertz
     * @param ms     duration in milliseconds
     * @return signed 16-bit samples
     */
    public static short[] generateTone(final int freqHz, final int ms) {
        final int count = Math.max(0, SAMPLE_RATE * ms / 1000);
        final short[] samples = new short[count];
        final int fade = Math.min(count / 2, SAMPLE_RATE / 100); // up to 10 ms fade
        final double amplitude = TONE_GAIN * (FULL_SCALE - 1);

        for (int i = 0; i < count; i++) {
            double gain = 1.0;
            if (fade > 0) {
                if (i < fade) {
                    gain = (double) i / fade;
                } else if (i >= count - fade) {
                    gain = (double) (count - i) / fade;
                }
            }
            final double angle = 2.0 * Math.PI * freqHz * i / SAMPLE_RATE;
            samples[i] = (short) Math.round(Math.sin(angle) * amplitude * gain);
        }
        return samples;
    }

    // ── Byte/short conversion (little-endian) ─────────────────────────────────────

    private static short[] bytesToShorts(final byte[] bytes, final int length) {
        final int n = length / 2;
        final short[] shorts = new short[n];
        for (int i = 0; i < n; i++) {
            final int lo = bytes[i * 2] & 0xFF;
            final int hi = bytes[i * 2 + 1];
            shorts[i] = (short) ((hi << 8) | lo);
        }
        return shorts;
    }

    private static byte[] shortsToBytes(final short[] shorts) {
        final byte[] bytes = new byte[shorts.length * 2];
        for (int i = 0; i < shorts.length; i++) {
            bytes[i * 2]     = (byte) (shorts[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((shorts[i] >> 8) & 0xFF);
        }
        return bytes;
    }
}
