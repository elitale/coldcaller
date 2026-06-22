package com.elitale.coldbirds.coldcalling.ui;

import com.elitale.coldbirds.coldcalling.services.CallService;
import com.elitale.coldbirds.coldcalling.services.ContactService;
import com.elitale.coldbirds.coldcalling.services.PhoneNumberService;
import com.elitale.coldbirds.coldcalling.services.PowerDialerService;
import com.elitale.coldbirds.coldcalling.services.SettingsService;
import com.elitale.coldbirds.coldcalling.services.SmsService;
import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.model.Contact;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.domain.value.CountryLookup;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceManager;
import com.elitale.coldbirds.coldcalling.telephony.audio.AudioDeviceTester;
import com.elitale.coldbirds.coldcalling.ui.controller.ActiveCallController;
import com.elitale.coldbirds.coldcalling.ui.controller.CallHistoryController;
import com.elitale.coldbirds.coldcalling.ui.controller.ContactsController;
import com.elitale.coldbirds.coldcalling.ui.controller.DialerController;
import com.elitale.coldbirds.coldcalling.ui.controller.IncomingCallController;
import com.elitale.coldbirds.coldcalling.ui.controller.MessagesController;
import com.elitale.coldbirds.coldcalling.ui.controller.PowerDialerController;
import com.elitale.coldbirds.coldcalling.ui.controller.SettingsController;
import com.elitale.coldbirds.coldcalling.ui.support.CallParticipant;
import com.elitale.coldbirds.coldcalling.ui.support.CountryCatalog;
import com.elitale.coldbirds.coldcalling.ui.support.RecentCallRow;
import com.elitale.coldbirds.coldcalling.ui.support.TextInputShortcuts;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Main application window — fixed sidebar + swappable centre pane.
 * All services are injected via {@link Dependencies}.
 */
public final class MainWindow {

    /** All services the window and its controllers need. */
    public record Dependencies(
            ContactService     contactService,
            CallService        callService,
            SmsService         smsService,
            PhoneNumberService phoneNumberService,
            Consumer<String>   onDial,
            PowerDialerService powerDialerService,
            SettingsService    settingsService,
            AudioDeviceManager audioDeviceManager,
            AudioDeviceTester  audioDeviceTester,
            BiConsumer<String, String> onApplyAudioDevices) {}

    private static final double SIDEBAR_WIDTH  = 190;
    private static final double MIN_WINDOW_W   = 960;
    private static final double MIN_WINDOW_H   = 640;
    private static final double DEFAULT_WIDTH  = 1280;
    private static final double DEFAULT_HEIGHT = 820;

    private final Stage              stage;
    private final ContactService     contactService;
    private final CallService        callService;
    private final SmsService         smsService;
    private final PhoneNumberService phoneNumberService;
    private final Consumer<String>   onDial;
    private final PowerDialerService powerDialerService;
    private final SettingsService    settingsService;
    private final AudioDeviceManager audioDeviceManager;
    private final AudioDeviceTester  audioDeviceTester;
    private final BiConsumer<String, String> onApplyAudioDevices;

    // Controllers
    private DialerController       dialerController;
    private IncomingCallController incomingCallController;
    private ActiveCallController   activeCallController;
    private ContactsController     contactsController;
    private CallHistoryController  callHistoryController;
    private MessagesController     messagesController;
    private PowerDialerController  powerDialerController;
    private SettingsController     settingsController;

    /** Non-blocking right-docked detail panel (built lazily on first open). */
    private NumberDetailPanel     numberDetailPanel;

    /** Auto-save sink registered before {@link #show()} builds the controller. */
    private BiConsumer<Optional<CallDisposition>, String> pendingLogAutoSave;

    /** Most recent remote number shown on the calling screen — reused by Redial. */
    private String lastDialedNumber;

    // Loaded FXML roots
    private Parent dialerView;
    private Parent incomingCallView;
    private Parent activeCallView;
    private Parent contactsView;
    private Parent callHistoryView;
    private Parent messagesView;
    private Parent powerDialerView;
    private Parent settingsView;

    /** Number of recent calls shown on the dialer. */
    private static final int RECENT_CALLS_LIMIT = 50;

