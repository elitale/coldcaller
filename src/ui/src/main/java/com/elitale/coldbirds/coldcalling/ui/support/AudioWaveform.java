package com.elitale.coldbirds.coldcalling.ui.support;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Compact scrolling bar meter for the live microphone level: each {@link #push(double)}
 * appends a normalized sample (0..1) and repaints, so the bars scroll right-to-left and
 * rise/fall with the rep's voice. Flat when muted (the controller pushes zeros).
 *
 * <p>FX-thread only. Width/height come from FXML; the rolling-window math lives in the
 * pure, unit-tested {@link WaveformBuffer}.
 */
public final class AudioWaveform extends Canvas {

    private static final int    BARS    = 56;
    private static final double GAP     = 2.0;
    private static final double MIN_BAR = 2.0;

    private final WaveformBuffer buffer = new WaveformBuffer(BARS);
    private Color barColor = Color.web("#0071E3"); // -cc-accent

    public AudioWaveform() {
        widthProperty().addListener((obs, old, val) -> redraw());
        heightProperty().addListener((obs, old, val) -> redraw());
    }

    /**
     * Set the bar colour (e.g. greyed while muted).
     *
     * @param color new bar colour; ignored if null
     */
    public void setBarColor(final Color color) {
        if (color != null) {
            this.barColor = color;
            redraw();
        }
    }

    /**
     * Append the latest normalized level and repaint.
     *
     * @param level normalized level; clamped to [0,1]
     */
    public void push(final double level) {
        buffer.push(Math.max(0.0, Math.min(1.0, level)));
        redraw();
    }

    /** Clear the meter back to rest. */
    public void clear() {
        buffer.reset();
        redraw();
    }

    private void redraw() {
        final GraphicsContext g = getGraphicsContext2D();
        final double w = getWidth();
        final double h = getHeight();
        g.clearRect(0, 0, w, h);
        if (w <= 0 || h <= 0) {
            return;
        }
        final int slots = buffer.capacity();
        final double barW = Math.max(1.0, (w - (slots - 1) * GAP) / slots);
        final double midY = h / 2.0;
        final double[] samples = buffer.snapshot();
        g.setFill(barColor);
        // Right-align the newest sample at the right edge so the meter scrolls left.
        final int offset = slots - samples.length;
        for (int i = 0; i < samples.length; i++) {
            final double x = (offset + i) * (barW + GAP);
            final double barH = Math.max(MIN_BAR, samples[i] * (h - 2.0));
            g.fillRoundRect(x, midY - barH / 2.0, barW, barH, barW, barW);
        }
    }
}
