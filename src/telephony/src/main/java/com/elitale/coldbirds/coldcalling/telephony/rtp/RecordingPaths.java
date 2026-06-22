package com.elitale.coldbirds.coldcalling.telephony.rtp;

import com.elitale.coldbirds.coldcalling.domain.value.Country;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves on-disk locations for call recordings, organised hierarchically:
 * {@code <base>/yyyy/MM/dd/HH/<ISO-Country_Name>/<number>_<HHmmss>.wav}.
 *
 * <p>The {@code <HHmmss>} suffix on the file name keeps multiple calls to the
 * same number within the same hour from overwriting one another. The default
 * base directory is {@code ~/.coldcalling/recordings}.
 */
public final class RecordingPaths {

    private static final DateTimeFormatter YEAR  = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM");
    private static final DateTimeFormatter DAY   = DateTimeFormatter.ofPattern("dd");
    private static final DateTimeFormatter HOUR  = DateTimeFormatter.ofPattern("HH");
    private static final DateTimeFormatter TIME  = DateTimeFormatter.ofPattern("HHmmss");

    private static final String UNKNOWN = "unknown";

    private RecordingPaths() {}

    /** {@code ~/.coldcalling/recordings} — the default recordings root. */
    public static Path defaultBaseDir() {
        return Path.of(System.getProperty("user.home"), ".coldcalling", "recordings");
    }

    /**
     * Resolve a recording file path for a call.
     *
     * @param baseDir       recordings root directory
     * @param when          call start time
     * @param zone          time-zone for the date/time folders
     * @param countryFolder country segment (see {@link #countryFolder(Optional)}); blank → {@code unknown}
     * @param remoteNumber  remote party number (used in the file name)
     * @return {@code <base>/yyyy/MM/dd/HH/<countryFolder>/<sanitisedRemote>_<HHmmss>.wav}
     */
    public static Path resolve(
            final Path baseDir,
            final Instant when,
            final ZoneId zone,
            final String countryFolder,
            final String remoteNumber) {

        Objects.requireNonNull(baseDir, "baseDir must not be null");
        Objects.requireNonNull(when,    "when must not be null");
        Objects.requireNonNull(zone,    "zone must not be null");

        final var local = when.atZone(zone);
        final String fileName = sanitizeNumber(remoteNumber) + "_" + TIME.format(local) + ".wav";
        return baseDir
                .resolve(YEAR.format(local))
                .resolve(MONTH.format(local))
                .resolve(DAY.format(local))
                .resolve(HOUR.format(local))
                .resolve(sanitizeFolder(countryFolder))
                .resolve(fileName);
    }

    /**
     * Build the country folder segment {@code <ISO>-<Display_Name>} (e.g.
     * {@code IN-India}), or {@code unknown} when the country is absent.
     */
    public static String countryFolder(final Optional<Country> country) {
        Objects.requireNonNull(country, "country must not be null");
        return country
                .map(c -> c.isoCode() + "-" + c.displayName().strip().replaceAll("[^A-Za-z0-9]+", "_"))
                .map(RecordingPaths::sanitizeFolder)
                .orElse(UNKNOWN);
    }

    /** Keep digits and a leading {@code +}; collapse everything else. */
    private static String sanitizeNumber(final String remoteNumber) {
        if (remoteNumber == null || remoteNumber.isBlank()) {
            return UNKNOWN;
        }
        final String cleaned = remoteNumber.strip().replaceAll("[^0-9+]", "");
        return cleaned.isBlank() ? UNKNOWN : cleaned;
    }

    /** Keep folder names filesystem-safe. */
    private static String sanitizeFolder(final String folder) {
        if (folder == null || folder.isBlank()) {
            return UNKNOWN;
        }
        final String cleaned = folder.strip().replaceAll("[^A-Za-z0-9_+-]", "_");
        return cleaned.isBlank() ? UNKNOWN : cleaned;
    }
}
