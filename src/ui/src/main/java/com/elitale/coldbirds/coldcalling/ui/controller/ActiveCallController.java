package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.ui.support.CallDurationFormatter;
import com.elitale.coldbirds.coldcalling.ui.support.CallParticipant;
import com.elitale.coldbirds.coldcalling.ui.support.CallTones;
import com.elitale.coldbirds.coldcalling.ui.support.DispositionCatalog;
import com.elitale.coldbirds.coldcalling.ui.support.LocalTimeFormatter;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Controller for the in-window calling screen (active-call-view.fxml).
 * <p>
 * Drives five phases — Ringing → Active → On Hold → Wrap-up / Failed — with a
 * pulsing avatar halo, a live duration + remote local-time clock, synthesised
 * ringback/connect/hangup/DTMF tones, contact identity, disposition chips and
 * notes. After a call ends naturally the screen stays in a Wrap-up phase so the
 * rep can finish notes and a disposition before saving.
 * <p>
 * Threading: all public mutators are safe to call from any thread.
 */
public final class ActiveCallController {

    private enum Phase { RINGING, ACTIVE, HOLD, WRAPUP, FAILED }

    @FXML private VBox      callingScreen;
    @FXML private Button    closeButton;
    @FXML private Region    haloCircle;
    @FXML private StackPane avatarRing;
    @FXML private Label     avatarLabel;
    @FXML private Label     remotePartyLabel;
    @FXML private Label     numberLabel;
    @FXML private Label     locationLabel;
    @FXML private Label     statusLabel;
    @FXML private Button    muteButton;
    @FXML private Button    keypadButton;
    @FXML private Button    holdButton;
    @FXML private Button    voicemailButton;
    @FXML private Button    hangUpButton;
    @FXML private Button    redialButton;
    @FXML private VBox      keypadOverlay;
    @FXML private Label     dtmfReadout;
    @FXML private GridPane  keypadGrid;
    @FXML private FlowPane  dispositionChips;
    @FXML private TextArea  notesArea;
    @FXML private Label     notesStatus;

    private final ToggleGroup dispositionGroup = new ToggleGroup();
    private final CallTones   tones = new CallTones();

    private Timeline tickTimer;
    private Timeline pulseTimer;
    private PauseTransition notesDebounce;
    private boolean  suppressAutoSave = false;
    private Instant  callStartedAt;
    private Phase    phase = Phase.RINGING;
    private boolean  muted = false;
    private Optional<Country> country = Optional.empty();
    private String   subtitle = "";

    // Callbacks
    private Runnable          onHangUpCb = () -> {};
    private Runnable          onRedialCb = () -> {};
    private Consumer<Boolean> onMuteCb   = ignored -> {};
    private Consumer<Boolean> onHoldCb   = ignored -> {};
    private Consumer<String>  onDtmfCb   = ignored -> {};
    private BiConsumer<Optional<CallDisposition>, String> onLogChangedCb = (d, n) -> {};

    /** Default no-arg constructor — required by FXMLLoader. */
    public ActiveCallController() {}

    public void setOnHangUp(Runnable cb)        { this.onHangUpCb = Objects.requireNonNull(cb); }
    public void setOnRedial(Runnable cb)        { this.onRedialCb = Objects.requireNonNull(cb); }
    public void setOnMute(Consumer<Boolean> cb) { this.onMuteCb   = Objects.requireNonNull(cb); }
    public void setOnHold(Consumer<Boolean> cb) { this.onHoldCb   = Objects.requireNonNull(cb); }
    public void setOnDtmf(Consumer<String> cb)  { this.onDtmfCb   = Objects.requireNonNull(cb); }

