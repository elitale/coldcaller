package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.onboarding.ProviderOptions;
import com.elitale.coldbirds.coldcalling.domain.routing.CallRoutingConfig;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.services.CallRoutingService;
import com.elitale.coldbirds.coldcalling.services.PhoneNumberService;
import com.elitale.coldbirds.coldcalling.services.SettingsService;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDevice;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceManager;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceTester;
import com.elitale.coldbirds.coldcalling.telephony.rtp.VoicemailGreeting;
import com.elitale.coldbirds.coldcalling.ui.support.CallerIdLabel;
import com.elitale.coldbirds.coldcalling.ui.support.Motion;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Controller for settings-view.fxml.
 * <p>
 * All DB reads run off the FX Application Thread via {@link CompletableFuture};
 * UI updates are dispatched back via {@code Platform.runLater()}.
 * Save handlers run on the FX thread — SQLite upserts are fast enough to be inline.
 * <p>
 * Audio devices are enumerated through {@link AudioDeviceManager} (capability-filtered,
 * deduped, "System Default" first) and tested live through {@link AudioDeviceTester}.
 */
public final class SettingsController {

    // ── FXML-injected fields ──────────────────────────────────────────────────

    @FXML private Label            statusLabel;

    // Calling numbers
    @FXML private VBox             numberPoolBox;
    @FXML private Button           refreshNumbersButton;

    /** One checkbox per owned number; checked = active (in the rotation pool). */
    private final List<CheckBox> numberChecks = new ArrayList<>();

    // Twilio
    @FXML private TextField        twilioAccountSidField;
    @FXML private PasswordField    twilioAuthTokenField;

    // SIP
    @FXML private TextField        sipUsernameField;
    @FXML private PasswordField    sipPasswordField;
    @FXML private TextField        sipDomainField;
    @FXML private TextField        sipProxyField;
    @FXML private Spinner<Integer> sipProxyPortSpinner;

    // Call Routing
    @FXML private Label     routingCurrentLabel;
    @FXML private Button    routingAutoButton;
    @FXML private Button    routingVerifyButton;
    @FXML private TextField routingUrlField;
    @FXML private TextField routingCallerIdField;

    // Audio
    @FXML private ComboBox<AudioDevice> audioInputCombo;
    @FXML private ComboBox<AudioDevice> audioOutputCombo;
    @FXML private Button                inputTestButton;
    @FXML private ProgressBar           inputLevelBar;
    @FXML private Button                outputTestButton;
    @FXML private Spinner<Integer>      jitterBufferSpinner;

    // Power Dialer
    @FXML private Spinner<Integer> noAnswerTimeoutSpinner;
    @FXML private Spinner<Integer> autoAdvanceDelaySpinner;
    @FXML private CheckBox         voicemailDropCheckBox;
    @FXML private Label            greetingPathLabel;

    // Appearance
    @FXML private CheckBox         reduceMotionCheckBox;

    // ── Services ──────────────────────────────────────────────────────────────

    private SettingsService    settingsService;
    private PhoneNumberService phoneNumberService;
    private CallRoutingService callRoutingService;
    private AudioDeviceManager audioDeviceManager;
    private AudioDeviceTester  audioDeviceTester;
    private BiConsumer<String, String> onApplyAudioDevices;

    // ── Mic-test live state ───────────────────────────────────────────────────

    /** Handle to the running mic meter, or null when not testing. */
    private AutoCloseable    micMeterHandle;
    /** Drives the level bar from the polled {@link #micLevel}; null when not testing. */
    private AnimationTimer   levelTimer;
    /** Latest RMS level 0..1, written by the mic thread, read by the FX timer. */
    private volatile double  micLevel;

    /** Required no-arg constructor for FXMLLoader. */
    public SettingsController() {}

    public void setSettingsService(SettingsService service) {
        this.settingsService = Objects.requireNonNull(service, "settingsService must not be null");
    }

    public void setPhoneNumberService(PhoneNumberService service) {
        this.phoneNumberService = Objects.requireNonNull(service, "phoneNumberService must not be null");
    }

    public void setCallRoutingService(CallRoutingService service) {
        this.callRoutingService = Objects.requireNonNull(service, "callRoutingService must not be null");
    }

    public void setAudioDeviceManager(AudioDeviceManager manager) {
        this.audioDeviceManager = Objects.requireNonNull(manager, "audioDeviceManager must not be null");
    }

    public void setAudioDeviceTester(AudioDeviceTester tester) {
        this.audioDeviceTester = Objects.requireNonNull(tester, "audioDeviceTester must not be null");
    }

