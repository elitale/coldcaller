package com.elitale.coldbirds.coldcalling.telephony.audio;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class AudioLevelsTest {

    @Test
    void rms_ofSilence_isZero() {
        assertThat(AudioLevels.rms(new short[160])).isEqualTo(0.0);
    }

    @Test
    void rms_ofNullOrEmpty_isZero() {
        assertThat(AudioLevels.rms(null)).isEqualTo(0.0);
        assertThat(AudioLevels.rms(new short[0])).isEqualTo(0.0);
    }

    @Test
    void rms_ofFullScalePositive_isApproximatelyOne() {
        final short[] frame = new short[160];
        Arrays.fill(frame, Short.MAX_VALUE);
        assertThat(AudioLevels.rms(frame)).isCloseTo(1.0, offset(0.001));
    }

    @Test
    void rms_ofFullScaleNegative_isClampedToOne() {
        final short[] frame = new short[160];
        Arrays.fill(frame, Short.MIN_VALUE); // -32768 normalizes to exactly -1.0
        assertThat(AudioLevels.rms(frame)).isEqualTo(1.0);
    }

    @Test
    void rms_isWithinUnitRange_forArbitrarySignal() {
        final short[] tone = AudioDeviceTester.generateTone(440, 100);
        assertThat(AudioLevels.rms(tone)).isBetween(0.0, 1.0);
    }

    @Test
    void rms_growsWithAmplitude() {
        final short[] quiet = new short[160];
        final short[] loud = new short[160];
        Arrays.fill(quiet, (short) 1000);
        Arrays.fill(loud, (short) 16000);
        assertThat(AudioLevels.rms(loud)).isGreaterThan(AudioLevels.rms(quiet));
    }
}