    /** Backing list for the dialer's "Recent Calls" view — mutated on the FX thread only. */
    private final ObservableList<RecentCallRow> recentCalls = FXCollections.observableArrayList();

    // Root layout
    private BorderPane root;

    /** Top-right stack that holds transient toast notifications. */
    private VBox toastLayer;

    public MainWindow(Stage stage, Dependencies deps) {
        this.stage              = Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(deps, "deps must not be null");
        this.contactService     = Objects.requireNonNull(deps.contactService(),     "contactService");
        this.callService        = Objects.requireNonNull(deps.callService(),        "callService");
        this.smsService         = Objects.requireNonNull(deps.smsService(),         "smsService");
        this.phoneNumberService = Objects.requireNonNull(deps.phoneNumberService(), "phoneNumberService");
        this.onDial             = Objects.requireNonNull(deps.onDial(),             "onDial");
        this.powerDialerService = Objects.requireNonNull(deps.powerDialerService(), "powerDialerService");
        this.settingsService    = Objects.requireNonNull(deps.settingsService(),    "settingsService");
        this.audioDeviceManager = Objects.requireNonNull(deps.audioDeviceManager(), "audioDeviceManager");
        this.audioDeviceTester  = Objects.requireNonNull(deps.audioDeviceTester(),  "audioDeviceTester");
        this.onApplyAudioDevices = Objects.requireNonNull(deps.onApplyAudioDevices(), "onApplyAudioDevices");
    }

    // ── Thread-safe public API ────────────────────────────────────────────────

    /** Show the incoming-call overlay. Safe to call from any thread. */
    public void showIncomingCall(String callerName, String callerNumber,
                                 Runnable onAnswer, Runnable onReject) {
        Platform.runLater(() -> {
            incomingCallController.setOnAnswer(onAnswer);
            incomingCallController.setOnReject(onReject);
            incomingCallController.showCaller(callerName, callerNumber);
            root.setCenter(incomingCallView);
        });
    }

    /**
     * Open the calling screen in its connecting phase ("Calling…") the instant
     * the user presses call, before the SIP INVITE goes out. Safe to call from
     * any thread.
     */
    public void showCallStarting(String number, Runnable onHangUp) {
        final CallParticipant party = participantFor(number);
        Platform.runLater(() -> {
            lastDialedNumber = number;
            activeCallController.setOnHangUp(onHangUp);
            activeCallController.startConnecting(party);
            root.setCenter(activeCallView);
        });
    }

    /**
     * Flip the already-visible calling screen from "Calling…" to "Ringing…" once
     * the INVITE has been dispatched. Safe to call from any thread.
     */
    public void markCallRinging() {
        activeCallController.markRinging();
    }

    /**
     * Transition the on-screen calling screen from Ringing to Active when the
     * outbound call connects. Safe to call from any thread.
     */
    public void markCallConnected(Instant connectedAt) {
        activeCallController.markConnected(connectedAt);
    }

    /**
     * Open the calling screen directly in its Active phase (inbound answered).
     * Safe to call from any thread.
     */
    public void showActiveCall(String number, Instant connectedAt, Runnable onHangUp) {
        final CallParticipant party = participantFor(number);
        Platform.runLater(() -> {
            lastDialedNumber = number;
            activeCallController.setOnHangUp(onHangUp);
            activeCallController.startActive(party, connectedAt);
            root.setCenter(activeCallView);
        });
    }

    /** @return the disposition chosen on the calling screen, if any. */
    public Optional<CallDisposition> selectedDisposition() {
        return activeCallController.getDisposition();
    }

    /** @return the notes typed on the calling screen. */
    public String callNotes() {
        return activeCallController.getNotes();
    }

    /**
     * Register the calling screen's auto-save sink. Invoked (debounced for notes,
     * immediately for a disposition pick) with the current disposition + notes so
     * the call log is persisted continuously, not just on "Save &amp; Close".
     * May be called before {@link #show()} builds the controller; the handler is
     * then applied once the controller exists.
     */
    public void setOnCallLogAutoSave(BiConsumer<Optional<CallDisposition>, String> handler) {
        this.pendingLogAutoSave = Objects.requireNonNull(handler);
        if (activeCallController != null) {
            activeCallController.setOnLogChanged(handler);
        }
    }

