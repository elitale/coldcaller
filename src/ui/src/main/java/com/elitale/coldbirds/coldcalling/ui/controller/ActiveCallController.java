package com.elitale.coldbirds.coldcalling.ui.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.Duration;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Controller for the active-call view (active-call-view.fxml).
 * <p>
 * Owns the live duration timer (a JavaFX {@link Timeline} firing every second
 * on the FX Application Thread) and exposes mute/hold toggles and a
 * notes/disposition form.
 * <p>
 * Threading: {@link #startCall} and {@link #endCall} are safe to call from any
 * thread. All other methods must run on the FX Application Thread.
 */
public final class ActiveCallController {

    @FXML private Label    remotePartyLabel;
    @FXML private Label    durationLabel;
    @FXML private Button   muteButton;
    @FXML private Button   holdButton;
    @FXML private Button   keypadButton;
    @FXML private Button   hangUpButton;
    @FXML private TextArea notesArea;
    @FXML private ComboBox<String> dispositionBox;

    private Timeline durationTimer;
    private Instant  callStartedAt;
    private boolean  muted  = false;
    private boolean  onHold = false;

    // Callbacks
    private Runnable          onHangUpCb = () -> {};
    private Consumer<Boolean> onMuteCb   = ignored -> {};
    private Consumer<Boolean> onHoldCb   = ignored -> {};
    private Consumer<String>  onNotesCb  = ignored -> {};

    /** Default no-arg constructor — required by FXMLLoader. */
    public ActiveCallController() {}

    public void setOnHangUp(Runnable cb)             { this.onHangUpCb = Objects.requireNonNull(cb); }
    public void setOnMute(Consumer<Boolean> cb)      { this.onMuteCb   = Objects.requireNonNull(cb); }
    public void setOnHold(Consumer<Boolean> cb)      { this.onHoldCb   = Objects.requireNonNull(cb); }
    public void setOnNotesSaved(Consumer<String> cb) { this.onNotesCb  = Objects.requireNonNull(cb); }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        dispositionBox.setItems(FXCollections.observableArrayList(
                "Interested", "Not Interested", "Callback",
                "Voicemail",  "No Answer",      "Busy", "DNC", "Failed"
        ));
        durationTimer = new Timeline(
                new KeyFrame(Duration.seconds(1), e -> refreshDuration())
        );
        durationTimer.setCycleCount(Timeline.INDEFINITE);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Begin showing an active call. Resets mute/hold state and starts the
     * duration timer. Safe to call from any thread.
     *
     * @param remoteDisplay formatted name or E.164 number of the remote party
     * @param connectedAt   exact instant the call was answered
     */
    public void startCall(String remoteDisplay, Instant connectedAt) {
        Objects.requireNonNull(remoteDisplay, "remoteDisplay must not be null");
        Objects.requireNonNull(connectedAt,   "connectedAt must not be null");
        if (Platform.isFxApplicationThread()) {
            remotePartyLabel.setText(remoteDisplay);
            callStartedAt = connectedAt;
            muted  = false;
            onHold = false;
            muteButton.setText("Mute");
            holdButton.setText("Hold");
            notesArea.clear();
            dispositionBox.setValue(null);
            durationTimer.play();
        } else {
            Platform.runLater(() -> startCall(remoteDisplay, connectedAt));
        }
    }

    /**
     * Stop the duration timer. Safe to call from any thread.
     */
    public void endCall() {
        if (Platform.isFxApplicationThread()) {
            durationTimer.stop();
        } else {
            Platform.runLater(this::endCall);
        }
    }

    /** @return current notes text (empty string if not yet initialised) */
    public String getNotes() {
        return (notesArea != null) ? notesArea.getText() : "";
    }

    /** @return selected disposition label, or empty string if none selected */
    public String getDisposition() {
        return (dispositionBox != null && dispositionBox.getValue() != null)
                ? dispositionBox.getValue()
                : "";
    }

    /**
     * Dispatch keyboard shortcuts for this view.
     * Called by {@link com.elitale.coldbirds.coldcalling.ui.MainWindow} from the
     * scene-level key handler when this view is the active center pane.
     * <ul>
     *   <li>Escape → hang up</li>
     * </ul>
     */
    public void handleKeyPress(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            event.consume();
            onHangUp();
        }
    }

    // ── FXML event handlers ───────────────────────────────────────────────────

    @FXML
    private void onMuteToggle() {
        muted = !muted;
        muteButton.setText(muted ? "Unmute" : "Mute");
        onMuteCb.accept(muted);
    }

    @FXML
    private void onHoldToggle() {
        onHold = !onHold;
        holdButton.setText(onHold ? "Resume" : "Hold");
        onHoldCb.accept(onHold);
    }

    @FXML
    private void onKeypadToggle() {
        // TODO: show/hide DTMF keypad overlay panel (issue #TODO)
    }

    @FXML
    private void onHangUp() {
        onNotesCb.accept(getNotes());
        onHangUpCb.run();
    }

    // ── timer ─────────────────────────────────────────────────────────────────

    private void refreshDuration() {
        if (callStartedAt == null) return;
        long seconds = java.time.Duration.between(callStartedAt, Instant.now()).getSeconds();
        durationLabel.setText(String.format("%d:%02d", seconds / 60, seconds % 60));
    }
}
