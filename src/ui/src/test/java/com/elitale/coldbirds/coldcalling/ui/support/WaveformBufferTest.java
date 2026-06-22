package com.elitale.coldbirds.coldcalling.ui.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WaveformBufferTest {

    @Test
    void newBuffer_isEmpty() {
        final WaveformBuffer buffer = new WaveformBuffer(4);
        assertThat(buffer.size()).isZero();
        assertThat(buffer.capacity()).isEqualTo(4);
        assertThat(buffer.snapshot()).isEmpty();
    }

    @Test
    void nonPositiveCapacity_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WaveformBuffer(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new WaveformBuffer(-3));
    }

    @Test
    void push_belowCapacity_keepsOrderOldestFirst() {
        final WaveformBuffer buffer = new WaveformBuffer(4);
        buffer.push(0.1);
        buffer.push(0.2);
        buffer.push(0.3);

        assertThat(buffer.size()).isEqualTo(3);
        assertThat(buffer.snapshot()).containsExactly(0.1, 0.2, 0.3);
    }

    @Test
    void push_overCapacity_dropsOldest() {
        final WaveformBuffer buffer = new WaveformBuffer(3);
        buffer.push(1.0);
        buffer.push(2.0);
        buffer.push(3.0);
        buffer.push(4.0);
        buffer.push(5.0);

        assertThat(buffer.size()).isEqualTo(3);
        assertThat(buffer.snapshot()).containsExactly(3.0, 4.0, 5.0);
    }

    @Test
    void reset_clearsAllSamples() {
        final WaveformBuffer buffer = new WaveformBuffer(3);
        buffer.push(1.0);
        buffer.push(2.0);

        buffer.reset();

        assertThat(buffer.size()).isZero();
        assertThat(buffer.snapshot()).isEmpty();
    }

    @Test
    void push_afterReset_startsFresh() {
        final WaveformBuffer buffer = new WaveformBuffer(3);
        buffer.push(1.0);
        buffer.reset();
        buffer.push(9.0);

        assertThat(buffer.snapshot()).containsExactly(9.0);
    }
}
