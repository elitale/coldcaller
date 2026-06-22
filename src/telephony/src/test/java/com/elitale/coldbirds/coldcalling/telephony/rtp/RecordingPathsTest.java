package com.elitale.coldbirds.coldcalling.telephony.rtp;

import com.elitale.coldbirds.coldcalling.domain.value.Country;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class RecordingPathsTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void resolvesYearMonthDayHourCountryFolderAndTimestampedFile() {
        final Path base = Path.of("/tmp/recordings");
        final Instant when = Instant.parse("2026-06-22T08:09:05Z");

        final Path path = RecordingPaths.resolve(base, when, UTC, "IN-India", "+91 7597365803");

        assertThat(path).isEqualTo(base
                .resolve("2026").resolve("06").resolve("22").resolve("08")
                .resolve("IN-India")
                .resolve("+917597365803_080905.wav"));
    }

    @Test
    void sanitizesRemoteNumberInFileName() {
        final Path path = RecordingPaths.resolve(
                Path.of("/r"), Instant.parse("2026-01-02T03:04:05Z"), UTC, "US-United_States", "(555) 010-2345");

        assertThat(path.getFileName().toString()).isEqualTo("5550102345_030405.wav");
    }

    @Test
    void blankRemoteAndCountryBecomeUnknown() {
        final Path path = RecordingPaths.resolve(
                Path.of("/r"), Instant.parse("2026-01-02T03:04:05Z"), UTC, "   ", "   ");

        assertThat(path.getParent().getFileName().toString()).isEqualTo("unknown");
        assertThat(path.getFileName().toString()).isEqualTo("unknown_030405.wav");
    }

    @Test
    void countryFolderFormatsIsoAndName() {
        final Country in = new Country("IN", "India", "+91", "+05:30");
        final Country us = new Country("US", "United States", "+1", "-05:00");

        assertThat(RecordingPaths.countryFolder(Optional.of(in))).isEqualTo("IN-India");
        assertThat(RecordingPaths.countryFolder(Optional.of(us))).isEqualTo("US-United_States");
        assertThat(RecordingPaths.countryFolder(Optional.empty())).isEqualTo("unknown");
    }

    @Test
    void defaultBaseDirIsUnderUserHome() {
        assertThat(RecordingPaths.defaultBaseDir())
                .isEqualTo(Path.of(System.getProperty("user.home"), ".coldcalling", "recordings"));
    }
}
