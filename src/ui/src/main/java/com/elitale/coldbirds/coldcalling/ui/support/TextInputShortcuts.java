package com.elitale.coldbirds.coldcalling.ui.support;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;

import java.util.Objects;

/**
 * Installs platform-agnostic clipboard shortcuts (cut / copy / paste / select-all)
 * for text inputs.
 *
 * <p>On macOS a JavaFX app without a native {@code Edit} menu does not receive the
 * built-in {@code Cmd+C/V/X} bindings, so pasting into a {@link TextInputControl}
 * silently does nothing. This handler runs during the bubbling phase: on platforms
 * where the control already handled (and consumed) the shortcut it never fires, so
 * there is no double action; otherwise it performs the action on the focused input.
 */
public final class TextInputShortcuts {

    private TextInputShortcuts() {}

    /** Wire clipboard shortcuts to every text input in the given scene. */
    public static void install(final Scene scene) {
        Objects.requireNonNull(scene, "scene must not be null");
        scene.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (!event.isShortcutDown()) {
                return;
            }
            final Node focused = scene.getFocusOwner();
            if (!(focused instanceof TextInputControl input)) {
                return;
            }
            switch (event.getCode()) {
                case V -> { input.paste();     event.consume(); }
                case C -> { input.copy();      event.consume(); }
                case X -> { input.cut();       event.consume(); }
                case A -> { input.selectAll(); event.consume(); }
                default -> { /* not a clipboard shortcut */ }
            }
        });
    }
}
