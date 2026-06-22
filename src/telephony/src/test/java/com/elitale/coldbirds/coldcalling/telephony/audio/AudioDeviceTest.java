package com.elitale.coldbirds.coldcalling.telephony.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioDeviceTest {

    @Test
    void systemDefault_hasEmptyIdAndIsDefault() {
        final AudioDevice d = AudioDevice.systemDefault();
        assertThat(d.id()).isEqualTo(AudioDevice.SYSTEM_DEFAULT_ID);
        assertThat(d.id()).isEmpty();
        assertThat(d.name()).isEqualTo("System Default");
        assertThat(d.isDefault()).isTrue();
    }

    @Test
    void of_usesMixerNameForIdAndName() {
        final AudioDevice d = AudioDevice.of("MacBook Pro Microphone");
        assertThat(d.id()).isEqualTo("MacBook Pro Microphone");
        assertThat(d.name()).isEqualTo("MacBook Pro Microphone");
        assertThat(d.isDefault()).isFalse();
    }

    @Test
    void constructor_rejectsNullId() {
        assertThatThrownBy(() -> new AudioDevice(null, "x", false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsNullName() {
        assertThatThrownBy(() -> new AudioDevice("x", null, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsBlankName() {
        assertThatThrownBy(() -> new AudioDevice("x", "  ", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
