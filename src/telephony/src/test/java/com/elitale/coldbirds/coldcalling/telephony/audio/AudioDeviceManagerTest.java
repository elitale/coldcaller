package com.elitale.coldbirds.coldcalling.telephony.audio;

import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceManager.MixerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudioDeviceManagerTest {

    @Test
    void filterInputs_keepsCaptureCapable_andPrependsSystemDefault() {
        final List<AudioDevice> result = AudioDeviceManager.filterInputs(List.of(
                new MixerEntry("MacBook Pro Microphone", true, false),
                new MixerEntry("MacBook Pro Speakers", false, true)));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).isDefault()).isTrue();
        assertThat(result.get(0).name()).isEqualTo("System Default");
        assertThat(result.get(1).name()).isEqualTo("MacBook Pro Microphone");
    }

    @Test
    void filterOutputs_keepsPlaybackCapable_only() {
        final List<AudioDevice> result = AudioDeviceManager.filterOutputs(List.of(
                new MixerEntry("MacBook Pro Microphone", true, false),
                new MixerEntry("MacBook Pro Speakers", false, true)));

        assertThat(result).extracting(AudioDevice::name)
                .containsExactly("System Default", "MacBook Pro Speakers");
    }

    @Test
    void filterInputs_excludesPortAndNonCapableMixers() {
        // Port mixers / playback-only devices support neither capture line.
        final List<AudioDevice> result = AudioDeviceManager.filterInputs(List.of(
                new MixerEntry("Port C24FG7x", false, false),
                new MixerEntry("Port MacBook Pro Microphone", false, false),
                new MixerEntry("Default Audio Device", false, false)));

        assertThat(result).extracting(AudioDevice::name).containsExactly("System Default");
    }

    @Test
    void filterInputs_dedupesByName() {
        final List<AudioDevice> result = AudioDeviceManager.filterInputs(List.of(
                new MixerEntry("USB Mic", true, false),
                new MixerEntry("USB Mic", true, false)));

        assertThat(result).extracting(AudioDevice::name)
                .containsExactly("System Default", "USB Mic");
    }

    @Test
    void filterInputs_skipsBlankNames() {
        final List<AudioDevice> result = AudioDeviceManager.filterInputs(List.of(
                new MixerEntry("  ", true, false),
                new MixerEntry(null, true, false)));

        assertThat(result).extracting(AudioDevice::name).containsExactly("System Default");
    }

    @Test
    void resolveInput_returnsEmpty_forBlankOrNull() {
        final AudioDeviceManager manager = new AudioDeviceManager();
        assertThat(manager.resolveInput("")).isEmpty();
        assertThat(manager.resolveInput(null)).isEmpty();
        assertThat(manager.resolveInput("   ")).isEmpty();
    }

    @Test
    void resolveOutput_returnsEmpty_forUnknownDevice() {
        final AudioDeviceManager manager = new AudioDeviceManager();
        assertThat(manager.resolveOutput("no-such-device-xyz")).isEmpty();
    }

    @Test
    void exists_returnsFalse_forBlankOrUnknown() {
        final AudioDeviceManager manager = new AudioDeviceManager();
        assertThat(manager.exists("", AudioDeviceManager.Direction.INPUT)).isFalse();
        assertThat(manager.exists("no-such-device-xyz", AudioDeviceManager.Direction.OUTPUT))
                .isFalse();
    }
}