    public void setOnApplyAudioDevices(BiConsumer<String, String> apply) {
        this.onApplyAudioDevices = Objects.requireNonNull(apply, "onApplyAudioDevices must not be null");
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        sipProxyPortSpinner.setValueFactory(
                new IntegerSpinnerValueFactory(1, 65535, 5060, 1));
        jitterBufferSpinner.setValueFactory(
                new IntegerSpinnerValueFactory(20, 200, 40, 10));
        noAnswerTimeoutSpinner.setValueFactory(
                new IntegerSpinnerValueFactory(5, 120, 30, 5));
        autoAdvanceDelaySpinner.setValueFactory(
                new IntegerSpinnerValueFactory(0, 10, 1, 1));

        final StringConverter<AudioDevice> deviceNames = new StringConverter<>() {
            @Override public String toString(AudioDevice device) {
                return device == null ? "" : device.name();
            }
            @Override public AudioDevice fromString(String name) {
                return null;  // combos are not editable
            }
        };
        audioInputCombo.setConverter(deviceNames);
        audioOutputCombo.setConverter(deviceNames);
        inputLevelBar.setProgress(0);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Reload all settings fields from DB. Must be called on the FX Application Thread.
     * All I/O is dispatched to a background thread; fields are populated via runLater.
     */
    public void refresh() {
        stopMicTest();  // never leave a meter running across screen entries
        statusLabel.setText("Loading…");
        CompletableFuture.supplyAsync(this::loadAll)
                .thenAcceptAsync(this::applyAll, Platform::runLater)
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusLabel.setText("Error loading settings."));
                    return null;
                });
    }

    /** Stop any running mic test. Safe to call when leaving the screen or on shutdown. */
    public void dispose() {
        stopMicTest();
    }

    // ── FXML event handlers ───────────────────────────────────────────────────

    @FXML
    private void onSaveGeneral() {
        int active = 0;
        for (final CheckBox check : numberChecks) {
            final OwnedNumber number = (OwnedNumber) check.getUserData();
            phoneNumberService.setActive(number.id(), check.isSelected());
            if (check.isSelected()) active++;
        }
        showStatus(active == 0
                ? "Saved — no active calling numbers. Outbound calls need at least one."
                : "Saved — " + active + " number(s) in rotation.");
    }

    /**
     * Re-sync the user's phone numbers from Twilio, then reload the default-number
     * combo. Network and DB work run off the FX thread; the current selection is
     * preserved when it survives the refresh.
     */
    @FXML
    private void onRefreshNumbers() {
        refreshNumbersButton.setDisable(true);
        showStatus("Refreshing numbers\u2026");
        CompletableFuture.supplyAsync(this::fetchAndListNumbers)
                .thenAcceptAsync(this::applyRefreshedNumbers, Platform::runLater)
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        refreshNumbersButton.setDisable(false);
                        showStatus("Couldn\u2019t refresh numbers.");
                    });
                    return null;
                });
    }

    @FXML
    private void onSaveTwilio() {
        settingsService.setTwilioAccountSid(twilioAccountSidField.getText().strip());
        settingsService.setTwilioAuthToken(twilioAuthTokenField.getText().strip());
        showStatus("Twilio settings saved. Restart to apply.");
    }

    @FXML
    private void onSaveSip() {
        settingsService.setSipUsername(sipUsernameField.getText().strip());
        settingsService.setSipPassword(sipPasswordField.getText());
        settingsService.setSipDomain(sipDomainField.getText().strip());
        settingsService.setSipProxy(sipProxyField.getText().strip());
        settingsService.setSipProxyPort(sipProxyPortSpinner.getValue());
        showStatus("SIP settings saved. Restart to apply.");
    }

    /** Save the manual bridge URL and push it to the provider (Twilio: sets the SIP domain VoiceUrl). */
    @FXML
    private void onSaveRouting() {
        final String url = routingUrlField.getText() == null ? "" : routingUrlField.getText().strip();
        final String callerId = routingCallerIdField.getText() == null ? "" : routingCallerIdField.getText().strip();
        if (url.isBlank()) {
            showStatus("Enter a bridge URL, or use Auto-configure.");
            return;
        }
        showStatus("Applying routing\u2026");
        CompletableFuture
                .supplyAsync(() -> callRoutingService.applyManual(ProviderOptions.TWILIO_ID, url, callerId))
                .thenAcceptAsync(result -> {
                    if (result instanceof Result.Ok<CallRoutingConfig> ok) {
                        routingCurrentLabel.setText(describeRouting(ok.value()));
                        showStatus("Call routing saved.");
                    } else {
                        showStatus(routingError(result, "That bridge URL didn\u2019t work."));
                    }
                }, Platform::runLater)
                .exceptionally(ex -> {
                    Platform.runLater(() -> showStatus("Couldn\u2019t save routing."));
                    return null;
                });
    }

    /** Set the managed bridge URL on the provider automatically (Twilio only). */
    @FXML
    private void onAutoConfigureRouting() {
        routingAutoButton.setDisable(true);
        showStatus("Setting up call routing\u2026");
        CompletableFuture
                .supplyAsync(() -> callRoutingService.autoConfigure(ProviderOptions.TWILIO_ID))
                .thenAcceptAsync(result -> {
                    routingAutoButton.setDisable(false);
                    if (result instanceof Result.Ok<CallRoutingConfig> ok) {
                        routingUrlField.setText(ok.value().voiceUrl());
                        routingCurrentLabel.setText(describeRouting(ok.value()));
                        showStatus("Call routing is ready.");
                    } else {
                        showStatus(routingError(result, "Couldn\u2019t set up routing automatically."));
                    }
                }, Platform::runLater)
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        routingAutoButton.setDisable(false);
                        showStatus("Couldn\u2019t set up routing.");
                    });
                    return null;
                });
    }

    /** Read the live VoiceUrl back from Twilio so the user can confirm it took effect. */
    @FXML
    private void onVerifyRouting() {
        routingVerifyButton.setDisable(true);
        showStatus("Checking Twilio\u2026");
        CompletableFuture
                .supplyAsync(() -> callRoutingService.currentVoiceUrl(ProviderOptions.TWILIO_ID))
                .thenAcceptAsync(result -> {
                    routingVerifyButton.setDisable(false);
                    if (result instanceof Result.Ok<Optional<String>> ok) {
                        showStatus(ok.value()
                                .map(u -> "Live bridge: " + u)
                                .orElse("No bridge URL is set on Twilio yet."));
                    } else {
                        showStatus(routingError(result, "Couldn\u2019t read routing from Twilio."));
                    }
                }, Platform::runLater)
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        routingVerifyButton.setDisable(false);
                        showStatus("Couldn\u2019t reach Twilio.");
                    });
                    return null;
                });
    }

    @FXML
    private void onSaveAudio() {
        final String inId  = selectedId(audioInputCombo);
        final String outId = selectedId(audioOutputCombo);
        settingsService.setAudioInputDevice(inId);
        settingsService.setAudioOutputDevice(outId);
        settingsService.setJitterBufferMs(jitterBufferSpinner.getValue());
        onApplyAudioDevices.accept(inId, outId);  // live-apply to the next call, no restart
        showStatus("Audio settings saved.");
    }

    /** Toggle the live microphone level meter on the currently selected input device. */
    @FXML
    private void onToggleMicTest() {
        if (micMeterHandle != null) {
            stopMicTest();
            return;
        }
        try {
            micLevel = 0;
            micMeterHandle = audioDeviceTester.startMicMeter(
                    audioDeviceManager.resolveInput(selectedId(audioInputCombo)).orElse(null),
                    level -> micLevel = level);
            levelTimer = new AnimationTimer() {
                @Override public void handle(long now) {
                    inputLevelBar.setProgress(micLevel);
                }
            };
            levelTimer.start();
            inputTestButton.setText("Stop");
            showStatus("Listening — speak into your microphone…");
        } catch (Exception e) {
            stopMicTest();
            showStatus("Couldn't open microphone — try another device.");
        }
    }

    /** Play a short test tone on the currently selected output device. */
    @FXML
    private void onTestSpeaker() {
        try {
            audioDeviceTester.playTestTone(
                    audioDeviceManager.resolveOutput(selectedId(audioOutputCombo)).orElse(null));
            showStatus("Playing test sound…");
        } catch (Exception e) {
            showStatus("Couldn't open speaker — try another device.");
        }
    }

    @FXML
    private void onSavePowerDialer() {
        settingsService.setNoAnswerTimeoutSec(noAnswerTimeoutSpinner.getValue());
        settingsService.setAutoAdvanceDelaySec(autoAdvanceDelaySpinner.getValue());
        settingsService.setVoicemailDropEnabled(voicemailDropCheckBox.isSelected());
        showStatus("Power Dialer settings saved.");
    }

    /**
     * Pick a WAV greeting, validate its telephony format off the FX thread, and copy it
     * into {@code ~/.coldcalling/} so the drop path always has a stable, format-checked
     * source. Applied immediately (no Save button) since the copy is the commit.
     */
    @FXML
    private void onChooseGreeting() {
        final FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose voicemail greeting");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV audio", "*.wav"));
        final Window owner = greetingPathLabel.getScene() == null
                ? null : greetingPathLabel.getScene().getWindow();
        final File picked = chooser.showOpenDialog(owner);
        if (picked == null) {
            return;
        }
        showStatus("Validating greeting\u2026");
        CompletableFuture.supplyAsync(() -> copyValidatedGreeting(picked.toPath()))
                .thenAcceptAsync(result -> {
                    switch (result) {
                        case Result.Ok<String> ok -> {
                            greetingPathLabel.setText(greetingDisplayName(ok.value()));
                            showStatus("Voicemail greeting saved.");
                        }
                        case Result.Err<String> err -> showStatus(err.message());
                    }
                }, Platform::runLater);
    }

    /** Forget the configured greeting. The drop control then no-ops until one is chosen. */
    @FXML
    private void onClearGreeting() {
        settingsService.setVoicemailGreetingPath("");
        greetingPathLabel.setText("None selected");
        showStatus("Voicemail greeting cleared.");
    }

    /** Runs on a background thread — validates format, then copies into the app data dir. */
    private Result<String> copyValidatedGreeting(Path source) {
        try {
            VoicemailGreeting.load(source);  // throws on a non-telephony format
        } catch (UnsupportedAudioFileException | IllegalArgumentException badFormat) {
            return Result.err("Greeting must be an 8 kHz mono 16-bit WAV.");
        } catch (IOException io) {
            return Result.err("Couldn't read that file.");
        }
        try {
            final Path dir = Path.of(System.getProperty("user.home"), ".coldcalling");
            Files.createDirectories(dir);
            final Path dest = dir.resolve("voicemail-greeting.wav");
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            settingsService.setVoicemailGreetingPath(dest.toString());
            return Result.ok(dest.toString());
        } catch (IOException io) {
            return Result.err("Couldn't save the greeting.");
        }
    }

    private static String greetingDisplayName(String path) {
        if (path == null || path.isBlank()) {
            return "None selected";
        }
        return Path.of(path).getFileName().toString();
    }

    /** Persist and apply reduce-motion immediately so the effect is visible at once. */
    @FXML
    private void onReduceMotionToggled() {
        final boolean reduce = reduceMotionCheckBox.isSelected();
        settingsService.setReduceMotion(reduce);
        Motion.setReduced(reduce);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private record AllSettings(
            List<OwnedNumber> ownedNumbers,
            String twilioAccountSid,    String twilioAuthToken,
            String sipUsername,         String sipPassword,
            String sipDomain,           String sipProxy,    int sipProxyPort,
            CallRoutingConfig routing,
            List<AudioDevice> inputDevices, List<AudioDevice> outputDevices,
            String audioInput,          String audioOutput, int jitterMs,
            int noAnswerSec,            int advanceDelaySec, boolean voicemailDrop,
            String greetingPath,        boolean reduceMotion) {}

    /** Runs on a background thread — no UI access here. */
    private AllSettings loadAll() {
        return new AllSettings(
                phoneNumberService.listAll(),
                settingsService.getTwilioAccountSid(), settingsService.getTwilioAuthToken(),
                settingsService.getSipUsername(),    settingsService.getSipPassword(),
                settingsService.getSipDomain(),      settingsService.getSipProxy(),
                settingsService.getSipProxyPort(),
                callRoutingService.load(),
                audioDeviceManager.inputDevices(),   audioDeviceManager.outputDevices(),
                settingsService.getAudioInputDevice(), settingsService.getAudioOutputDevice(),
                settingsService.getJitterBufferMs(),
                settingsService.getNoAnswerTimeoutSec(), settingsService.getAutoAdvanceDelaySec(),
                settingsService.isVoicemailDropEnabled(),
                settingsService.getVoicemailGreetingPath(),
                settingsService.isReduceMotion());
    }

    /** Runs on the FX Application Thread — safe to update UI fields here. */
    private void applyAll(AllSettings s) {
        populateNumberPool(s.ownedNumbers());

        twilioAccountSidField.setText(s.twilioAccountSid());
        twilioAuthTokenField.setText(s.twilioAuthToken());

        sipUsernameField.setText(s.sipUsername());
        sipPasswordField.setText(s.sipPassword());
        sipDomainField.setText(s.sipDomain());
        sipProxyField.setText(s.sipProxy());
        sipProxyPortSpinner.getValueFactory().setValue(s.sipProxyPort());

        routingUrlField.setText(s.routing().voiceUrl());
        routingCallerIdField.setText(s.routing().callerIdFallback());
        routingCurrentLabel.setText(describeRouting(s.routing()));

        populateDevices(audioInputCombo,  s.inputDevices(),  s.audioInput());
        populateDevices(audioOutputCombo, s.outputDevices(), s.audioOutput());
        jitterBufferSpinner.getValueFactory().setValue(s.jitterMs());

        noAnswerTimeoutSpinner.getValueFactory().setValue(s.noAnswerSec());
        autoAdvanceDelaySpinner.getValueFactory().setValue(s.advanceDelaySec());
        voicemailDropCheckBox.setSelected(s.voicemailDrop());
        greetingPathLabel.setText(greetingDisplayName(s.greetingPath()));
        reduceMotionCheckBox.setSelected(s.reduceMotion());

        statusLabel.setText("");
    }

    /**
     * Fill a device combo and select the persisted id, falling back to "System Default"
     * (the first item) when the id is blank or no longer present.
     */
    private void populateDevices(ComboBox<AudioDevice> combo, List<AudioDevice> devices, String savedId) {
        combo.setItems(FXCollections.observableArrayList(devices));
        combo.getItems().stream()
                .filter(d -> d.id().equals(savedId))
                .findFirst()
                .ifPresentOrElse(
                        combo::setValue,
                        () -> combo.getItems().stream().findFirst().ifPresent(combo::setValue));
    }

    private record NumbersRefresh(String message, List<OwnedNumber> numbers) {}

    /** Background thread: pull numbers from Twilio, then read back the owned list. */
    private NumbersRefresh fetchAndListNumbers() {
        final String message = switch (phoneNumberService.fetchAndSync()) {
            case Result.Ok<Integer> ok -> ok.value() == 0
                    ? "Numbers up to date."
                    : ok.value() + " new number(s) added.";
            case Result.Err<?> err -> "Couldn\u2019t refresh numbers \u2014 check Twilio settings.";
        };
        return new NumbersRefresh(message, phoneNumberService.listAll());
    }

    /** FX thread: rebuild the number pool from storage (active flags are authoritative). */
    private void applyRefreshedNumbers(NumbersRefresh r) {
        refreshNumbersButton.setDisable(false);
        populateNumberPool(r.numbers());
        showStatus(r.message());
    }

    /** Rebuild the calling-number checkbox list; checked = active (in rotation). */
    private void populateNumberPool(List<OwnedNumber> numbers) {
        numberChecks.clear();
        numberPoolBox.getChildren().clear();
        if (numbers.isEmpty()) {
            final Label empty = new Label("No numbers yet \u2014 add one in Twilio, then Refresh.");
            empty.getStyleClass().add("caption");
            numberPoolBox.getChildren().add(empty);
            return;
        }
        for (final OwnedNumber number : numbers) {
            final CheckBox check = new CheckBox(CallerIdLabel.describe(number));
            check.setSelected(number.active());
            check.setUserData(number);
            numberChecks.add(check);
            numberPoolBox.getChildren().add(check);
        }
    }

    /** Selected device id, or "" (system default) when nothing is selected. */
    private static String selectedId(ComboBox<AudioDevice> combo) {
        final AudioDevice device = combo.getValue();
        return device == null ? AudioDevice.SYSTEM_DEFAULT_ID : device.id();
    }

    /** Human-readable summary of the current routing state for the status label. */
    private static String describeRouting(CallRoutingConfig config) {
        return switch (config.mode()) {
            case NONE   -> "Off — outbound calls won\u2019t connect to the phone network.";
            case AUTO   -> "Managed bridge \u00b7 " + config.voiceUrl();
            case MANUAL -> "Custom bridge \u00b7 " + config.voiceUrl();
        };
    }

    /** Prefer the service's error message, falling back to a friendly default. */
    private static String routingError(Result<?> result, String fallback) {
        return result instanceof Result.Err<?> err && err.message() != null && !err.message().isBlank()
                ? err.message()
                : fallback;
    }

    private void stopMicTest() {
        if (levelTimer != null) {
            levelTimer.stop();
            levelTimer = null;
        }
        if (micMeterHandle != null) {
            try {
                micMeterHandle.close();
            } catch (Exception ignored) {
                // best-effort — the line is being torn down regardless
            }
            micMeterHandle = null;
        }
        micLevel = 0;
        if (inputLevelBar != null) inputLevelBar.setProgress(0);
        if (inputTestButton != null) inputTestButton.setText("Test");
    }

    private void showStatus(String message) {
        statusLabel.setText(message);
    }
}
