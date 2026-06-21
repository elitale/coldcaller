package com.elitale.coldbirds.coldcalling.ui.support;

import javafx.scene.image.Image;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads bundled country flag images (PNG) from {@code /flags/{iso}.png}.
 * <p>
 * JavaFX cannot render Unicode regional-indicator flag emoji on macOS (they show
 * as boxed letter pairs), so the UI renders real flag bitmaps instead. Images are
 * decoded once and cached; a missing flag yields {@link Optional#empty()} so the
 * caller can fall back to an ISO badge.
 */
public final class FlagImages {

    /** Decode height in px (2x the ~18px display size for crisp HiDPI). */
    private static final double DECODE_HEIGHT = 36;

    private static final Map<String, Optional<Image>> CACHE = new ConcurrentHashMap<>();

    private FlagImages() {}

    /** Classpath resource path for a country's flag, e.g. {@code "/flags/in.png"}. */
    public static String resourcePath(String isoCode) {
        Objects.requireNonNull(isoCode, "isoCode must not be null");
        return "/flags/" + isoCode.toLowerCase(Locale.ROOT) + ".png";
    }

    /** Load (and cache) the flag image for an ISO code, or empty if none is bundled. */
    public static Optional<Image> load(String isoCode) {
        return CACHE.computeIfAbsent(resourcePath(isoCode), path -> {
            var url = FlagImages.class.getResource(path);
            if (url == null) {
                return Optional.empty();
            }
            return Optional.of(new Image(url.toExternalForm(), 0, DECODE_HEIGHT, true, true));
        });
    }
}
