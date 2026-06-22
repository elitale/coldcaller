package com.elitale.coldbirds.coldcalling.telephony.rtp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import static org.assertj.core.api.Assertions.assertThat;

final class CallRecorderTest {

    @Test
    void createsParentDirectoriesAndWritesFrames(@TempDir Path dir) throws Exception {
        final Path wav = dir.resolve("2026-06-22").resolve("rec.wav");

        try (CallRecorder recorder = new CallRecorder(wav)) {
            recorder.onRemoteFrame(new short[160]);
            recorder.onMicFrame(new short[160]);
            recorder.onMicFrame(new short[160]); // no remote queued → mic-only
        }

        assertThat(wav).exists();
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            assertThat(in.getFrameLength()).isEqualTo(320); // 2 mic frames * 160
            assertThat(in.getFormat().getChannels()).isEqualTo(1);
        }
    }

    @Test
    void mixesNearAndFarWithClamping(@TempDir Path dir) throws Exception {
        final Path wav = dir.resolve("mix.wav");

        try (CallRecorder recorder = new CallRecorder(wav)) {
            recorder.onRemoteFrame(new short[]{20000});       // far
            recorder.onMicFrame(new short[]{20000});          // near → sum clamps to MAX
        }

        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            final byte[] pcm = in.readAllBytes();
            final short sample = (short) ((pcm[0] & 0xFF) | (pcm[1] << 8));
            assertThat(sample).isEqualTo(Short.MAX_VALUE);
        }
    }

    @Test
    void closeIsIdempotent(@TempDir Path dir) throws Exception {
        final Path wav = dir.resolve("idem.wav");
        final CallRecorder recorder = new CallRecorder(wav);
        recorder.close();
        recorder.close(); // must not throw
        assertThat(wav).exists();
    }
}
