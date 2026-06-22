package com.elitale.coldbirds.coldcalling.telephony.rtp;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.LineUnavailableException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the device-open fallback that keeps a call audible when the configured
 * audio device is unavailable (e.g. a stale macOS Continuity "iPhone Microphone"
 * {@code Mixer.Info} whose {@code AudioSystem.getMixer} throws
 * {@link IllegalArgumentException}). The hardware-touching open path is exercised
 * through string sentinels so the test runs headless.
 */
class AudioPipelineTest {

    @Test
    void openWithFallback_returnsConfiguredLine_whenAvailable() throws LineUnavailableException {
        final List<String> warnings = new ArrayList<>();

        final String line = AudioPipeline.openWithFallback(
                () -> "configured",
                () -> "default",
                warnings::add);

        assertThat(line).isEqualTo("configured");
        assertThat(warnings).isEmpty();
    }

    @Test
    void openWithFallback_usesDefault_whenNoDeviceConfigured() throws LineUnavailableException {
        final List<String> warnings = new ArrayList<>();

        final String line = AudioPipeline.openWithFallback(
                null,                 // no configured device → normal default path
                () -> "default",
                warnings::add);

        assertThat(line).isEqualTo("default");
        assertThat(warnings).isEmpty(); // using the default is not a "fallback"
    }

    @Test
    void openWithFallback_fallsBackToDefault_whenMixerInfoIsStale() throws LineUnavailableException {
        // macOS Continuity "iPhone Microphone": AudioSystem.getMixer → IllegalArgumentException.
        final List<String> warnings = new ArrayList<>();

        final String line = AudioPipeline.openWithFallback(
                () -> { throw new IllegalArgumentException("Mixer not supported: iPhone Microphone"); },
                () -> "default",
                warnings::add);

        assertThat(line).isEqualTo("default");
        assertThat(warnings).singleElement().asString().contains("iPhone Microphone");
    }

    @Test
    void openWithFallback_fallsBackToDefault_whenDeviceLineUnavailable() throws LineUnavailableException {
        final List<String> warnings = new ArrayList<>();

        final String line = AudioPipeline.openWithFallback(
                () -> { throw new LineUnavailableException("device busy"); },
                () -> "default",
                warnings::add);

        assertThat(line).isEqualTo("default");
        assertThat(warnings).hasSize(1);
    }

    @Test
    void openWithFallback_propagates_whenDefaultAlsoFails() {
        assertThatThrownBy(() -> AudioPipeline.openWithFallback(
                () -> { throw new IllegalArgumentException("stale"); },
                () -> { throw new LineUnavailableException("no microphone present"); },
                msg -> { }))
                .isInstanceOf(LineUnavailableException.class)
                .hasMessageContaining("no microphone present");
    }

    @Test
    void openWithFallback_doesNotCatchUnexpectedRuntimeExceptions() {
        // Only stale-mixer / line-unavailable failures fall back; bugs must surface.
        assertThatThrownBy(() -> AudioPipeline.openWithFallback(
                () -> { throw new IllegalStateException("bug"); },
                () -> "default",
                msg -> { }))
                .isInstanceOf(IllegalStateException.class);
    }
}
