package com.elitale.coldbirds.coldcalling.app;

/**
 * Plain (non-JavaFX) entry point for packaged/native builds.
 *
 * <p>When the main class extends {@link javafx.application.Application} and is launched from
 * the <em>classpath</em> — as it is inside a {@code jpackage} app image — the JavaFX launcher
 * aborts with <em>"JavaFX runtime components are missing, and are required to run this
 * application"</em>. Delegating through a launcher that does <strong>not</strong> extend
 * {@code Application} sidesteps that check, so the same image runs on macOS, Windows, and Linux.
 *
 * <p>{@code ./gradlew :app:run} and {@code :app:jpackage} both target this class.
 */
public final class Launcher {

    private Launcher() {
        // Static entry point only.
    }

    public static void main(final String[] args) {
        ColdCallingApp.main(args);
    }
}
