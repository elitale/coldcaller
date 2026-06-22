package com.elitale.coldbirds.coldcalling.telephony.audio;

import java.util.Objects;

/**
 * A selectable audio device (microphone or speaker) presented to the user.
 *
 * <p>The {@code id} is the underlying mixer name and is the stable token persisted
 * in settings. An empty {@code id} denotes the platform's system-default device.
 *
 * @param id        mixer name, or {@code ""} for the system default
 * @param name      human-friendly label shown in the UI
 * @param isDefault {@code true} only for the synthetic "System Default" entry
 */
public record AudioDevice(String id, String name, boolean isDefault) {

    /** Stable id token representing "use the operating-system default device". */
    public static final String SYSTEM_DEFAULT_ID = "";

    public AudioDevice {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** The synthetic entry that maps to the system-default device. */
    public static AudioDevice systemDefault() {
        return new AudioDevice(SYSTEM_DEFAULT_ID, "System Default", true);
    }

    /** A concrete hardware device identified by its mixer name. */
    public static AudioDevice of(final String mixerName) {
        return new AudioDevice(mixerName, mixerName, false);
    }
}
