package com.elitale.coldbirds.coldcalling.ui.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Synthesises short call-feedback tones (ringback, connect/hangup blips, DTMF)
 * on the fly with {@code javax.sound.sampled} — no bundled audio assets.
 * <p>
 * Every method is non-blocking and fail-safe: if no output line is available
 * (e.g. headless CI or a busy device) playback is silently skipped. Ringback
 * loops on its own daemon thread until {@link #stopRingback()} is called; all
 * one-shot tones run on a single daemon executor so callers never block.
 */
public final class CallTones {

    private static final Logger LOG = LoggerFactory.getLogger(CallTones.class);

    private static final float SAMPLE_RATE = 44_100f;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    private final ExecutorService player = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "call-tones");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean ringing = false;
    private Thread ringThread;

    /** Start the looping ringback cadence (440+480 Hz, 2 s on / 3 s off). Idempotent. */
    public synchronized void startRingback() {
        if (ringing) return;
        ringing = true;
        ringThread = new Thread(this::ringbackLoop, "call-ringback");
        ringThread.setDaemon(true);
        ringThread.start();
    }

    /** Stop the ringback loop if running. Safe to call repeatedly. */
    public synchronized void stopRingback() {
        ringing = false;
        if (ringThread != null) {
            ringThread.interrupt();
            ringThread = null;
        }
    }

    /** A short rising two-note chime signalling the call connected. */
    public void connect() {
        playOnce(concat(tone(587.33, 90, 0.22), tone(880.0, 120, 0.22)));
    }

    /** A short falling two-note chime signalling the call ended. */
    public void hangup() {
        playOnce(concat(tone(480.0, 110, 0.20), tone(360.0, 150, 0.20)));
    }

    /** Play the standard DTMF dual-tone for a dial-pad key. Unknown keys are ignored. */
    public void dtmf(String key) {
        final double[] pair = dtmfFreqs(key);
        if (pair != null) {
            playOnce(dualTone(pair[0], pair[1], 120, 0.25));
        }
    }

    /** Release the audio executor. Call on shutdown. */
    public void close() {
        stopRingback();
        player.shutdownNow();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void ringbackLoop() {
        try (SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT)) {
            line.open(FORMAT);
            line.start();
            final byte[] ring = dualTone(440.0, 480.0, 2000, 0.18);
            final byte[] silence = new byte[bytesForMs(3000)];
            while (ringing && !Thread.currentThread().isInterrupted()) {
                if (!writeResponsive(line, ring)) break;
                if (!writeResponsive(line, silence)) break;
            }
            line.stop();
            line.flush();
        } catch (Exception e) {
            LOG.debug("Ringback unavailable: {}", e.getMessage());
        }
    }

    private void playOnce(byte[] pcm) {
        player.submit(() -> {
            try (SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT)) {
                line.open(FORMAT);
                line.start();
                line.write(pcm, 0, pcm.length);
                line.drain();
                line.stop();
            } catch (Exception e) {
                LOG.debug("Tone unavailable: {}", e.getMessage());
            }
        });
    }

    /** Write in small chunks so {@link #stopRingback()} interrupts promptly. */
    private boolean writeResponsive(SourceDataLine line, byte[] data) {
        final int chunk = bytesForMs(100);
        for (int offset = 0; offset < data.length; offset += chunk) {
            if (!ringing || Thread.currentThread().isInterrupted()) return false;
            line.write(data, offset, Math.min(chunk, data.length - offset));
        }
        return true;
    }

    private static int bytesForMs(int ms) {
        return (int) (SAMPLE_RATE * ms / 1000.0) * 2; // 16-bit mono = 2 bytes/sample
    }

    private static byte[] tone(double freq, int ms, double gain) {
        return dualTone(freq, freq, ms, gain);
    }

    private static byte[] dualTone(double f1, double f2, int ms, double gain) {
        final int samples = (int) (SAMPLE_RATE * ms / 1000.0);
        final byte[] out = new byte[samples * 2];
        final int ramp = (int) (SAMPLE_RATE * 0.006); // 6 ms attack/release
        for (int i = 0; i < samples; i++) {
            final double t = i / SAMPLE_RATE;
            double sample = 0.5 * Math.sin(2 * Math.PI * f1 * t)
                          + 0.5 * Math.sin(2 * Math.PI * f2 * t);
            double envelope = 1.0;
            if (i < ramp) envelope = i / (double) ramp;
            else if (i > samples - ramp) envelope = (samples - i) / (double) ramp;
            final short value = (short) (sample * gain * envelope * Short.MAX_VALUE);
            out[i * 2] = (byte) (value & 0xFF);
            out[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static double[] dtmfFreqs(String key) {
        return switch (key) {
            case "1" -> new double[]{697, 1209};
            case "2" -> new double[]{697, 1336};
            case "3" -> new double[]{697, 1477};
            case "4" -> new double[]{770, 1209};
            case "5" -> new double[]{770, 1336};
            case "6" -> new double[]{770, 1477};
            case "7" -> new double[]{852, 1209};
            case "8" -> new double[]{852, 1336};
            case "9" -> new double[]{852, 1477};
            case "*" -> new double[]{941, 1209};
            case "0" -> new double[]{941, 1336};
            case "#" -> new double[]{941, 1477};
            default  -> null;
        };
    }
}
