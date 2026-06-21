package com.elitale.coldbirds.coldcalling.ui.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.Objects;

/**
 * Controller for the incoming-call ring overlay (incoming-call-view.fxml).
 * <p>
 * The overlay is shown by {@link com.elitale.coldbirds.coldcalling.ui.MainWindow}
 * when a SIP INVITE arrives from the telephony layer.
 * <p>
 * Threading: {@link #showCaller} is safe to call from any thread.
 * All other methods must run on the FX Application Thread.
 */
public final class IncomingCallController {

    @FXML private Label  callerNameLabel;
    @FXML private Label  callerNumberLabel;
    @FXML private Button answerButton;
    @FXML private Button rejectButton;

    private Runnable onAnswer = () -> {};
    private Runnable onReject = () -> {};

    /** Default no-arg constructor — required by FXMLLoader. */
    public IncomingCallController() {}

    /** Register callback invoked when the user answers. */
    public void setOnAnswer(Runnable callback) {
        this.onAnswer = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Register callback invoked when the user rejects. */
    public void setOnReject(Runnable callback) {
        this.onReject = Objects.requireNonNull(callback, "callback must not be null");
    }

    /**
     * Update the display with caller information.
     * Safe to call from any thread — dispatches to the FX thread automatically.
     *
     * @param callerName   display name (may be blank for unknown callers)
     * @param callerNumber E.164 number string
     */
    public void showCaller(String callerName, String callerNumber) {
        Objects.requireNonNull(callerName,   "callerName must not be null");
        Objects.requireNonNull(callerNumber, "callerNumber must not be null");
        if (Platform.isFxApplicationThread()) {
            callerNameLabel.setText(callerName.isBlank() ? "Unknown Caller" : callerName);
            callerNumberLabel.setText(callerNumber);
        } else {
            Platform.runLater(() -> showCaller(callerName, callerNumber));
        }
    }

    /**
     * Dispatch keyboard shortcuts for this view.
     * Called by {@link com.elitale.coldbirds.coldcalling.ui.MainWindow} from the
     * scene-level key handler when this view is the active center pane.
     * <ul>
     *   <li>Space  → answer</li>
     *   <li>Escape → reject</li>
     * </ul>
     */
    public void handleKeyPress(KeyEvent event) {
        if (event.getCode() == KeyCode.SPACE) {
            event.consume();
            onAnswer();
        } else if (event.getCode() == KeyCode.ESCAPE) {
            event.consume();
            onReject();
        }
    }

    // ── FXML event handlers ───────────────────────────────────────────────────

    @FXML
    private void onAnswer() {
        onAnswer.run();
    }

    @FXML
    private void onReject() {
        onReject.run();
    }
}
