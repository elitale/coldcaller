package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.PhoneNumberService;
import com.elitale.coldbirds.coldcalling.services.SettingsService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for settings-view.fxml.
 * <p>
 * All DB reads run off the FX Application Thread via {@link CompletableFuture};
 * UI updates are dispatched back via {@code Platform.runLater()}.
 * Save handlers run on the FX thread — SQLite upserts are fast enough to be inline.
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
    @FXML private ComboBox<String> audioInputCombo;
    @FXML private ComboBox<String> audioOutputCombo;
    @FXML private Spinner<Integer> jitterBufferSpinner;

    // Power Dialer
    @FXML private Spinner<Integer> noAnswerTimeoutSpinner;
    @FXML private Spinner<Integer> autoAdvanceDelaySpinner;
    @FXML private CheckBox         voicemailDropCheckBox;

    // Appearance
    @FXML private ToggleGroup      themeGroup;

    // ── Services ──────────────────────────────────────────────────────────────

    private SettingsService    settingsService;
    private PhoneNumberService phoneNumberService;

    /** Required no-arg constructor for FXMLLoader. */
    public SettingsController() {}

    public void setSettingsService(SettingsService service) {
        this.settingsService = Objects.requireNonNull(service, "settingsService must not be null");
    }

    public void setPhoneNumberService(PhoneNumberService service) {
        this.phoneNumberService = Objects.requireNonNull(service, "phoneNumberService must not be null");
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
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Reload all settings fields from DB. Must be called on the FX Application Thread.
     * All I/O is dispatched to a background thread; fields are populated via runLater.
     */
    public void refresh() {
        statusLabel.setText("Loading…");
        CompletableFuture.supplyAsync(this::loadAll)
                .thenAcceptAsync(this::applyAll, Platform::runLater)
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusLabel.setText("Error loading settings."));
                    return null;
                });
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
        final String inDev  = audioInputCombo.getValue();
        final String outDev = audioOutputCombo.getValue();
        settingsService.setAudioInputDevice(inDev  == null ? "" : inDev);
        settingsService.setAudioOutputDevice(outDev == null ? "" : outDev);
        settingsService.setJitterBufferMs(jitterBufferSpinner.getValue());
        showStatus("Audio settings saved.");
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
            List<String> audioDevices,
            String audioInput,          String audioOutput, int jitterMs,
            int noAnswerSec,            int advanceDelaySec, boolean voicemailDrop,
            String theme) {}

    /** Runs on a background thread — no UI access here. */
    private AllSettings loadAll() {
        final List<String> numbers = phoneNumberService.listOwned()
                .stream().map(n -> n.number().value()).toList();
        final String defNum = phoneNumberService.getDefault()
                .map(n -> n.number().value()).orElse("");
        final List<String> devices = Arrays.stream(AudioSystem.getMixerInfo())
                .map(Mixer.Info::getName).toList();
        return new AllSettings(
                numbers, defNum,
                settingsService.getTwilioAccountSid(), settingsService.getTwilioAuthToken(),
                settingsService.getSipUsername(),    settingsService.getSipPassword(),
                settingsService.getSipDomain(),      settingsService.getSipProxy(),
                settingsService.getSipProxyPort(),
                settingsService.getSmsRelayUrl(),    settingsService.getSmsRelayKey(),
                devices,
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

        audioInputCombo.setItems(FXCollections.observableArrayList(s.audioDevices()));
        if (!s.audioInput().isBlank()) audioInputCombo.setValue(s.audioInput());
        audioOutputCombo.setItems(FXCollections.observableArrayList(s.audioDevices()));
        if (!s.audioOutput().isBlank()) audioOutputCombo.setValue(s.audioOutput());
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

    private void showStatus(String message) {
        statusLabel.setText(message);
    }
}