    /**
     * Register the auto-save sink. Fired (debounced for notes, immediately for a
     * disposition pick) with the current disposition + notes whenever the rep
     * edits the call log, so a record is never lost if they forget to save.
     */
    public void setOnLogChanged(BiConsumer<Optional<CallDisposition>, String> cb) {
        this.onLogChangedCb = Objects.requireNonNull(cb);
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        applyIcon(muteButton,      "bi-mic",              "Mute");
        applyIcon(keypadButton,    "bi-grid-3x3-gap",     "Keypad");
        applyIcon(holdButton,      "bi-pause-fill",       "Hold");
        applyIcon(voicemailButton, "bi-soundwave",        "Voicemail");
        applyIcon(hangUpButton,    "bi-telephone-x-fill", "Hang Up");
        applyIcon(redialButton,    "bi-telephone-outbound-fill", "Redial");
        applyIcon(closeButton,     "bi-x",                null);

        voicemailButton.setDisable(true);
        voicemailButton.setTooltip(new Tooltip("Voicemail drop — coming soon"));
        redialButton.setTooltip(new Tooltip("Call this number again"));
        showRedial(false);
        closeButton.setTooltip(new Tooltip("Close"));
        closeButton.setOnAction(e -> onHangUp());

        buildKeypad();
        buildDispositionChips();

        tickTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> onTick()));
        tickTimer.setCycleCount(Timeline.INDEFINITE);
        pulseTimer = buildPulse();

        // Auto-save: debounce note typing, persist a disposition pick immediately.
        notesDebounce = new PauseTransition(Duration.millis(500));
        notesDebounce.setOnFinished(e -> fireLogChanged());
        notesArea.textProperty().addListener((obs, old, val) -> {
            if (suppressAutoSave) return;
            setNotesStatus("Saving\u2026");
            notesDebounce.playFromStart();
        });
        dispositionGroup.selectedToggleProperty().addListener((obs, old, sel) -> {
            if (suppressAutoSave) return;
            notesDebounce.stop();
            fireLogChanged();
        });
    }

    private static void applyIcon(Button button, String iconLiteral, String text) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(20);
        button.setGraphic(icon);
        button.setContentDisplay(text == null ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.TOP);
    }

    private Timeline buildPulse() {
        Timeline pulse = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(haloCircle.scaleXProperty(), 0.85),
                        new KeyValue(haloCircle.scaleYProperty(), 0.85),
                        new KeyValue(haloCircle.opacityProperty(), 0.55)),
                new KeyFrame(Duration.millis(1400),
                        new KeyValue(haloCircle.scaleXProperty(), 1.6, Interpolator.EASE_OUT),
                        new KeyValue(haloCircle.scaleYProperty(), 1.6, Interpolator.EASE_OUT),
                        new KeyValue(haloCircle.opacityProperty(), 0.0, Interpolator.EASE_OUT)));
        pulse.setCycleCount(Timeline.INDEFINITE);
        return pulse;
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
     * Show the screen in its connecting phase ("Calling…") with the resolved
     * party. Opened the instant the user presses call, before the SIP INVITE is
     * even dispatched. Any thread.
     */
    public void startConnecting(CallParticipant party) {
        Objects.requireNonNull(party, "party must not be null");
        runOnFx(() -> {
            reset(party);
            phase = Phase.RINGING;
            statusLabel.setText("Calling\u2026");
            setRingStyle("call-ring--ringing");
            hangUpButton.setText("Cancel");
            setControlsDisabled(true);
            startPulse();
            tones.startRingback();
            animateIn();
        });
    }

    /**
     * Flip the connecting screen's status to "Ringing…" once the INVITE is on
     * the wire. No-op once the call has connected or failed. Any thread.
     */
    public void markRinging() {
        runOnFx(() -> {
            if (phase == Phase.RINGING) statusLabel.setText("Ringing\u2026");
        });
    }

    /** Transition the ringing screen to Active when the call connects. Any thread. */
    public void markConnected(Instant connectedAt) {
        Objects.requireNonNull(connectedAt, "connectedAt must not be null");
        runOnFx(() -> {
            callStartedAt = connectedAt;
            phase = Phase.ACTIVE;
            stopPulse();
            tones.stopRingback();
            tones.connect();
            setRingStyle("call-ring--active");
            hangUpButton.setText("Hang Up");
            setControlsDisabled(false);
            notesArea.requestFocus();
            refreshDuration();
            tickTimer.playFromStart();
        });
    }

    /** Open the screen directly in its Active phase (inbound answered). Any thread. */
    public void startActive(CallParticipant party, Instant connectedAt) {
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(connectedAt, "connectedAt must not be null");
        runOnFx(() -> {
            reset(party);
            animateIn();
            markConnected(connectedAt);
        });
    }

    /**
     * Move the screen to its Wrap-up phase after a call ends naturally: the line
     * is down but notes and disposition stay editable so the rep can log the
     * call, then "Save &amp; Close" persists and dismisses. Any thread.
     */
    public void markEnded(Instant endedAt) {
        Objects.requireNonNull(endedAt, "endedAt must not be null");
        runOnFx(() -> {
            stopPulse();
            tones.stopRingback();
            tones.hangup();
            phase = Phase.WRAPUP;
            setRingStyle("call-ring--ended");
            statusLabel.getStyleClass().remove("call-status--failed");
            statusLabel.setText("Call ended" + durationSuffix());
            disableCallControls();
            hangUpButton.setDisable(false);
            hangUpButton.setText("Save & Close");
            ((FontIcon) hangUpButton.getGraphic()).setIconLiteral("bi-check");
            showRedial(true);
            notesArea.requestFocus();
        });
    }

    /** Move the screen to its Failed phase, showing the reason. Any thread. */
    public void markFailed(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        runOnFx(() -> {
            stopPulse();
            tones.stopRingback();
            tones.hangup();
            phase = Phase.FAILED;
            setRingStyle("call-ring--failed");
            setControlsDisabled(true);
            hangUpButton.setDisable(false);
            if (!statusLabel.getStyleClass().contains("call-status--failed")) {
                statusLabel.getStyleClass().add("call-status--failed");
            }
            statusLabel.setText(reason.isBlank() ? "Call failed" : reason);
            hangUpButton.setText("Close");
            ((FontIcon) hangUpButton.getGraphic()).setIconLiteral("bi-x");
            showRedial(true);
        });
    }

    /** Open the calling screen directly in its Failed phase (a call that never started). */
    public void showFailed(CallParticipant party, String reason) {
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        runOnFx(() -> {
            reset(party);
            animateIn();
            markFailed(reason);
        });
    }

    /** Stop timers and sounds (called when the screen is dismissed). Any thread. */
    public void dispose() {
        runOnFx(() -> {
            tickTimer.stop();
            if (notesDebounce != null) notesDebounce.stop();
            stopPulse();
            tones.stopRingback();
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
        if (phase == Phase.RINGING || phase == Phase.WRAPUP || phase == Phase.FAILED) return;
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
        toggleStyle(muteButton, "call-control--on", muted);
        onMuteCb.accept(muted);
    }

    @FXML
    private void onHoldToggle() {
        boolean held = phase != Phase.HOLD;
        phase = held ? Phase.HOLD : Phase.ACTIVE;
        ((FontIcon) holdButton.getGraphic()).setIconLiteral(held ? "bi-play-fill" : "bi-pause-fill");
        holdButton.setText(held ? "Resume" : "Hold");
        toggleStyle(holdButton, "call-control--on", held);
        setRingStyle(held ? "call-ring--hold" : "call-ring--active");
        if (held) statusLabel.setText("On hold");
        onHoldCb.accept(held);
    }

    @FXML
    private void onKeypadToggle() {
        boolean show = !keypadOverlay.isVisible();
        keypadOverlay.setVisible(show);
        keypadOverlay.setManaged(show);
        toggleStyle(keypadButton, "call-control--on", show);
        if (show) {
            keypadOverlay.requestFocus();
        } else {
            dtmfReadout.setText("");
        }
    }

    @FXML
    private void onHangUp() {
        onHangUpCb.run();
    }

    @FXML
    private void onRedial() {
        onRedialCb.run();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void reset(CallParticipant party) {
        suppressAutoSave = true;
        if (notesDebounce != null) notesDebounce.stop();
        remotePartyLabel.setText(party.headline());
        avatarLabel.setText(party.initials());
        boolean named = party.name().isPresent();
        numberLabel.setText(named ? party.number() : "");
        numberLabel.setVisible(named);
        numberLabel.setManaged(named);
        subtitle = party.subtitle().orElse("");
        country = party.country();
        refreshLocation();

        notesArea.setText(party.priorNotes().orElse(""));
        selectPriorDisposition(party.priorDisposition());
        dtmfReadout.setText("");
        keypadOverlay.setVisible(false);
        keypadOverlay.setManaged(false);
        keypadButton.getStyleClass().remove("call-control--on");
        muted = false;
        ((FontIcon) muteButton.getGraphic()).setIconLiteral("bi-mic");
        muteButton.setText("Mute");
        muteButton.getStyleClass().remove("call-control--on");
        holdButton.setText("Hold");
        holdButton.getStyleClass().remove("call-control--on");
        ((FontIcon) holdButton.getGraphic()).setIconLiteral("bi-pause-fill");
        hangUpButton.setText("Hang Up");
        hangUpButton.setDisable(false);
        ((FontIcon) hangUpButton.getGraphic()).setIconLiteral("bi-telephone-x-fill");
        showRedial(false);
        statusLabel.getStyleClass().remove("call-status--failed");
        statusLabel.setText("");
        setNotesStatus("");
        tickTimer.playFromStart();
        suppressAutoSave = false;
    }

    private void fireLogChanged() {
        if (suppressAutoSave) return;
        onLogChangedCb.accept(getDisposition(), getNotes());
        setNotesStatus("Saved \u2713");
    }

    /** Select the disposition chip that matches a prior call's outcome, if any. */
    private void selectPriorDisposition(Optional<CallDisposition> disposition) {
        if (disposition.isEmpty()) {
            dispositionGroup.selectToggle(null);
            return;
        }
        final String label = DispositionCatalog.labelOf(disposition.get());
        for (Toggle toggle : dispositionGroup.getToggles()) {
            if (label.equals(toggle.getUserData())) {
                dispositionGroup.selectToggle(toggle);
                return;
            }
        }
        dispositionGroup.selectToggle(null);
    }

    private void setNotesStatus(String text) {
        if (notesStatus == null) return;
        notesStatus.setText(text);
        boolean show = !text.isBlank();
        notesStatus.setVisible(show);
        notesStatus.setManaged(show);
    }

    private void onTick() {
        if (phase == Phase.ACTIVE) refreshDuration();
        refreshLocation();
    }

    private void refreshLocation() {
        StringBuilder text = new StringBuilder();
        if (!subtitle.isBlank()) {
            text.append(subtitle);
        }
        country.ifPresent(c -> {
            if (text.length() > 0) text.append("   ·   ");
            text.append(LocalTimeFormatter.describe(c, Instant.now()));
        });
        boolean show = text.length() > 0;
        locationLabel.setText(text.toString());
        locationLabel.setVisible(show);
        locationLabel.setManaged(show);
    }

    private String durationSuffix() {
        if (callStartedAt == null) return "";
        return " · " + CallDurationFormatter.format(
                java.time.Duration.between(callStartedAt, Instant.now()));
    }

    private void pressDigit(String digit) {
        dtmfReadout.setText(dtmfReadout.getText() + digit);
        tones.dtmf(digit);
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

    private void disableCallControls() {
        muteButton.setDisable(true);
        keypadButton.setDisable(true);
        holdButton.setDisable(true);
    }

    private void showRedial(boolean show) {
        redialButton.setVisible(show);
        redialButton.setManaged(show);
        redialButton.setDisable(!show);
    }

    private void setRingStyle(String activeClass) {
        avatarRing.getStyleClass().removeAll(
                "call-ring--ringing", "call-ring--active", "call-ring--hold",
                "call-ring--ended", "call-ring--failed");
        avatarRing.getStyleClass().add(activeClass);
    }

    private static void toggleStyle(Button button, String styleClass, boolean on) {
        if (on) {
            if (!button.getStyleClass().contains(styleClass)) button.getStyleClass().add(styleClass);
        } else {
            button.getStyleClass().remove(styleClass);
        }
    }

    private void refreshDuration() {
        if (callStartedAt == null) return;
        statusLabel.setText(CallDurationFormatter.format(
                java.time.Duration.between(callStartedAt, Instant.now())));
    }

    private void startPulse() {
        haloCircle.setVisible(true);
        pulseTimer.playFromStart();
    }

    private void stopPulse() {
        pulseTimer.stop();
        haloCircle.setVisible(false);
        haloCircle.setOpacity(0);
    }

    private void animateIn() {
        callingScreen.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(220), callingScreen);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }
}
