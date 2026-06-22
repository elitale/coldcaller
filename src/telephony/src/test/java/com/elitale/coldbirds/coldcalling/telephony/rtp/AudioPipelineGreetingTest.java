package com.elitale.coldbirds.coldcalling.telephony.rtp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link AudioPipeline}'s voicemail-greeting injection seam.
 * Exercises the frame-selection logic directly, with no audio hardware.
 */
class AudioPipelineGreetingTest {

    private AudioPipeline pipeline;
    private short[] loudMic;

    @BeforeEach
    void setUp() {
        // System-default devices (null) are never opened — start() is never called.
        pipeline = new AudioPipeline(mock(RtpTransport.class), null, null);
        loudMic = new short[160];
        java.util.Arrays.fill(loudMic, (short) 8000); // well above the silence threshold
    }

    @Test
    void noGreeting_loudMic_returnsMicFrame() {
        assertThat(pipeline.selectOutboundFrame(loudMic, 0.5)).isSameAs(loudMic);
    }

    @Test
    void noGreeting_silentMic_returnsNull() {
        assertThat(pipeline.selectOutboundFrame(loudMic, 0.0)).isNull();
    }

    @Test
    void playGreeting_emptyList_isNoOp() {
        pipeline.playGreeting(List.of());
        assertThat(pipeline.isPlayingGreeting()).isFalse();
    }

    @Test
    void playGreeting_thenSelect_returnsGreetingFramesInOrder_thenRestoresMic() {
        final short[] g0 = frameOf((short) 1);
        final short[] g1 = frameOf((short) 2);
        pipeline.playGreeting(List.of(g0, g1));

        assertThat(pipeline.isPlayingGreeting()).isTrue();
        assertThat(pipeline.selectOutboundFrame(loudMic, 0.5)).isSameAs(g0);
        assertThat(pipeline.selectOutboundFrame(loudMic, 0.5)).isSameAs(g1);
        // Greeting exhausted: next tick falls back to the live mic and clears the flag.
        assertThat(pipeline.selectOutboundFrame(loudMic, 0.5)).isSameAs(loudMic);
        assertThat(pipeline.isPlayingGreeting()).isFalse();
    }

    @Test
    void playGreeting_ignoredWhileAlreadyPlaying() {
        final short[] first = frameOf((short) 1);
        pipeline.playGreeting(List.of(first));

        pipeline.playGreeting(List.of(frameOf((short) 9))); // must be ignored

        assertThat(pipeline.selectOutboundFrame(loudMic, 0.5)).isSameAs(first);
    }

    private static short[] frameOf(final short value) {
        final short[] frame = new short[160];
        java.util.Arrays.fill(frame, value);
        return frame;
    }
}
