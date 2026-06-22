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
}
