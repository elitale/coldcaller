package com.elitale.coldbirds.coldcalling.telephony.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioDeviceTesterTest {

    @Test
    void generateTone_hasExpectedSampleCount() {
        // 8 kHz * 700 ms = 5600 samples
        assertThat(AudioDeviceTester.generateTone(440, 700)).hasSize(5600);
    }

    @Test
    void generateTone_zeroDuration_isEmpty() {
        assertThat(AudioDeviceTester.generateTone(440, 0)).isEmpty();
    }

    @Test
    void generateTone_staysWithinAmplitudeBounds() {
        final short[] tone = AudioDeviceTester.generateTone(440, 200);
        short max = 0;
        for (final short s : tone) {
            max = (short) Math.max(max, Math.abs(s));
        }
        assertThat(max).isGreaterThan((short) 0);
        // Gain 0.5 of full-scale → peak around 16383, comfortably under the 16-bit ceiling.
        assertThat(max).isLessThanOrEqualTo((short) 16384);
    }

    @Test
    void generateTone_fadesInFromZero() {
        final short[] tone = AudioDeviceTester.generateTone(440, 200);
        assertThat(tone[0]).isEqualTo((short) 0);
    }

    @Test
    void rms_ofSilence_isZero() {
        assertThat(AudioDeviceTester.rms(new short[160])).isEqualTo(0.0);
    }

    @Test
    void rms_ofNullOrEmpty_isZero() {
        assertThat(AudioDeviceTester.rms(null)).isEqualTo(0.0);
        assertThat(AudioDeviceTester.rms(new short[0])).isEqualTo(0.0);
    }

    @Test
    void rms_ofFullScale_isApproximatelyOne() {
        final short[] frame = new short[160];
        java.util.Arrays.fill(frame, Short.MAX_VALUE);
        assertThat(AudioDeviceTester.rms(frame)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void rms_isWithinUnitRange_forArbitrarySignal() {
        final short[] tone = AudioDeviceTester.generateTone(440, 100);
        final double level = AudioDeviceTester.rms(tone);
        assertThat(level).isBetween(0.0, 1.0);
    }
}
