package com.elitale.coldbirds.coldcalling.ui.support;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * Applies the coldCalling brand mark to a {@link Stage} for the taskbar/window
 * decoration. Several PNG sizes are supplied so the OS can pick the sharpest one.
 *
 * <p>The packaged native bundle gets its dock/taskbar icon from the installer
 * ({@code .icns}/{@code .ico}); this covers the window title-bar icon and the
 * dev-mode launch. Missing or unreadable resources are skipped silently — a
 * cosmetic icon must never stop the window from opening.
 */
public final class AppIcons {

    private static final List<String> RESOURCE_PATHS = List.of(
            "/icons/coldcalling-256.png",
            "/icons/coldcalling-128.png",
            "/icons/coldcalling-64.png",
            "/icons/coldcalling-32.png",
            "/icons/coldcalling-16.png");

    private AppIcons() {
        // Static utility.
    }

    /** Adds every available brand icon size to {@code stage}. */
    public static void applyTo(final Stage stage) {
        Objects.requireNonNull(stage, "stage must not be null");
        for (final String path : RESOURCE_PATHS) {
            try (InputStream in = AppIcons.class.getResourceAsStream(path)) {
                if (in != null) {
                    stage.getIcons().add(new Image(in));
                }
            } catch (final Exception ignored) {
                // Skip a bad icon resource; the window must still open.
            }
        }
    }
}
