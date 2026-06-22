package com.elitale.coldbirds.coldcalling.telephony.audio;

/**
 * Pure audio-level math shared by the live call pipeline and the device tester.
 *
 * <p>Stateless and hardware-free, so it is unit-tested directly and is safe to
 * call from any thread (mic capture, RTP receive, or the FX meter poll).
 */
public final class AudioLevels {

    /** 16-bit signed full-scale magnitude used to normalize sample amplitudes. */
    private static final double FULL_SCALE = 32768.0;

    private AudioLevels() {
    }

    /**
     * Root-mean-square level of a PCM frame, normalized to [0,1].
     *
     * @param frame signed 16-bit samples; may be null or empty
     * @return normalized RMS — 0 for silence/empty, ≈1 for full-scale
     */
    public static double rms(final short[] frame) {
        if (frame == null || frame.length == 0) {
            return 0.0;
        }
        double sumSquares = 0.0;
        for (final short sample : frame) {
            final double normalized = sample / FULL_SCALE;
            sumSquares += normalized * normalized;
        }
        return Math.min(1.0, Math.sqrt(sumSquares / frame.length));
    }
}
