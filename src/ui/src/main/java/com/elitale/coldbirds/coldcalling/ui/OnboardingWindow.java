package com.elitale.coldbirds.coldcalling.ui;

import com.elitale.coldbirds.coldcalling.services.OnboardingService;
import com.elitale.coldbirds.coldcalling.ui.controller.OnboardingController;
import com.elitale.coldbirds.coldcalling.ui.support.TextInputShortcuts;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

/**
 * Standalone first-run setup wizard window. Owns the stage until onboarding
 * completes, then hands control back via the {@code onComplete} callback so the
 * caller can swap in the main application window.
 */
public final class OnboardingWindow {

    private static final double WIDTH     = 720;
    private static final double HEIGHT    = 680;
    private static final double MIN_WIDTH = 640;

    private final Stage stage;
    private final OnboardingService onboardingService;

    public OnboardingWindow(final Stage stage, final OnboardingService onboardingService) {
        this.stage = Objects.requireNonNull(stage, "stage must not be null");
        this.onboardingService =
                Objects.requireNonNull(onboardingService, "onboardingService must not be null");
    }

    /**
     * Build and show the wizard. Must be called on the FX Application Thread.
     *
     * @param onComplete invoked (on the FX thread) once onboarding finishes
     */
    public void show(final Runnable onComplete) {
        Objects.requireNonNull(onComplete, "onComplete must not be null");
        Application.setUserAgentStylesheet(Application.STYLESHEET_MODENA);

        final OnboardingController controller = new OnboardingController();
        controller.setOnboardingService(onboardingService);
        controller.setOnComplete(onComplete);

        final Parent root = loadFxml("/fxml/onboarding-view.fxml", controller);
        final Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets().add(
                Objects.requireNonNull(
                        OnboardingWindow.class.getResource("/css/cupertino-light.css"),
                        "cupertino-light.css not found in UI resources"
                ).toExternalForm()
        );
        TextInputShortcuts.install(scene);

        stage.setTitle("coldCalling — Setup");
        stage.setScene(scene);
        stage.setMinWidth(MIN_WIDTH);
        stage.centerOnScreen();
        stage.show();
    }

    private <T> Parent loadFxml(final String resourcePath, final T controller) {
        try {
            final FXMLLoader loader = new FXMLLoader(
                    Objects.requireNonNull(
                            getClass().getResource(resourcePath),
                            resourcePath + " not found in classpath"
                    )
            );
            loader.setController(controller);
            return loader.load();
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to load FXML: " + resourcePath, e);
        }
    }
}
