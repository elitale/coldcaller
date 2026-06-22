package com.elitale.coldbirds.coldcalling.telephony.rtp;

import com.elitale.coldbirds.coldcalling.telephony.codec.G711Codec;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A pre-recorded voicemail greeting decoded into 20 ms PCM frames ready to be
 * injected into the outbound RTP stream by {@link AudioPipeline}.
 *
 * <p>Strictly validates that the source WAV is 8 kHz, mono, 16-bit signed PCM —
 * the exact format the pipeline sends. Mismatched files are rejected rather than
 * resampled, so a greeting always plays at the correct pitch and speed.
 */
public final class VoicemailGreeting {

    /** Telephony sample rate (Hz). */
    private static final int SAMPLE_RATE = 8000;
    /** Samples in a 20 ms frame at 8 kHz. */
    private static final int FRAME_SAMPLES = G711Codec.SAMPLES_PER_PACKET; // 160
    /** Milliseconds of audio per frame. */
    private static final long FRAME_MILLIS = 20L;

    private final List<short[]> frames;

    private VoicemailGreeting(final List<short[]> frames) {
        this.frames = frames;
    }

    /**
     * Decode and validate a greeting WAV file.
     *
     * @param path the WAV file
     * @return the decoded greeting
     * @throws IOException                   if the file cannot be read
     * @throws UnsupportedAudioFileException if the file is not a recognised audio file
     * @throws IllegalArgumentException      if the format is not 8 kHz mono 16-bit PCM
     */
    public static VoicemailGreeting load(final Path path)
            throws IOException, UnsupportedAudioFileException {
        Objects.requireNonNull(path, "path must not be null");
        try (AudioInputStream in = AudioSystem.getAudioInputStream(path.toFile())) {
            final AudioFormat fmt = in.getFormat();
            requireTelephonyFormat(fmt);
            final byte[] bytes = in.readAllBytes();
            return new VoicemailGreeting(toFrames(bytes, fmt.isBigEndian()));
        }
    }

    private static void requireTelephonyFormat(final AudioFormat fmt) {
        if (fmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                || Math.round(fmt.getSampleRate()) != SAMPLE_RATE
                || fmt.getChannels() != 1
                || fmt.getSampleSizeInBits() != 16) {
            throw new IllegalArgumentException(
                    "Greeting must be 8 kHz mono 16-bit PCM WAV, was: " + fmt);
        }
    }

    /** Chunk little/big-endian 16-bit samples into 160-sample frames, zero-padding the tail. */
    private static List<short[]> toFrames(final byte[] bytes, final boolean bigEndian) {
        final int sampleCount = bytes.length / 2;
        final int frameCount = (sampleCount + FRAME_SAMPLES - 1) / FRAME_SAMPLES; // ceil
        final List<short[]> out = new ArrayList<>(frameCount);
        for (int f = 0; f < frameCount; f++) {
            final short[] frame = new short[FRAME_SAMPLES];
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                final int sample = f * FRAME_SAMPLES + i;
                if (sample >= sampleCount) break; // zero-pad tail
                final int b0 = bytes[sample * 2] & 0xFF;
                final int b1 = bytes[sample * 2 + 1] & 0xFF;
                frame[i] = bigEndian
                        ? (short) ((b0 << 8) | b1)
                        : (short) ((b1 << 8) | b0);
            }
            out.add(frame);
        }
        return List.copyOf(out);
    }

    /**
     * The greeting's 20 ms PCM frames (160 samples each; final frame zero-padded).
     *
     * @return an unmodifiable list of frames
     */
    public List<short[]> frames() {
        return frames;
    }

    /**
     * Playback duration (frame count × 20 ms).
     *
     * @return the greeting duration
     */
    public Duration duration() {
        return Duration.ofMillis(frames.size() * FRAME_MILLIS);
    }
}
