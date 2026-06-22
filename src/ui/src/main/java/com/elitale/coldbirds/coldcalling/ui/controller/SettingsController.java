package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.PhoneNumberService;
import com.elitale.coldbirds.coldcalling.services.SettingsService;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDevice;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceManager;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceTester;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Objects;
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

    // General
    @FXML private ComboBox<String> defaultNumberCombo;

    // Twilio
    @FXML private TextField        twilioAccountSidField;
    @FXML private PasswordField    twilioAuthTokenField;

    // SIP
    @FXML private TextField        sipUsernameField;
    @FXML private PasswordField    sipPasswordField;
    @FXML private TextField        sipDomainField;
    @FXML private TextField        sipProxyField;
    @FXML private Spinner<Integer> sipProxyPortSpinner;

    // SMS Relay
    @FXML private TextField        smsRelayUrlField;
    @FXML private PasswordField    smsRelayKeyField;

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

    // Appearance
    @FXML private ToggleGroup      themeGroup;

    // ── Services ──────────────────────────────────────────────────────────────

    private SettingsService    settingsService;
    private PhoneNumberService phoneNumberService;
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
        final String sel = defaultNumberCombo.getValue();
        if (sel != null && !sel.isBlank()) {
            phoneNumberService.setDefault(new PhoneNumber(sel));
        }
        showStatus("General settings saved.");
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

    @FXML
    private void onSaveSmsRelay() {
        settingsService.setSmsRelayUrl(smsRelayUrlField.getText().strip());
        settingsService.setSmsRelayKey(smsRelayKeyField.getText().strip());
        showStatus("SMS Relay settings saved. Restart to apply.");
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

    @FXML
    private void onSaveAppearance() {
        if (themeGroup.getSelectedToggle() != null) {
            settingsService.setTheme((String) themeGroup.getSelectedToggle().getUserData());
        }
        showStatus("Appearance settings saved.");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private record AllSettings(
            List<String> ownedNumbers,  String defaultNumber,
            String twilioAccountSid,    String twilioAuthToken,
            String sipUsername,         String sipPassword,
            String sipDomain,           String sipProxy,    int sipProxyPort,
            String smsRelayUrl,         String smsRelayKey,
            List<AudioDevice> inputDevices, List<AudioDevice> outputDevices,
            String audioInput,          String audioOutput, int jitterMs,
            int noAnswerSec,            int advanceDelaySec, boolean voicemailDrop,
            String theme) {}

    /** Runs on a background thread — no UI access here. */
    private AllSettings loadAll() {
        final List<String> numbers = phoneNumberService.listOwned()
                .stream().map(n -> n.number().value()).toList();
        final String defNum = phoneNumberService.getDefault()
                .map(n -> n.number().value()).orElse("");
        return new AllSettings(
                numbers, defNum,
                settingsService.getTwilioAccountSid(), settingsService.getTwilioAuthToken(),
                settingsService.getSipUsername(),    settingsService.getSipPassword(),
                settingsService.getSipDomain(),      settingsService.getSipProxy(),
                settingsService.getSipProxyPort(),
                settingsService.getSmsRelayUrl(),    settingsService.getSmsRelayKey(),
                audioDeviceManager.inputDevices(),   audioDeviceManager.outputDevices(),
                settingsService.getAudioInputDevice(), settingsService.getAudioOutputDevice(),
                settingsService.getJitterBufferMs(),
                settingsService.getNoAnswerTimeoutSec(), settingsService.getAutoAdvanceDelaySec(),
                settingsService.isVoicemailDropEnabled(),
                settingsService.getTheme());
    }

    /** Runs on the FX Application Thread — safe to update UI fields here. */
    private void applyAll(AllSettings s) {
        defaultNumberCombo.setItems(FXCollections.observableArrayList(s.ownedNumbers()));
        if (!s.defaultNumber().isBlank()) defaultNumberCombo.setValue(s.defaultNumber());

        twilioAccountSidField.setText(s.twilioAccountSid());
        twilioAuthTokenField.setText(s.twilioAuthToken());

        sipUsernameField.setText(s.sipUsername());
        sipPasswordField.setText(s.sipPassword());
        sipDomainField.setText(s.sipDomain());
        sipProxyField.setText(s.sipProxy());
        sipProxyPortSpinner.getValueFactory().setValue(s.sipProxyPort());

        smsRelayUrlField.setText(s.smsRelayUrl());
        smsRelayKeyField.setText(s.smsRelayKey());

        populateDevices(audioInputCombo,  s.inputDevices(),  s.audioInput());
        populateDevices(audioOutputCombo, s.outputDevices(), s.audioOutput());
        jitterBufferSpinner.getValueFactory().setValue(s.jitterMs());

        noAnswerTimeoutSpinner.getValueFactory().setValue(s.noAnswerSec());
        autoAdvanceDelaySpinner.getValueFactory().setValue(s.advanceDelaySec());
        voicemailDropCheckBox.setSelected(s.voicemailDrop());

        themeGroup.getToggles().stream()
                .filter(t -> s.theme().equals(t.getUserData()))
                .findFirst()
                .ifPresent(themeGroup::selectToggle);

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

    /** Selected device id, or "" (system default) when nothing is selected. */
    private static String selectedId(ComboBox<AudioDevice> combo) {
        final AudioDevice device = combo.getValue();
        return device == null ? AudioDevice.SYSTEM_DEFAULT_ID : device.id();
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
