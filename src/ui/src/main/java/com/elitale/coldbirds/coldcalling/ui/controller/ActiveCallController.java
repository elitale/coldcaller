package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDevice;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceManager;
import com.elitale.coldbirds.coldcalling.ui.support.AudioWaveform;
import com.elitale.coldbirds.coldcalling.ui.support.CallDurationFormatter;
import com.elitale.coldbirds.coldcalling.ui.support.CallParticipant;
import com.elitale.coldbirds.coldcalling.ui.support.CallTones;
import com.elitale.coldbirds.coldcalling.ui.support.DispositionCatalog;
import com.elitale.coldbirds.coldcalling.ui.support.LocalTimeFormatter;
import com.elitale.coldbirds.coldcalling.ui.support.Motion;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

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

    private static final double RIPPLE_THRESHOLD = 0.16;
    private static final double RIPPLE_SECONDS   = 0.75;
    private static final double REC_PULSE_SECONDS = 1.4;
    private static final Color  MIC_ON_COLOR  = Color.web("#34C759"); // success green
    private static final Color  MIC_OFF_COLOR = Color.web("#98989D"); // muted / idle grey

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
    @FXML private Button    outputButton;
    @FXML private Button    voicemailButton;
    @FXML private Button    hangUpButton;
    @FXML private Button    redialButton;
    @FXML private VBox      keypadOverlay;
    @FXML private Label     dtmfReadout;
    @FXML private GridPane  keypadGrid;
    @FXML private FlowPane  dispositionChips;
    @FXML private TextArea  notesArea;
    @FXML private Label     notesStatus;
    @FXML private Region    rippleCircle;
    @FXML private HBox      recChip;
    @FXML private HBox      micMeterRow;
    @FXML private Label     micStateIcon;
    @FXML private AudioWaveform micMeter;

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

    private AnimationTimer levelTimer;
    private DoubleSupplier micLevelSupplier    = () -> 0.0;
    private DoubleSupplier remoteLevelSupplier = () -> 0.0;
    private double   remoteSmoothed = 0.0;
    private double   micSmoothed    = 0.0;
    private double   ripplePhase    = 1.0; // ≥1 = idle
    private long     lastFrameNanos = 0L;
    private BooleanSupplier recordingSupplier = () -> false;
    private boolean  recVisible     = false;
    private double   recPhase       = 0.0;

    private AudioDeviceManager audioDeviceManager;
    private BiConsumer<String, String> onSwitchAudioDevices = (in, out) -> {};
    private Supplier<String> currentInputId  = () -> "";
    private Supplier<String> currentOutputId = () -> "";
    private String selectedInputId  = "";
    private String selectedOutputId = "";

    // Callbacks
    private Runnable          onHangUpCb = () -> {};
    private Runnable          onRedialCb = () -> {};
    private Consumer<Boolean> onMuteCb   = ignored -> {};
    private Consumer<Boolean> onHoldCb   = ignored -> {};
    private Consumer<String>  onDtmfCb   = ignored -> {};
    private BiConsumer<Optional<CallDisposition>, String> onLogChangedCb = (d, n) -> {};
    private Runnable          onDispositionChosenCb = () -> {};
    private Supplier<Optional<java.time.Duration>> onVoicemailDropCb = Optional::empty;
    private Runnable          onVoicemailCompletedCb = () -> {};
    private boolean           voicemailDropping = false;

    /** Default no-arg constructor — required by FXMLLoader. */
    public ActiveCallController() {}

    public void setOnHangUp(Runnable cb)        { this.onHangUpCb = Objects.requireNonNull(cb); }
    public void setOnRedial(Runnable cb)        { this.onRedialCb = Objects.requireNonNull(cb); }
    public void setOnMute(Consumer<Boolean> cb) { this.onMuteCb   = Objects.requireNonNull(cb); }
    public void setOnHold(Consumer<Boolean> cb) { this.onHoldCb   = Objects.requireNonNull(cb); }
    public void setOnDtmf(Consumer<String> cb)  { this.onDtmfCb   = Objects.requireNonNull(cb); }

    /** @return whether the mic is currently muted on the call card. */
    public boolean isMuted() { return muted; }

    /** Toggle mute from another surface (the Mini Call HUD); keeps the call card in sync. */
    public void toggleMute() { onMuteToggle(); }

    /** Trigger the current hang-up action programmatically (e.g. from the Mini Call HUD). */
    public void triggerHangUp() { onHangUp(); }

    /**
     * Inject the live audio-level sources that drive the avatar halo (remote
     * voice) and the mic waveform. Polled ~60fps by an AnimationTimer while the
     * call is connected; suppliers must be cheap and non-blocking.
     *
     * @param micLevel    supplies the normalized mic level (0..1)
     * @param remoteLevel supplies the normalized remote-party level (0..1)
     */
    public void setAudioLevels(DoubleSupplier micLevel, DoubleSupplier remoteLevel) {
        this.micLevelSupplier    = Objects.requireNonNull(micLevel,    "micLevel must not be null");
        this.remoteLevelSupplier = Objects.requireNonNull(remoteLevel, "remoteLevel must not be null");
    }

    /**
     * Wire the in-call audio-device menu (the ••• button). Devices are listed from
     * {@code manager}; picking one applies it live via {@code onSwitch} as
     * (inputId, outputId) and the caller persists it. The current ids seed the ticks.
     */
    public void setAudioDevices(AudioDeviceManager manager,
                                BiConsumer<String, String> onSwitch,
                                Supplier<String> currentInputId,
                                Supplier<String> currentOutputId) {
        this.audioDeviceManager   = Objects.requireNonNull(manager, "manager must not be null");
        this.onSwitchAudioDevices = Objects.requireNonNull(onSwitch, "onSwitch must not be null");
        this.currentInputId       = Objects.requireNonNull(currentInputId, "currentInputId must not be null");
        this.currentOutputId      = Objects.requireNonNull(currentOutputId, "currentOutputId must not be null");
    }

    /**
     * Register the auto-save sink. Fired (debounced for notes, immediately for a
     * disposition pick) with the current disposition + notes whenever the rep
     * edits the call log, so a record is never lost if they forget to save.
     */
    public void setOnLogChanged(BiConsumer<Optional<CallDisposition>, String> cb) {
        this.onLogChangedCb = Objects.requireNonNull(cb);
    }

    /**
     * Register the "disposition chosen during wrap-up" sink. Fired exactly once when the
     * rep picks a disposition chip while the call is in its wrap-up phase — the power dialer
     * uses this to auto-advance to the next contact. Distinct from {@link #setOnLogChanged}
     * (which also fires on every note keystroke) so advancing is driven only by a deliberate
     * disposition pick, never by typing notes or by an active (not-yet-ended) call.
     */
    public void setOnDispositionChosen(Runnable cb) {
        this.onDispositionChosenCb = Objects.requireNonNull(cb);
    }

    /**
     * Register the voicemail-drop action. Invoked when the rep clicks the Voicemail
     * control or presses {@code V} on a connected call. The supplier performs the
     * drop and returns the greeting's playback duration, or empty when the drop was
     * skipped (disabled, no greeting configured, or no active call). When a duration
     * is returned the control shows a determinate ring for that long, then settles.
     */
    public void setOnVoicemailDrop(Supplier<Optional<java.time.Duration>> cb) {
        this.onVoicemailDropCb = Objects.requireNonNull(cb);
    }

    /**
     * Register the sink fired once a dropped greeting has finished playing. The power
     * dialer uses this to end the current call and advance to the next contact, making
     * voicemail → drop → advance a single hands-free flow. No-op for manual calls.
     */
    public void setOnVoicemailCompleted(Runnable cb) {
        this.onVoicemailCompletedCb = Objects.requireNonNull(cb);
    }

    /**
     * Inject the live recording-state source for the in-call REC indicator. Polled ~60fps
     * by the level timer while connected; the chip shows (and pulses, unless reduce-motion
     * is on) whenever the supplier reports {@code true}.
     *
     * @param recording supplies whether the active call is currently being recorded
     */
    public void setRecordingState(BooleanSupplier recording) {
        this.recordingSupplier = Objects.requireNonNull(recording, "recording must not be null");
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        applyIcon(muteButton,      "bi-mic",              "Mute");
        applyIcon(keypadButton,    "bi-grid-3x3-gap",     "Keypad");
        applyIcon(holdButton,      "bi-pause-fill",       "Hold");
        applyIcon(outputButton,    "bi-volume-up-fill",   "Speaker");
        applyIcon(voicemailButton, "bi-soundwave",        "Voicemail");
        applyIcon(hangUpButton,    "bi-telephone-x-fill", "Hang Up");
        applyIcon(redialButton,    "bi-telephone-outbound-fill", "Redial");
        applyIcon(closeButton,     "bi-sliders",          null);

        outputButton.setTooltip(new Tooltip("Switch audio output"));

        voicemailButton.setDisable(true);
        voicemailButton.setTooltip(new Tooltip("Drop a pre-recorded voicemail (V)"));
        voicemailButton.setOnAction(e -> onVoicemailDrop());
        redialButton.setTooltip(new Tooltip("Call this number again"));
        showRedial(false);
        closeButton.setTooltip(new Tooltip("Audio devices"));
        closeButton.setOnAction(e -> showAudioMenu());
        closeButton.setVisible(false);
        closeButton.setManaged(false);

        buildKeypad();
        buildDispositionChips();

        tickTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> onTick()));
        tickTimer.setCycleCount(Timeline.INDEFINITE);
        pulseTimer = buildPulse();
        levelTimer = buildLevelTimer();

        FontIcon micGlyph = new FontIcon("bi-mic");
        micGlyph.setIconSize(15);
        micGlyph.setIconColor(MIC_OFF_COLOR);
        micStateIcon.setGraphic(micGlyph);

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
            // A deliberate disposition pick during wrap-up advances the power dialer.
            if (sel != null && phase == Phase.WRAPUP) {
                onDispositionChosenCb.run();
            }
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

    /**
     * ~60fps timer that polls the live audio levels while connected and drives
     * the adaptive avatar halo (remote voice), loud-speech ripples, and the mic
     * waveform. Runs only between {@link #startLevelMeter()} and
     * {@link #stopLevelMeter()}.
     */
    private AnimationTimer buildLevelTimer() {
        return new AnimationTimer() {
            @Override public void handle(long now) {
                if (lastFrameNanos == 0L) { lastFrameNanos = now; return; }
                double dt = Math.min(0.05, (now - lastFrameNanos) / 1_000_000_000.0);
                lastFrameNanos = now;
                onLevelFrame(dt);
            }
        };
    }

    private void onLevelFrame(double dt) {
        // Remote voice → adaptive halo: calm when quiet, energetic + ripples when loud.
        double rawRemote = clamp01(remoteLevelSupplier.getAsDouble());
        double attack = rawRemote > remoteSmoothed ? 0.55 : 0.10; // fast rise, slow fall
        remoteSmoothed += (rawRemote - remoteSmoothed) * attack;
        double haloScale = 0.9 + remoteSmoothed * 1.05;
        haloCircle.setVisible(true);
        haloCircle.setScaleX(haloScale);
        haloCircle.setScaleY(haloScale);
        haloCircle.setOpacity(Math.min(0.62, remoteSmoothed * 1.5));

        if (ripplePhase >= 1.0 && rawRemote >= RIPPLE_THRESHOLD) {
            ripplePhase = 0.0;
            rippleCircle.setVisible(true);
        }
        if (ripplePhase < 1.0) {
            ripplePhase = Math.min(1.0, ripplePhase + dt / RIPPLE_SECONDS);
            double rippleScale = 0.9 + ripplePhase * 1.25;
            rippleCircle.setScaleX(rippleScale);
            rippleCircle.setScaleY(rippleScale);
            rippleCircle.setOpacity((1.0 - ripplePhase) * 0.5);
            if (ripplePhase >= 1.0) rippleCircle.setVisible(false);
        }

        // Mic level → waveform (flat when muted).
        double rawMic = muted ? 0.0 : clamp01(micLevelSupplier.getAsDouble());
        double micK = rawMic > micSmoothed ? 0.6 : 0.25;
        micSmoothed += (rawMic - micSmoothed) * micK;
        micMeter.push(micSmoothed);

        updateRecIndicator(dt);
    }

    /** Show / hide the REC chip from the live recording state, pulsing it unless reduce-motion. */
    private void updateRecIndicator(double dt) {
        boolean recording = recordingSupplier.getAsBoolean();
        if (recording != recVisible) {
            recVisible = recording;
            recPhase = 0.0;
            recChip.setVisible(recording);
            recChip.setManaged(recording);
        }
        if (!recording) return;
        if (Motion.isReduced()) {
            recChip.setOpacity(1.0);
        } else {
            recPhase += dt / REC_PULSE_SECONDS;
            recChip.setOpacity(0.55 + 0.45 * (0.5 + 0.5 * Math.sin(recPhase * 2.0 * Math.PI)));
        }
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private void startLevelMeter() {
        remoteSmoothed = 0.0;
        micSmoothed    = 0.0;
        ripplePhase    = 1.0;
        lastFrameNanos = 0L;
        recVisible     = false;
        recPhase       = 0.0;
        selectedInputId  = currentInputId.get();
        selectedOutputId = currentOutputId.get();
        updateOutputLabel();
        closeButton.setVisible(true);
        closeButton.setManaged(true);
        micMeter.clear();
        micMeterRow.setVisible(true);
        micMeterRow.setManaged(true);
        updateMicIndicator();
        levelTimer.start();
    }

    private void stopLevelMeter() {
        levelTimer.stop();
        haloCircle.setScaleX(1.0);
        haloCircle.setScaleY(1.0);
        haloCircle.setOpacity(0.0);
        haloCircle.setVisible(false);
        rippleCircle.setVisible(false);
        rippleCircle.setOpacity(0.0);
        recVisible = false;
        recChip.setVisible(false);
        recChip.setManaged(false);
        micMeter.clear();
        micMeterRow.setVisible(false);
        micMeterRow.setManaged(false);
        closeButton.setVisible(false);
        closeButton.setManaged(false);
    }

    private void updateMicIndicator() {
        FontIcon glyph = (FontIcon) micStateIcon.getGraphic();
        if (glyph == null) return;
        glyph.setIconLiteral(muted ? "bi-mic-mute-fill" : "bi-mic");
        glyph.setIconColor(muted ? MIC_OFF_COLOR : MIC_ON_COLOR);
    }

    /** One-shot "connect" pop on the avatar the moment the line goes live. No-op under reduce-motion. */
    private void playConnectBloom() {
        if (Motion.isReduced()) return;
        avatarRing.setScaleX(0.9);
        avatarRing.setScaleY(0.9);
        Timeline bloom = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(avatarRing.scaleXProperty(), 0.9),
                        new KeyValue(avatarRing.scaleYProperty(), 0.9)),
                new KeyFrame(Duration.millis(220),
                        new KeyValue(avatarRing.scaleXProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(avatarRing.scaleYProperty(), 1.0, Interpolator.EASE_OUT)));
        bloom.play();
    }

    private void showAudioMenu() {
        if (audioDeviceManager == null) return;
        ContextMenu menu = new ContextMenu();
        menu.getItems().add(buildDeviceMenu("Microphone", audioDeviceManager.inputDevices(), true));
        menu.getItems().add(buildDeviceMenu("Speaker", audioDeviceManager.outputDevices(), false));
        menu.show(closeButton, Side.BOTTOM, 0, 4);
    }

    private Menu buildDeviceMenu(String title, List<AudioDevice> devices, boolean input) {
        Menu menu = new Menu(title);
        String selected = input ? selectedInputId : selectedOutputId;
        ToggleGroup group = new ToggleGroup();
        for (AudioDevice device : devices) {
            RadioMenuItem item = new RadioMenuItem(device.name());
            item.setToggleGroup(group);
            item.setSelected(device.id().equals(selected));
            item.setOnAction(e -> chooseDevice(input, device.id()));
            menu.getItems().add(item);
        }
        return menu;
    }

    private void chooseDevice(boolean input, String id) {
        if (input) {
            selectedInputId = id;
        } else {
            selectedOutputId = id;
        }
        onSwitchAudioDevices.accept(selectedInputId, selectedOutputId);
    }

    /**
     * Primary speaker/headset control: round-robins to the next output device and applies it
     * through the shipped switch path. The ••• menu remains the full mic+speaker picker.
     */
    @FXML
    private void onCycleOutput() {
        Motion.pressFlash(outputButton);
        if (audioDeviceManager == null) {
            return;
        }
        final List<AudioDevice> outputs = audioDeviceManager.outputDevices();
        if (outputs.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = 0; i < outputs.size(); i++) {
            if (outputs.get(i).id().equals(selectedOutputId)) {
                index = i;
                break;
            }
        }
        final AudioDevice next = outputs.get((index + 1) % outputs.size());
        selectedOutputId = next.id();
        onSwitchAudioDevices.accept(selectedInputId, selectedOutputId);
        updateOutputLabel();
    }

    /** Surface which output is active on the speaker control's tooltip. */
    private void updateOutputLabel() {
        if (audioDeviceManager == null) {
            return;
        }
        final String name = audioDeviceManager.outputDevices().stream()
                .filter(device -> device.id().equals(selectedOutputId))
                .map(AudioDevice::name)
                .findFirst()
                .orElse("Speaker");
        outputButton.setTooltip(new Tooltip(name));
    }

    private void buildKeypad() {
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            Button btn = new Button(key);
            btn.getStyleClass().add("keypad-key");
            btn.setOnAction(e -> { Motion.pressFlash(btn); pressDigit(key); });
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
            startLevelMeter();
            playConnectBloom();
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
            stopLevelMeter();
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
            stopLevelMeter();
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
            stopLevelMeter();
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
            case V -> { event.consume(); onVoicemailDrop(); }
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
    /**
     * Drop the configured greeting into the live call. Acts only on a connected
     * (Active) call and ignores re-entrant clicks while a drop is in flight. An
     * empty result from the drop action (disabled / no greeting / no call) just
     * flashes the control; otherwise the button shows a determinate ring for the
     * greeting's length, then settles dimmed and fires the completion sink.
     */
    private void onVoicemailDrop() {
        if (voicemailDropping || phase != Phase.ACTIVE) return;
        final Optional<java.time.Duration> playing = onVoicemailDropCb.get();
        if (playing.isEmpty()) {
            Motion.pressFlash(voicemailButton);
            return;
        }
        playVoicemailAffordance(playing.get());
    }

    private void playVoicemailAffordance(final java.time.Duration playing) {
        voicemailDropping = true;
        voicemailButton.setDisable(true);
        voicemailButton.setText("Dropping\u2026");
        final long millis = Math.max(1L, playing.toMillis());

        if (Motion.isReduced()) {
            // No determinate ring under reduced motion — hold the state for the
            // greeting's length, then settle.
            final PauseTransition wait = new PauseTransition(Duration.millis(millis));
            wait.setOnFinished(e -> finishVoicemailDrop());
            wait.play();
            return;
        }

        final ProgressIndicator ring = new ProgressIndicator(0);
        ring.setPrefSize(20, 20);
        ring.setMaxSize(20, 20);
        voicemailButton.setGraphic(ring);
        final Timeline ringTimer = new Timeline(
                new KeyFrame(Duration.ZERO,           new KeyValue(ring.progressProperty(), 0)),
                new KeyFrame(Duration.millis(millis), new KeyValue(ring.progressProperty(), 1)));
        ringTimer.setOnFinished(e -> finishVoicemailDrop());
        ringTimer.play();
    }

    private void finishVoicemailDrop() {
        voicemailDropping = false;
        restoreVoicemailIcon();
        voicemailButton.setText("Voicemail");
        voicemailButton.setOpacity(0.5); // settled: greeting dropped
        onVoicemailCompletedCb.run();
    }

    private void restoreVoicemailIcon() {
        final FontIcon icon = new FontIcon("bi-soundwave");
        icon.setIconSize(20);
        voicemailButton.setGraphic(icon);
    }
    @FXML
    private void onMuteToggle() {
        muted = !muted;
        ((FontIcon) muteButton.getGraphic()).setIconLiteral(muted ? "bi-mic-mute-fill" : "bi-mic");
        muteButton.setText(muted ? "Unmute" : "Mute");
        toggleStyle(muteButton, "call-control--muted", muted);
        updateMicIndicator();
        onMuteCb.accept(muted);
    }

    @FXML
    private void onHoldToggle() {
        boolean held = phase != Phase.HOLD;
        phase = held ? Phase.HOLD : Phase.ACTIVE;
        ((FontIcon) holdButton.getGraphic()).setIconLiteral(held ? "bi-play-fill" : "bi-pause-fill");
        holdButton.setText(held ? "Resume" : "Hold");
        toggleStyle(holdButton, "call-control--hold", held);
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
        stopLevelMeter();
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
        muteButton.getStyleClass().remove("call-control--muted");
        holdButton.setText("Hold");
        holdButton.getStyleClass().remove("call-control--hold");
        ((FontIcon) holdButton.getGraphic()).setIconLiteral("bi-pause-fill");
        voicemailDropping = false;
        restoreVoicemailIcon();
        voicemailButton.setText("Voicemail");
        voicemailButton.setOpacity(1.0);
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
        outputButton.setDisable(disabled);
        voicemailButton.setDisable(disabled || voicemailDropping);
    }

    private void disableCallControls() {
        muteButton.setDisable(true);
        keypadButton.setDisable(true);
        holdButton.setDisable(true);
        outputButton.setDisable(true);
        voicemailButton.setDisable(true);
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
