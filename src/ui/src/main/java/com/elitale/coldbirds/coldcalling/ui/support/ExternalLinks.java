package com.elitale.coldbirds.coldcalling.ui.support;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * App-wide bridge for opening external links (web pages, {@code mailto:}) in the
 * user's default browser/mail client.
 *
 * <p>JavaFX exposes this capability only through
 * {@link javafx.application.HostServices}, which is reachable from the {@code Application}
 * instance and nowhere else. Rather than thread that handle through every constructor
 * down to leaf views, the app wires it once at startup via {@link #configure(Consumer)}
 * and leaf nodes call {@link #open}/{@link #openMail}. Using {@code HostServices}
 * (instead of {@code java.awt.Desktop}) avoids initialising AWT alongside the JavaFX
 * toolkit — which is important on macOS.
 */
public final class ExternalLinks {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalLinks.class);

    private static volatile Consumer<String> opener =
            url -> LOG.warn("No link opener configured; ignoring {}", url);

    private ExternalLinks() {
    }

    /** Wire the platform opener once, at application start. */
    public static void configure(final Consumer<String> linkOpener) {
        opener = Objects.requireNonNull(linkOpener, "linkOpener must not be null");
    }

    /** Open an {@code http(s)} or {@code mailto:} URL. No-ops on blank/unsupported schemes. */
    public static void open(final String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        final String trimmed = url.trim();
        final String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")
                && !lower.startsWith("mailto:")) {
            LOG.warn("Refusing to open unsupported URL scheme: {}", trimmed);
            return;
        }
        try {
            opener.accept(trimmed);
        } catch (RuntimeException e) {
            LOG.warn("Failed to open link {}: {}", trimmed, e.getMessage());
        }
    }

    /** Open the default mail client composing a message to {@code email}. */
    public static void openMail(final String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        open("mailto:" + email.trim());
    }
}
