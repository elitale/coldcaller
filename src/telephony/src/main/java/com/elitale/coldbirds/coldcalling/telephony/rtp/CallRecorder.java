package com.elitale.coldbirds.coldcalling.telephony.rtp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records a live call to a mono WAV file by mixing both directions.
 *
 * <p>The recording timeline is anchored to the microphone capture cadence:
 * {@link #onMicFrame} is called once per 20 ms frame and writes a mixed sample
 * block (local mic + the most recent remote frame). Remote frames arrive on the
 * RTP receive thread via {@link #onRemoteFrame} and are buffered until the next
 * mic frame consumes one. Missing remote frames are written as the mic-only
 * signal (and vice-versa for silent mic frames).
 *
 * <p>{@link #onMicFrame} must be called from a single thread (the capture loop).
 */
public final class CallRecorder implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CallRecorder.class);

    private static final int SAMPLE_RATE = 8000;
    private static final int MAX_QUEUED_REMOTE = 50; // ~1s backlog cap

    private final Path file;
    private final WavFileWriter writer;
    private final Queue<short[]> remoteFrames = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean open = new AtomicBoolean(true);

    /**
     * Open a recorder writing to {@code file}. Parent directories are created.
     *
     * @param file destination WAV path
     * @throws IOException if the file cannot be opened
     */
    public CallRecorder(final Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file must not be null");
        final Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = new WavFileWriter(file, SAMPLE_RATE);
        LOG.info("Recording call to {}", file);
    }

    /** Destination file for this recording. */
    public Path file() {
        return file;
    }

    /**
     * Buffer a decoded remote (far-end) PCM frame. Safe to call from the RTP
     * receive thread. Drops the oldest frame if the backlog cap is exceeded.
     *
     * @param pcm decoded 16-bit PCM samples
     */
    public void onRemoteFrame(final short[] pcm) {
        if (!open.get() || pcm == null) {
            return;
        }
        if (remoteFrames.size() >= MAX_QUEUED_REMOTE) {
            remoteFrames.poll();
        }
        remoteFrames.offer(pcm.clone());
    }

    /**
     * Write one mixed frame: local mic samples summed with the next buffered
     * remote frame. Call once per captured mic frame from the capture thread.
     *
     * @param micPcm 16-bit PCM samples from the microphone
     */
    public void onMicFrame(final short[] micPcm) {
        if (!open.get() || micPcm == null) {
            return;
        }
        final short[] remote = remoteFrames.poll();
        final short[] mixed = mix(micPcm, remote);
        try {
            writer.write(mixed);
        } catch (final IOException e) {
            LOG.warn("Failed to write recording frame: {}", e.getMessage());
        }
    }

    /** Finalise the WAV file. Idempotent. */
    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            try {
                writer.close();
                LOG.info("Recording finalised: {}", file);
            } catch (final IOException e) {
                LOG.warn("Failed to finalise recording {}: {}", file, e.getMessage());
            }
        }
    }

    /** Sum two PCM frames with clamping; {@code far} may be null (mic-only). */
    private static short[] mix(final short[] near, final short[] far) {
        if (far == null) {
            return near;
        }
        final int len = Math.max(near.length, far.length);
        final short[] out = new short[len];
        for (int i = 0; i < len; i++) {
            final int a = (i < near.length) ? near[i] : 0;
            final int b = (i < far.length)  ? far[i]  : 0;
            int sum = a + b;
            if (sum > Short.MAX_VALUE) {
                sum = Short.MAX_VALUE;
            } else if (sum < Short.MIN_VALUE) {
                sum = Short.MIN_VALUE;
            }
            out[i] = (short) sum;
        }
        return out;
    }
}
