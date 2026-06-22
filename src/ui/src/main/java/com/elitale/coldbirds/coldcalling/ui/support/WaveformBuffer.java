package com.elitale.coldbirds.coldcalling.ui.support;

/**
 * Fixed-capacity ring buffer of recent audio levels for the call waveform meter.
 *
 * <p>Pure (no JavaFX dependency) so the rolling-window math is unit-tested
 * headlessly; the {@link AudioWaveform} canvas renders a {@link #snapshot()} each frame.
 */
public final class WaveformBuffer {

    private final double[] values;
    private int next; // index of the next write
    private int size; // filled slots (≤ capacity)

    /**
     * @param capacity number of recent samples to retain; must be positive
     */
    public WaveformBuffer(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.values = new double[capacity];
    }

    /** @return the retained-sample capacity */
    public int capacity() {
        return values.length;
    }

    /** @return number of samples currently held (0..capacity) */
    public int size() {
        return size;
    }

    /**
     * Append the newest level, evicting the oldest once at capacity.
     *
     * @param level normalized level (0..1)
     */
    public void push(final double level) {
        values[next] = level;
        next = (next + 1) % values.length;
        if (size < values.length) {
            size++;
        }
    }

    /** Drop all retained samples. */
    public void reset() {
        next = 0;
        size = 0;
    }

    /**
     * @return the retained samples, oldest first (length == {@link #size()})
     */
    public double[] snapshot() {
        final double[] out = new double[size];
        final int start = (next - size + values.length) % values.length;
        for (int i = 0; i < size; i++) {
            out[i] = values[(start + i) % values.length];
        }
        return out;
    }
}
