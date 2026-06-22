package com.elitale.coldbirds.coldcalling.telephony.rtp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoicemailGreetingTest {

    @TempDir
    Path tmp;

    @Test
    void load_decodesValidEightKMonoSixteenBit_intoTwentyMsFrames() throws Exception {
        final Path wav = writeWav("ok.wav", format(8000f, 16, 1), 320); // 2 frames

        final VoicemailGreeting greeting = VoicemailGreeting.load(wav);

        assertThat(greeting.frames()).hasSize(2);
        assertThat(greeting.frames().get(0)).hasSize(160);
        assertThat(greeting.duration()).isEqualTo(java.time.Duration.ofMillis(40));
    }

    @Test
    void load_zeroPadsTrailingPartialFrame() throws Exception {
        final Path wav = writeWav("pad.wav", format(8000f, 16, 1), 161); // 1 full + 1 sample

        final VoicemailGreeting greeting = VoicemailGreeting.load(wav);

        assertThat(greeting.frames()).hasSize(2);
        // Tail samples beyond index 0 of the second frame are zero-padded.
        assertThat(greeting.frames().get(1)[159]).isZero();
    }

    @Test
    void load_rejectsWrongSampleRate() throws Exception {
        final Path wav = writeWav("44k.wav", format(44100f, 16, 1), 320);

        assertThatThrownBy(() -> VoicemailGreeting.load(wav))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 kHz");
    }

    @Test
    void load_rejectsStereo() throws Exception {
        final Path wav = writeWav("stereo.wav", format(8000f, 16, 2), 320);

        assertThatThrownBy(() -> VoicemailGreeting.load(wav))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void load_rejectsEightBit() throws Exception {
        final Path wav = writeWav("8bit.wav", format(8000f, 8, 1), 320);

        assertThatThrownBy(() -> VoicemailGreeting.load(wav))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void frames_areUnmodifiable() throws Exception {
        final Path wav = writeWav("ok.wav", format(8000f, 16, 1), 320);

        final VoicemailGreeting greeting = VoicemailGreeting.load(wav);

        assertThatThrownBy(() -> greeting.frames().add(new short[160]))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static AudioFormat format(final float sampleRate, final int bits, final int channels) {
        return new AudioFormat(sampleRate, bits, channels, true, false); // signed, little-endian
    }

    private Path writeWav(final String name, final AudioFormat fmt, final int sampleCount)
            throws IOException {
        final byte[] pcm = new byte[sampleCount * fmt.getFrameSize()];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) ((i * 7) % 127); // arbitrary non-zero content
        }
        final Path file = tmp.resolve(name);
        try (AudioInputStream ais =
                     new AudioInputStream(new ByteArrayInputStream(pcm), fmt, sampleCount)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }
}
