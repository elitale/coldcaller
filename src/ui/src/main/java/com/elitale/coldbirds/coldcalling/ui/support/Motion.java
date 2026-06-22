package com.elitale.coldbirds.coldcalling.ui.support;

import javafx.animation.ScaleTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Global Motion Doctrine gate. Non-essential animations consult {@link #isReduced()}
 * and either play their full form or collapse to an instantaneous state change.
 *
 * <p>The flag mirrors the user's "Reduce motion" preference. JavaFX 21 exposes no
 * portable OS {@code prefers-reduced-motion} hint, so this in-app setting is the single
 * source of truth; {@code app} wiring seeds it from {@code SettingsService.isReduceMotion()}
 * at launch and the Settings screen updates it live.
 */
public final class Motion {

    private static volatile boolean reduced = false;

    private Motion() {
    }

    /** Set the global reduce-motion state (called from the FX thread on settings change). */
    public static void setReduced(final boolean value) {
        reduced = value;
    }

    /** @return {@code true} when non-essential animation should be suppressed */
    public static boolean isReduced() {
        return reduced;
    }

    /**
     * Brief press-feedback scale pulse on a control. No-op when {@code node} is {@code null}
     * or reduce-motion is enabled, so callers need not branch.
     *
     * @param node the control that was pressed
     */
    public static void pressFlash(final Node node) {
        if (node == null || reduced) {
            return;
        }
        final ScaleTransition pulse = new ScaleTransition(Duration.millis(90), node);
        pulse.setFromX(1.0);
        pulse.setFromY(1.0);
        pulse.setToX(0.94);
        pulse.setToY(0.94);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(2);
        pulse.playFromStart();
    }
}