    /** Stop the calling screen's timers and tones. Safe to call from any thread. */
    public void endActiveCall() {
        activeCallController.dispose();
    }

    /**
     * Move the on-screen calling screen into its wrap-up phase after a call ends
     * naturally, keeping notes and disposition editable until the rep saves.
     * {@code onSaveClose} persists those edits and returns to the dialer.
     * Safe to call from any thread.
     */
    public void showCallWrapUp(Instant endedAt, Runnable onSaveClose) {
        Platform.runLater(() -> {
            activeCallController.setOnHangUp(onSaveClose);
            activeCallController.markEnded(endedAt);
        });
    }

    /**
     * Keep the calling screen up and show the failure reason after a mid-call
     * failure. {@code onClose} dismisses the screen. Safe to call from any thread.
     */
    public void markCallFailed(String reason, Runnable onClose) {
        Platform.runLater(() -> {
            activeCallController.setOnHangUp(onClose);
            activeCallController.markFailed(reason);
        });
    }

    /**
     * Show the calling screen directly in its failed state for a call that never
     * started (e.g. not signed in). Safe to call from any thread.
     */
    public void showCallFailed(String number, String reason, Runnable onClose) {
        final CallParticipant party = participantFor(number);
        Platform.runLater(() -> {
            lastDialedNumber = number;
            activeCallController.setOnHangUp(onClose);
            activeCallController.showFailed(party, reason);
            root.setCenter(activeCallView);
        });
    }

    /** Resolve a remote number to a display participant (contact + country). */
    private CallParticipant participantFor(String number) {
        Optional<Contact> contact = Optional.empty();
        Optional<Call> priorCall = Optional.empty();
        try {
            final PhoneNumber remote = new PhoneNumber(number);
            contact = contactService.findByPhone(remote);
            final List<Call> history = callService.findByRemoteNumber(remote);
            priorCall = history.isEmpty() ? Optional.empty() : Optional.of(history.get(0));
        } catch (final IllegalArgumentException ignored) {
            // Non-E.164 caller id — fall back to number-only display.
        }
        final Optional<Country> country = CountryLookup.byE164(CountryCatalog.ALL, number);
        return CallParticipant.of(number, contact, country, priorCall);
    }

    /** Return to the dialer view. Safe to call from any thread. */
    public void showDialer() {
        Platform.runLater(() -> root.setCenter(dialerView));
    }

    /** Show a transient error toast (alert icon + message). Safe to call from any thread. */
    public void showError(String message) {
        Objects.requireNonNull(message, "message must not be null");
        Platform.runLater(() -> addToast(message));
    }

    /** Refresh the Messages view (e.g. after an inbound poll). Safe to call from any thread. */
    public void refreshMessages() {
        Platform.runLater(messagesController::refresh);
    }

