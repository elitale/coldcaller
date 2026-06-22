package com.elitale.coldbirds.coldcalling.telephony.rtp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import static org.assertj.core.api.Assertions.assertThat;

final class WavFileWriterTest {

    @Test
    void writesPlayableMono16BitWavWithCorrectFrameCount(@TempDir Path dir) throws Exception {
        final Path wav = dir.resolve("out.wav");

        try (WavFileWriter writer = new WavFileWriter(wav, 8000)) {
            writer.write(new short[160]);            // 160 samples
            writer.write(new short[]{1, -1, 100});   // 3 samples
        }

        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            final AudioFormat fmt = in.getFormat();
            assertThat(fmt.getSampleRate()).isEqualTo(8000f);
            assertThat(fmt.getChannels()).isEqualTo(1);
            assertThat(fmt.getSampleSizeInBits()).isEqualTo(16);
            assertThat(in.getFrameLength()).isEqualTo(163);
        }
    }

    @Test
    void emptyRecordingProducesValidHeaderWithZeroFrames(@TempDir Path dir) throws IOException {
        final Path wav = dir.resolve("empty.wav");

        new WavFileWriter(wav, 8000).close();

        assertThat(wav).exists();
        assertThat(wav.toFile().length()).isEqualTo(44);
    }
}
