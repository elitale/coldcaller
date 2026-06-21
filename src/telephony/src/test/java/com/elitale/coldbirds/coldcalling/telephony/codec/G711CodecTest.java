package com.elitale.coldbirds.coldcalling.telephony.codec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class G711CodecTest {

    private final G711Codec codec = new G711Codec();

    // ------------------------------------------------------------------
    // Encode
    // ------------------------------------------------------------------

    @Test
    void testEncodeSilence() {
        // Silence (0): positive, bias=132, exp=0, mantissa=0
        // compressed = 0x80, inverted = 0x7F (127) — G.711 spec
        byte[] encoded = codec.encode(new short[]{0});
        assertThat(encoded).hasSize(1);
        assertThat(encoded[0] & 0xFF).isEqualTo(0x7F);
    }

    @Test
    void testEncodeMaxPositive() {
        byte[] encoded = codec.encode(new short[]{Short.MAX_VALUE});
        assertThat(encoded).hasSize(1);
        // max positive saturates: result should not throw
        assertThat(encoded[0]).isNotNull();
    }

    @Test
    void testEncodeMaxNegative() {
        byte[] encoded = codec.encode(new short[]{Short.MIN_VALUE});
        assertThat(encoded).hasSize(1);
        assertThat(encoded[0]).isNotNull();
    }

    // ------------------------------------------------------------------
    // Decode
    // ------------------------------------------------------------------

    @Test
    void testDecodeZeroByte() {
        // PCMU 0x00: inverted=0xFF, sign=positive, exp=7, mantissa=15 → large positive
        short[] decoded = codec.decode(new byte[]{0x00});
        assertThat(decoded).hasSize(1);
        assertThat(decoded[0]).isPositive();
        assertThat(decoded[0]).isGreaterThan((short) 30000); // near max amplitude
    }

    // ------------------------------------------------------------------
    // Roundtrip
    // ------------------------------------------------------------------

    @Test
    void testRoundtripPositive() {
        short[] original = {1000, 2000, 4000, 8000, 16000};
        byte[] encoded = codec.encode(original);
        short[] decoded = codec.decode(encoded);

        assertThat(decoded).hasSameSizeAs(original);
        // μ-law is lossy but error should be small relative to amplitude
        for (int i = 0; i < original.length; i++) {
            assertThat(Math.abs(decoded[i] - original[i]))
                    .as("roundtrip error at index %d", i)
                    .isLessThan(Math.abs(original[i]) / 4 + 128);
        }
    }

    @Test
    void testRoundtripNegative() {
        short[] original = {-1000, -2000, -8000};
        byte[] encoded = codec.encode(original);
        short[] decoded = codec.decode(encoded);

        assertThat(decoded).hasSameSizeAs(original);
        for (int i = 0; i < original.length; i++) {
            assertThat(Math.abs(decoded[i] - original[i]))
                    .as("roundtrip error at index %d", i)
                    .isLessThan(Math.abs(original[i]) / 4 + 128);
        }
    }

    @Test
    void testRoundtripSilence() {
        short[] original = {0, 0, 0};
        short[] decoded = codec.decode(codec.encode(original));
        // silence must roundtrip exactly to 0 or near-0
        for (short s : decoded) {
            assertThat(Math.abs(s)).isLessThanOrEqualTo(8);
        }
    }

    // ------------------------------------------------------------------
    // Packet size constants
    // ------------------------------------------------------------------

    @Test
    void testPacketConstants() {
        assertThat(G711Codec.SAMPLE_RATE).isEqualTo(8000);
        assertThat(G711Codec.SAMPLES_PER_PACKET).isEqualTo(160); // 20 ms
    }

    @Test
    void testEncodeDecodePacketSize() {
        short[] packet = new short[G711Codec.SAMPLES_PER_PACKET];
        byte[] encoded = codec.encode(packet);
        assertThat(encoded).hasSize(G711Codec.SAMPLES_PER_PACKET);
        assertThat(codec.decode(encoded)).hasSize(G711Codec.SAMPLES_PER_PACKET);
    }

    // ------------------------------------------------------------------
    // Null / empty guard
    // ------------------------------------------------------------------

    @Test
    void testEncodeEmptyArray() {
        assertThat(codec.encode(new short[0])).isEmpty();
    }

    @Test
    void testDecodeEmptyArray() {
        assertThat(codec.decode(new byte[0])).isEmpty();
    }

    @Test
    void testEncodeNullThrows() {
        assertThatNullPointerException().isThrownBy(() -> codec.encode(null));
    }

    @Test
    void testDecodeNullThrows() {
        assertThatNullPointerException().isThrownBy(() -> codec.decode(null));
    }
}