    /**
     * Reload the dialer's "Recent Calls" list from the database. Safe to call from
     * any thread — the DB read runs off the FX thread, the list update runs on it.
     */
    public void refreshRecentCalls() {
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> buildRecentRows(callService.findRecent(RECENT_CALLS_LIMIT)))
                .thenAccept(rows -> Platform.runLater(() -> recentCalls.setAll(rows)));
    }

    /**
     * Collapse the raw recent-call list into one row per phone number (newest
     * first), resolving the total call count and country for each. Runs off the
     * FX thread.
     */
    private List<RecentCallRow> buildRecentRows(final List<Call> calls) {
        final Map<String, Call> latestByNumber = new LinkedHashMap<>();
        for (final Call call : calls) {
            latestByNumber.putIfAbsent(call.remoteNumber().value(), call);
        }
        return latestByNumber.values().stream()
                .map(call -> new RecentCallRow(
                        call.remoteNumber().value(),
                        call.startedAt(),
                        callService.findByRemoteNumber(call.remoteNumber()).size(),
                        CountryLookup.byE164(CountryCatalog.ALL, call.remoteNumber().value()),
                        contactService.findByPhone(call.remoteNumber())))
                .toList();
    }

    /** Open the number-detail panel (contact/lead + call history + recordings). */
    private void openNumberDetail(String number) {
        if (numberDetailPanel == null) {
            numberDetailPanel = new NumberDetailPanel(
                    callService, contactService, smsService, CountryCatalog.ALL,
                    onDial, this::openMessageThread, this::closeNumberDetail, this::refreshRecentCalls);
        }
        numberDetailPanel.show(number);
        root.setRight(numberDetailPanel.getRoot());
    }

    /** Hide and stop the number-detail panel. */
    private void closeNumberDetail() {
        if (numberDetailPanel != null) {
            numberDetailPanel.stopPlayback();
        }
        root.setRight(null);
    }

    /** Switch to the Messages screen and open the conversation for {@code number}. */
    private void openMessageThread(String number) {
        try {
            final PhoneNumber remote = new PhoneNumber(number);
            messagesController.openConversation(remote);
            showCenter(messagesView);
        } catch (final IllegalArgumentException e) {
            showError("Can't message \"" + number + "\" — not a valid phone number.");
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Build and show the primary window. Must be called on the FX Application Thread. */
    public void show() {
        Application.setUserAgentStylesheet(Application.STYLESHEET_MODENA);

        // ── Call History
        callHistoryController = new CallHistoryController();
        callHistoryController.setCallService(callService);
        callHistoryController.setOnDial(onDial);
        callHistoryView = loadFxml("/fxml/call-history-view.fxml", callHistoryController);

        // ── Contacts
        contactsController = new ContactsController();
        contactsController.setContactService(contactService);
        contactsController.setOnDial(onDial);
        contactsView = loadFxml("/fxml/contacts-view.fxml", contactsController);

        // ── Messages
        messagesController = new MessagesController();
        messagesController.setSmsService(smsService);
        messagesController.setPhoneNumberService(phoneNumberService);
        messagesView = loadFxml("/fxml/messages-view.fxml", messagesController);

        // ── Power Dialer
        powerDialerController = new PowerDialerController();
        powerDialerController.setPowerDialerService(powerDialerService);
        powerDialerView = loadFxml("/fxml/power-dialer-view.fxml", powerDialerController);

        // ── Settings
        settingsController = new SettingsController();
        settingsController.setSettingsService(settingsService);
        settingsController.setPhoneNumberService(phoneNumberService);
        settingsController.setAudioDeviceManager(audioDeviceManager);
        settingsController.setAudioDeviceTester(audioDeviceTester);
        settingsController.setOnApplyAudioDevices(onApplyAudioDevices);
        settingsView = loadFxml("/fxml/settings-view.fxml", settingsController);

        // ── Dialer (@FXML fields injected during load — wire callbacks after)
        dialerController = new DialerController();
        dialerView = loadFxml("/fxml/dialer-view.fxml", dialerController);
        dialerController.setRecentCalls(recentCalls);
        dialerController.setOnRecentSelected(this::openNumberDetail);
        dialerController.setOnRecentCall(onDial);
        dialerController.setOnRecentMessage(this::openMessageThread);
        dialerController.setOnDial(onDial);
        dialerController.setCountries(CountryCatalog.ALL);
        dialerController.selectCountryByIso(settingsService.getDefaultCountryIso());
        dialerController.setOnCountrySelected(country ->
                settingsService.setDefaultCountryIso(country.isoCode()));

        // ── Incoming call overlay
        incomingCallController = new IncomingCallController();
        incomingCallView = loadFxml("/fxml/incoming-call-view.fxml", incomingCallController);

        // ── Active call view
        activeCallController = new ActiveCallController();
        activeCallView = loadFxml("/fxml/active-call-view.fxml", activeCallController);
        if (pendingLogAutoSave != null) {
            activeCallController.setOnLogChanged(pendingLogAutoSave);
        }
        // Redial re-dials the last remote number through the same dial path the
        // dialer uses; the button is shown only in the wrap-up / failed phases.
        activeCallController.setOnRedial(() -> {
            final String number = lastDialedNumber;
            if (number != null && !number.isBlank()) {
                onDial.accept(number);
            }
        });

        root = new BorderPane();
        root.setLeft(buildSidebar());
        root.setCenter(dialerView);

        // Overlay layer for toasts; lets clicks pass through empty areas.
        toastLayer = new VBox(10);
        toastLayer.setAlignment(Pos.TOP_RIGHT);
        toastLayer.setPadding(new Insets(20));
        toastLayer.setPickOnBounds(false);
        StackPane rootStack = new StackPane(root, toastLayer);

        Scene scene = new Scene(rootStack, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(
                Objects.requireNonNull(
                        MainWindow.class.getResource("/css/cupertino-light.css"),
                        "cupertino-light.css not found in UI resources"
                ).toExternalForm()
        );
        TextInputShortcuts.install(scene);

        scene.setOnKeyPressed(event -> {
            if (root.getCenter() == incomingCallView) {
                incomingCallController.handleKeyPress(event);
            } else if (root.getCenter() == activeCallView) {
                activeCallController.handleKeyPress(event);
            }
        });

        stage.setTitle("coldCalling");
        stage.setScene(scene);
        stage.setMinWidth(MIN_WINDOW_W);
        stage.setMinHeight(MIN_WINDOW_H);
        stage.show();
    }

    // ── Toasts ────────────────────────────────────────────────────────────────

    /** Build, show, and auto-dismiss an error toast in the overlay layer. */
    private void addToast(String message) {
        Node toast = buildToast(message);
        toast.setOpacity(0);
        toastLayer.getChildren().add(toast);

        FadeTransition in = new FadeTransition(Duration.millis(180), toast);
        in.setFromValue(0);
        in.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.seconds(6));
        FadeTransition out = new FadeTransition(Duration.millis(260), toast);
        out.setFromValue(1);
        out.setToValue(0);

        SequentialTransition sequence = new SequentialTransition(in, hold, out);
        sequence.setOnFinished(e -> toastLayer.getChildren().remove(toast));
        // Click to dismiss early.
        toast.setOnMouseClicked(e -> {
            sequence.stop();
            toastLayer.getChildren().remove(toast);
        });
        sequence.play();
    }

    private Node buildToast(String message) {
        Label icon = new Label("!");
        icon.getStyleClass().add("toast-icon");

        Label text = new Label(message);
        text.setWrapText(true);
        text.setMaxWidth(320);
        text.getStyleClass().add("toast-message");

        HBox box = new HBox(12, icon, text);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().addAll("toast", "toast-error");
        return box;
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────

    /** Swap the centre pane, stopping any live mic test left running in Settings. */
    private void showCenter(Parent view) {
        settingsController.dispose();
        closeNumberDetail();
        root.setCenter(view);
    }

    private VBox buildSidebar() {
        VBox sidebar = new VBox(2);
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setPadding(new Insets(20, 12, 16, 12));
        sidebar.getStyleClass().add("bg-subtle");

        Label appName = new Label("coldCalling");
        appName.getStyleClass().add("title-2");
        appName.setPadding(new Insets(0, 0, 12, 4));

        Button dialerBtn      = navButton("Dialer",       () -> showCenter(dialerView));
        Button contactsBtn    = navButton("Contacts",     () -> showCenter(contactsView));
        Button historyBtn     = navButton("Call History", () -> showCenter(callHistoryView));
        Button messagesBtn    = navButton("Messages",     () -> {
            messagesController.refresh();
            showCenter(messagesView);
        });
        Button powerDialerBtn = navButton("Power Dialer", () -> {
            powerDialerController.refresh();
            showCenter(powerDialerView);
        });

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Button settingsBtn = navButton("Settings", () -> {
            settingsController.refresh();
            root.setCenter(settingsView);
        });

        sidebar.getChildren().addAll(
                appName, new Separator(), gap(8),
                dialerBtn, contactsBtn, historyBtn, messagesBtn, powerDialerBtn,
                spacer,
                new Separator(), gap(4),
                settingsBtn
        );
        return sidebar;
    }

    private static Button navButton(String text, Runnable onClick) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.getStyleClass().add("flat");
        btn.setOnAction(e -> onClick.run());
        return btn;
    }

    private static Region gap(double height) {
        Region r = new Region();
        r.setPrefHeight(height);
        return r;
    }

    // ── FXML loading ──────────────────────────────────────────────────────────

    private <T> Parent loadFxml(String resourcePath, T controller) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    Objects.requireNonNull(
                            getClass().getResource(resourcePath),
                            resourcePath + " not found in classpath"
                    )
            );
            loader.setController(controller);
            return loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load FXML: " + resourcePath, e);
        }
    }
}
