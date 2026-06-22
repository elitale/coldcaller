package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.ui.support.CallDurationFormatter;
import com.elitale.coldbirds.coldcalling.ui.support.DispositionCatalog;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Controller for the in-window calling screen (active-call-view.fxml).
 * <p>
 * Drives four lifecycle phases — Ringing → Active → On Hold → Ended — and owns
 * a one-second {@link Timeline} that refreshes the live duration once connected.
 * Primary controls (Mute · Keypad · Hold · Voicemail · Hang Up) carry Bootstrap
 * glyphs; disposition chips and a DTMF keypad overlay are built in code.
 * <p>
 * Threading: {@link #startRinging}, {@link #markConnected}, {@link #startActive}
 * and {@link #endCall} are safe to call from any thread; all other methods must
 * run on the FX Application Thread.
 */
public final class ActiveCallController {

    private enum Phase { RINGING, ACTIVE, HOLD, ENDED }

    @FXML private Button    moreButton;
    @FXML private StackPane avatarRing;
    @FXML private Label     avatarLabel;
    @FXML private Label     remotePartyLabel;
    @FXML private Label     statusLabel;
    @FXML private Button    muteButton;
    @FXML private Button    keypadButton;
    @FXML private Button    holdButton;
    @FXML private Button    voicemailButton;
    @FXML private Button    hangUpButton;
    @FXML private VBox      keypadOverlay;
    @FXML private Label     dtmfReadout;
    @FXML private GridPane  keypadGrid;
    @FXML private FlowPane  dispositionChips;
    @FXML private TextArea  notesArea;

    private final ToggleGroup dispositionGroup = new ToggleGroup();
    private Timeline durationTimer;
    private Instant  callStartedAt;
    private Phase    phase = Phase.RINGING;
    private boolean  muted = false;

    // Callbacks
    private Runnable          onHangUpCb = () -> {};
    private Consumer<Boolean> onMuteCb   = ignored -> {};
    private Consumer<Boolean> onHoldCb   = ignored -> {};
    private Consumer<String>  onNotesCb  = ignored -> {};
    private Consumer<String>  onDtmfCb   = ignored -> {};

    /** Default no-arg constructor — required by FXMLLoader. */
    public ActiveCallController() {}

    public void setOnHangUp(Runnable cb)             { this.onHangUpCb = Objects.requireNonNull(cb); }
    public void setOnMute(Consumer<Boolean> cb)      { this.onMuteCb   = Objects.requireNonNull(cb); }
    public void setOnHold(Consumer<Boolean> cb)      { this.onHoldCb   = Objects.requireNonNull(cb); }
    public void setOnNotesSaved(Consumer<String> cb) { this.onNotesCb  = Objects.requireNonNull(cb); }
    public void setOnDtmf(Consumer<String> cb)       { this.onDtmfCb   = Objects.requireNonNull(cb); }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        applyIcon(muteButton,      "bi-mic",              "Mute");
        applyIcon(keypadButton,    "bi-grid-3x3-gap",     "Keypad");
        applyIcon(holdButton,      "bi-pause-fill",       "Hold");
        applyIcon(voicemailButton, "bi-soundwave",        "Voicemail");
        applyIcon(hangUpButton,    "bi-telephone-x-fill", "Hang Up");
        applyIcon(moreButton,      "bi-three-dots",       null);

        voicemailButton.setDisable(true);
        voicemailButton.setTooltip(new Tooltip("Voicemail drop — coming soon"));
        moreButton.setDisable(true);
        moreButton.setTooltip(new Tooltip("Switch mic / speaker — coming soon"));

        buildKeypad();
        buildDispositionChips();

        durationTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshDuration()));
        durationTimer.setCycleCount(Timeline.INDEFINITE);
    }

    private static void applyIcon(Button button, String iconLiteral, String text) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(20);
        button.setGraphic(icon);
        button.setContentDisplay(text == null ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.TOP);
    }

    private void buildKeypad() {
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            Button btn = new Button(key);
            btn.getStyleClass().add("keypad-key");
            btn.setOnAction(e -> pressDigit(key));
            keypadGrid.add(btn, i % 3, i / 3);
        }
    }

    private void buildDispositionChips() {
        for (DispositionCatalog.Option option : DispositionCatalog.ALL) {
            ToggleButton chip = new ToggleButton(option.label());
            FontIcon icon = new FontIcon(option.iconLiteral());
            icon.setIconSize(14);
            chip.setGraphic(icon);
            chip.setToggleGroup(dispositionGroup);
            chip.setUserData(option.label());
            chip.getStyleClass().add("disposition-chip");
            dispositionChips.getChildren().add(chip);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Show the screen in its Ringing phase: identity set, controls disabled,
     * notes focused, duration timer stopped. Resets prior notes/disposition.
     * Safe to call from any thread.
     */
    public void startRinging(String remoteDisplay) {
        Objects.requireNonNull(remoteDisplay, "remoteDisplay must not be null");
        runOnFx(() -> {
            reset(remoteDisplay);
            phase = Phase.RINGING;
            statusLabel.setText("Ringing\u2026");
            setRingStyle("call-ring--ringing");
            hangUpButton.setText("Cancel");
            setControlsDisabled(true);
            notesArea.requestFocus();
        });
    }

    /**
     * Transition the current Ringing screen to Active: start the timer and
     * enable controls. Preserves any notes typed during ringing. Safe to call
     * from any thread.
     */
    public void markConnected(Instant connectedAt) {
        Objects.requireNonNull(connectedAt, "connectedAt must not be null");
        runOnFx(() -> {
            callStartedAt = connectedAt;
            phase = Phase.ACTIVE;
            setRingStyle("call-ring--active");
            hangUpButton.setText("Hang Up");
            setControlsDisabled(false);
            refreshDuration();
            durationTimer.playFromStart();
        });
    }

    /**
     * Open the screen directly in its Active phase (inbound answered path):
     * resets identity/notes then connects. Safe to call from any thread.
     */
    public void startActive(String remoteDisplay, Instant connectedAt) {
        Objects.requireNonNull(remoteDisplay, "remoteDisplay must not be null");
        Objects.requireNonNull(connectedAt,   "connectedAt must not be null");
        runOnFx(() -> {
            reset(remoteDisplay);
            markConnected(connectedAt);
        });
    }

    /** Stop the duration timer. Safe to call from any thread. */
    public void endCall() {
        runOnFx(() -> durationTimer.stop());
    }

    /**
     * Move the screen to its Ended phase after a failed call, showing the
     * (already human-readable) failure reason in place of the duration and
     * relabelling the primary button to "Close". Safe to call from any thread.
     */
    public void markFailed(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        runOnFx(() -> {
            durationTimer.stop();
            phase = Phase.ENDED;
            setRingStyle("call-ring--failed");
            setControlsDisabled(true);
            if (!statusLabel.getStyleClass().contains("call-status--failed")) {
                statusLabel.getStyleClass().add("call-status--failed");
            }
            statusLabel.setText(reason.isBlank() ? "Call failed" : reason);
            hangUpButton.setText("Close");
            ((FontIcon) hangUpButton.getGraphic()).setIconLiteral("bi-x-lg");
        });
    }

    /**
     * Open the calling screen directly in its failed Ended phase (a call that
     * never started). Resets identity then shows the reason. Safe to call from
     * any thread.
     */
    public void showFailed(String remoteDisplay, String reason) {
        Objects.requireNonNull(remoteDisplay, "remoteDisplay must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        runOnFx(() -> {
            reset(remoteDisplay);
            markFailed(reason);
        });
    }

    /** @return current notes text (empty string if not yet initialised) */
    public String getNotes() {
        return (notesArea != null) ? notesArea.getText() : "";
    }

    /** @return the domain disposition for the selected chip, if any. */
    public Optional<CallDisposition> getDisposition() {
        if (dispositionGroup.getSelectedToggle() == null) return Optional.empty();
        String label = (String) dispositionGroup.getSelectedToggle().getUserData();
        return DispositionCatalog.toDisposition(label, Instant.now());
    }

    /**
     * Dispatch keyboard shortcuts: Escape hangs up always; once connected and
     * with the notes field unfocused, M/H/K toggle controls and 1–8 pick a
     * disposition. While the keypad overlay is open, digits and star/hash send DTMF.
     */
    public void handleKeyPress(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            event.consume();
            onHangUp();
            return;
        }
        if (phase == Phase.RINGING || phase == Phase.ENDED) return;
        if (keypadOverlay.isVisible()) {
            String text = event.getText();
            if (text != null && text.matches("[0-9*#]")) {
                event.consume();
                pressDigit(text);
            }
            return;
        }
        if (notesArea.isFocused()) return;
        switch (event.getCode()) {
            case M -> { event.consume(); onMuteToggle(); }
            case H -> { event.consume(); onHoldToggle(); }
            case K -> { event.consume(); onKeypadToggle(); }
            default -> {
                String text = event.getText();
                if (text != null && text.matches("[1-8]")) {
                    event.consume();
                    selectDispositionByDigit(text);
                }
            }
        }
    }

    // ── FXML event handlers ───────────────────────────────────────────────────

    @FXML
    private void onMuteToggle() {
        muted = !muted;
        ((FontIcon) muteButton.getGraphic()).setIconLiteral(muted ? "bi-mic-mute-fill" : "bi-mic");
        muteButton.setText(muted ? "Unmute" : "Mute");
        onMuteCb.accept(muted);
    }

    @FXML
    private void onHoldToggle() {
        boolean held = phase != Phase.HOLD;
        phase = held ? Phase.HOLD : Phase.ACTIVE;
        ((FontIcon) holdButton.getGraphic()).setIconLiteral(held ? "bi-play-fill" : "bi-pause-fill");
        holdButton.setText(held ? "Resume" : "Hold");
        setRingStyle(held ? "call-ring--hold" : "call-ring--active");
        if (held) statusLabel.setText("On hold");
        onHoldCb.accept(held);
    }

    @FXML
    private void onKeypadToggle() {
        boolean show = !keypadOverlay.isVisible();
        keypadOverlay.setVisible(show);
        keypadOverlay.setManaged(show);
        if (show) {
            if (!keypadButton.getStyleClass().contains("call-control--on")) {
                keypadButton.getStyleClass().add("call-control--on");
            }
            keypadOverlay.requestFocus();
        } else {
            keypadButton.getStyleClass().remove("call-control--on");
            dtmfReadout.setText("");
        }
    }

    @FXML
    private void onHangUp() {
        onNotesCb.accept(getNotes());
        onHangUpCb.run();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void reset(String remoteDisplay) {
        remotePartyLabel.setText(remoteDisplay);
        avatarLabel.setText(initials(remoteDisplay));
        notesArea.clear();
        dispositionGroup.selectToggle(null);
        dtmfReadout.setText("");
        keypadOverlay.setVisible(false);
        keypadOverlay.setManaged(false);
        keypadButton.getStyleClass().remove("call-control--on");
        muted = false;
        ((FontIcon) muteButton.getGraphic()).setIconLiteral("bi-mic");
        muteButton.setText("Mute");
        holdButton.setText("Hold");
        ((FontIcon) holdButton.getGraphic()).setIconLiteral("bi-pause-fill");
        hangUpButton.setText("Hang Up");
        ((FontIcon) hangUpButton.getGraphic()).setIconLiteral("bi-telephone-x-fill");
        statusLabel.getStyleClass().remove("call-status--failed");
        durationTimer.stop();
        statusLabel.setText("");
    }

    private void pressDigit(String digit) {
        dtmfReadout.setText(dtmfReadout.getText() + digit);
        onDtmfCb.accept(digit);
    }

    private void selectDispositionByDigit(String digit) {
        DispositionCatalog.ALL.stream()
                .filter(option -> option.digit().equals(digit))
                .findFirst()
                .ifPresent(option -> dispositionChips.getChildren().stream()
                        .filter(node -> node instanceof ToggleButton)
                        .map(node -> (ToggleButton) node)
                        .filter(chip -> option.label().equals(chip.getUserData()))
                        .findFirst()
                        .ifPresent(chip -> chip.setSelected(true)));
    }

    private void setControlsDisabled(boolean disabled) {
        muteButton.setDisable(disabled);
        keypadButton.setDisable(disabled);
        holdButton.setDisable(disabled);
    }

    private void setRingStyle(String activeClass) {
        avatarRing.getStyleClass().removeAll(
                "call-ring--ringing", "call-ring--active", "call-ring--hold");
        avatarRing.getStyleClass().add(activeClass);
    }

    private void refreshDuration() {
        if (callStartedAt == null) return;
        statusLabel.setText(CallDurationFormatter.format(
                java.time.Duration.between(callStartedAt, Instant.now())));
    }

    private static String initials(String display) {
        String trimmed = (display == null) ? "" : display.strip();
        if (trimmed.isEmpty()) return "?";
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
        }
        char first = trimmed.charAt(0);
        return Character.isLetter(first) ? String.valueOf(Character.toUpperCase(first)) : "#";
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }
}
