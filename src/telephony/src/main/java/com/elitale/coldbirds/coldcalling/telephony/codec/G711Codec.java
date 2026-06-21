package com.elitale.coldbirds.coldcalling.telephony.codec;

import java.util.Objects;

/**
 * G.711 PCMU (μ-law) encoder and decoder.
 *
 * <p>Standard: ITU-T G.711
 * <ul>
 *   <li>Sample rate: 8000 Hz</li>
 *   <li>Bit depth: 16-bit linear PCM input, 8-bit PCMU output</li>
 *   <li>Packet size: 160 samples = 20 ms</li>
 *   <li>Compression: 2:1 (16-bit → 8-bit via μ-law logarithmic quantisation)</li>
 * </ul>
 */
public final class G711Codec {

    /** Sample rate in Hz. */
    public static final int SAMPLE_RATE = 8000;

    /** Samples per 20 ms RTP packet. */
    public static final int SAMPLES_PER_PACKET = 160;

    /**
     * μ-law bias. Added to the magnitude before segment lookup to implement
     * the compression curve origin at 1 LSB (avoids a discontinuity at zero).
     */
    private static final int ULAW_BIAS = 0x84; // 132

    /** Maximum linear magnitude (positive range of a 16-bit sample). */
    private static final int ULAW_CLIP = 32767;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Encode 16-bit linear PCM samples to G.711 PCMU.
     *
     * @param pcm signed 16-bit samples; must not be null
     * @return PCMU bytes, same length as input
     */
    public byte[] encode(final short[] pcm) {
        Objects.requireNonNull(pcm, "pcm must not be null");
        final byte[] out = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = encodeSample(pcm[i]);
        }
        return out;
    }

    /**
     * Decode G.711 PCMU bytes to 16-bit linear PCM samples.
     *
     * @param pcmu μ-law encoded bytes; must not be null
     * @return signed 16-bit samples, same length as input
     */
    public short[] decode(final byte[] pcmu) {
        Objects.requireNonNull(pcmu, "pcmu must not be null");
        final short[] out = new short[pcmu.length];
        for (int i = 0; i < pcmu.length; i++) {
            out[i] = decodeSample(pcmu[i]);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // μ-law sample codec — ITU-T G.711 §3.2
    // ------------------------------------------------------------------

    /**
     * Encode one signed 16-bit PCM sample to one PCMU byte.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Extract sign; work with absolute magnitude.</li>
     *   <li>Add bias and clip to ULAW_CLIP.</li>
     *   <li>Find the 3-bit exponent: position of the highest set bit above bit 7.</li>
     *   <li>Extract 4 mantissa bits below the exponent.</li>
     *   <li>Pack sign (bit 7), exponent (bits 6-4), mantissa (bits 3-0).</li>
     *   <li>Invert all bits (μ-law convention).</li>
     * </ol>
     */
    private static byte encodeSample(final short pcm) {
        int sign;
        int sample;

        if (pcm < 0) {
            sample = -pcm;
            sign = 0;            // negative: sign bit = 0 in μ-law
        } else {
            sample = pcm;
            sign = 0x80;         // positive: sign bit = 1 in μ-law
        }

        sample += ULAW_BIAS;
        if (sample > ULAW_CLIP) {
            sample = ULAW_CLIP;
        }

        // Find exponent: highest bit above bit 7
        int exp = 7;
        for (; exp > 0; exp--) {
            if ((sample & (1 << (exp + 7))) != 0) {
                break;
            }
        }

        int mantissa   = (sample >> (exp + 3)) & 0x0F;
        int compressed = sign | (exp << 4) | mantissa;
        return (byte) (~compressed & 0xFF);
    }

    /**
     * Decode one PCMU byte to a signed 16-bit PCM sample.
     */
    private static short decodeSample(final byte pcmu) {
        int value    = ~pcmu & 0xFF;          // un-invert
        int sign     = value & 0x80;           // 1 = positive, 0 = negative
        int exp      = (value >> 4) & 0x07;
        int mantissa = value & 0x0F;

        // Reconstruct magnitude and remove bias
        int sample = ((mantissa << 3) | 0x84) << exp;
        sample -= ULAW_BIAS;

        return (short) (sign != 0 ? sample : -sample);
    }
}
