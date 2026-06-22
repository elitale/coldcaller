package com.elitale.coldbirds.coldcalling.telephony.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Enumerates usable audio capture (microphone) and playback (speaker) devices and
 * resolves a persisted device id back to a {@link Mixer.Info}.
 *
 * <p>Only mixers that can actually open the call {@link AudioFormat} are returned, which
 * naturally excludes control-only {@code Port} mixers and devices of the wrong direction.
 * Each list is deduplicated by name and led by a synthetic
 * {@link AudioDevice#systemDefault() System Default} entry.
 *
 * <p>All enumeration runs on the calling thread; methods are side-effect free and may be
 * invoked off the FX Application Thread.
 */
public final class AudioDeviceManager {

    /** Capture/playback format used by the live call pipeline (G.711 PCM). */
    static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, 8000f, 16, 1, 2, 8000f, false);

    /** Capture vs playback direction. */
    public enum Direction { INPUT, OUTPUT }

    /** A mixer reduced to the facts the pure filter needs. Package-private for testing. */
    record MixerEntry(String name, boolean supportsCapture, boolean supportsPlayback) {}

    // ── Public API ──────────────────────────────────────────────────────────────

    /** Microphones: System Default first, then deduped capture-capable devices. */
    public List<AudioDevice> inputDevices() {
        return filterInputs(scanMixers());
    }

    /** Speakers: System Default first, then deduped playback-capable devices. */
    public List<AudioDevice> outputDevices() {
        return filterOutputs(scanMixers());
    }

    /**
     * Resolve a persisted input device id to a {@link Mixer.Info}.
     *
     * @param id mixer name, or {@code ""}/null for system default
     * @return the matching mixer, or empty for system default / unknown id
     */
    public Optional<Mixer.Info> resolveInput(final String id) {
        return resolve(id, Direction.INPUT);
    }

    /**
     * Resolve a persisted output device id to a {@link Mixer.Info}.
     *
     * @param id mixer name, or {@code ""}/null for system default
     * @return the matching mixer, or empty for system default / unknown id
     */
    public Optional<Mixer.Info> resolveOutput(final String id) {
        return resolve(id, Direction.OUTPUT);
    }

    /** True if a concrete (non-default) device with this id is currently present. */
    public boolean exists(final String id, final Direction direction) {
        if (id == null || id.isBlank()) {
            return false;
        }
        final List<AudioDevice> devices =
                direction == Direction.INPUT ? inputDevices() : outputDevices();
        return devices.stream().anyMatch(d -> !d.isDefault() && d.id().equals(id));
    }

    // ── Pure filters (no hardware) ────────────────────────────────────────────────

    static List<AudioDevice> filterInputs(final List<MixerEntry> entries) {
        return build(entries, MixerEntry::supportsCapture);
    }

    static List<AudioDevice> filterOutputs(final List<MixerEntry> entries) {
        return build(entries, MixerEntry::supportsPlayback);
    }

    private static List<AudioDevice> build(
            final List<MixerEntry> entries,
            final java.util.function.Predicate<MixerEntry> capable) {

        final List<AudioDevice> result = new ArrayList<>();
        result.add(AudioDevice.systemDefault());

        final Set<String> seen = new LinkedHashSet<>();
        for (final MixerEntry entry : entries) {
            final String name = entry.name() == null ? "" : entry.name().strip();
            if (name.isEmpty() || !capable.test(entry)) {
                continue;
            }
            if (seen.add(name)) {
                result.add(AudioDevice.of(name));
            }
        }
        return List.copyOf(result);
    }

    // ── Hardware adapters ─────────────────────────────────────────────────────────

    private List<MixerEntry> scanMixers() {
        final DataLine.Info captureInfo  = new DataLine.Info(TargetDataLine.class, FORMAT);
        final DataLine.Info playbackInfo = new DataLine.Info(SourceDataLine.class, FORMAT);

        final List<MixerEntry> entries = new ArrayList<>();
        for (final Mixer.Info info : AudioSystem.getMixerInfo()) {
            final Mixer mixer = AudioSystem.getMixer(info);
            entries.add(new MixerEntry(
                    info.getName(),
                    mixer.isLineSupported(captureInfo),
                    mixer.isLineSupported(playbackInfo)));
        }
        return entries;
    }

    private Optional<Mixer.Info> resolve(final String id, final Direction direction) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        final DataLine.Info lineInfo = direction == Direction.INPUT
                ? new DataLine.Info(TargetDataLine.class, FORMAT)
                : new DataLine.Info(SourceDataLine.class, FORMAT);

        for (final Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (!Objects.equals(info.getName(), id)) {
                continue;
            }
            if (AudioSystem.getMixer(info).isLineSupported(lineInfo)) {
                return Optional.of(info);
            }
        }
        return Optional.empty();
    }
}
