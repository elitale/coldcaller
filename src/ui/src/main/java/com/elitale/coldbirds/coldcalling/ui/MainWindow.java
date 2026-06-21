package com.elitale.coldbirds.coldcalling.ui;

import com.elitale.coldbirds.coldcalling.services.CallService;
import com.elitale.coldbirds.coldcalling.services.ContactService;
import com.elitale.coldbirds.coldcalling.services.PhoneNumberService;
import com.elitale.coldbirds.coldcalling.services.PowerDialerService;
import com.elitale.coldbirds.coldcalling.services.SettingsService;
import com.elitale.coldbirds.coldcalling.services.SmsService;
import com.elitale.coldbirds.coldcalling.ui.controller.ActiveCallController;
import com.elitale.coldbirds.coldcalling.ui.controller.CallHistoryController;
import com.elitale.coldbirds.coldcalling.ui.controller.ContactsController;
import com.elitale.coldbirds.coldcalling.ui.controller.DialerController;
import com.elitale.coldbirds.coldcalling.ui.controller.IncomingCallController;
import com.elitale.coldbirds.coldcalling.ui.controller.MessagesController;
import com.elitale.coldbirds.coldcalling.ui.controller.PowerDialerController;
import com.elitale.coldbirds.coldcalling.ui.controller.SettingsController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
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
            SettingsService    settingsService) {}

    private static final double SIDEBAR_WIDTH  = 190;
    private static final double MIN_WINDOW_W   = 960;
    private static final double MIN_WINDOW_H   = 640;
    private static final double DEFAULT_WIDTH  = 1280;
    private static final double DEFAULT_HEIGHT = 820;
    private static final double DIALER_POPOUT_WIDTH = 860;
    private static final double DIALER_POPOUT_HEIGHT = 620;

    private final Stage              stage;
    private final ContactService     contactService;
    private final CallService        callService;
    private final SmsService         smsService;
    private final PhoneNumberService phoneNumberService;
    private final Consumer<String>   onDial;
    private final PowerDialerService powerDialerService;
    private final SettingsService    settingsService;

    // Controllers
    private DialerController       dialerController;
    private IncomingCallController incomingCallController;
    private ActiveCallController   activeCallController;
    private ContactsController     contactsController;
    private CallHistoryController  callHistoryController;
    private MessagesController     messagesController;
    private PowerDialerController  powerDialerController;
    private SettingsController     settingsController;

    // Loaded FXML roots
    private Parent dialerView;
    private Parent incomingCallView;
    private Parent activeCallView;
    private Parent contactsView;
    private Parent callHistoryView;
    private Parent messagesView;
    private Parent powerDialerView;
    private Parent settingsView;

    // Root layout
    private BorderPane root;
    private Stage dialerPopoutStage;
    private boolean dialerDetached;

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

    /** Show the active-call view. Safe to call from any thread. */
    public void showActiveCall(String remoteDisplay, Instant connectedAt, Runnable onHangUp) {
        Platform.runLater(() -> {
            activeCallController.setOnHangUp(onHangUp);
            activeCallController.startCall(remoteDisplay, connectedAt);
            root.setCenter(activeCallView);
        });
    }

    /** Return to the dialer view. Safe to call from any thread. */
    public void showDialer() {
        Platform.runLater(this::showDialerInCurrentContext);
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
        settingsView = loadFxml("/fxml/settings-view.fxml", settingsController);

        // ── Dialer (@FXML fields injected during load — wire callbacks after)
        dialerController = new DialerController();
        dialerView = loadFxml("/fxml/dialer-view.fxml", dialerController);
        dialerController.setRecentCalls(FXCollections.observableArrayList());
        dialerController.setOnDial(onDial);
        dialerController.setOnPopOut(this::toggleDialerPopOut);
        dialerController.setDetached(false);

        // ── Incoming call overlay
        incomingCallController = new IncomingCallController();
        incomingCallView = loadFxml("/fxml/incoming-call-view.fxml", incomingCallController);

        // ── Active call view
        activeCallController = new ActiveCallController();
        activeCallView = loadFxml("/fxml/active-call-view.fxml", activeCallController);

        root = new BorderPane();
        root.setLeft(buildSidebar());
        root.setCenter(dialerView);

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(
                Objects.requireNonNull(
                        MainWindow.class.getResource("/css/cupertino-light.css"),
                        "cupertino-light.css not found in UI resources"
                ).toExternalForm()
        );

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

    // ── Sidebar ───────────────────────────────────────────────────────────────

    private VBox buildSidebar() {
        VBox sidebar = new VBox(2);
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setPadding(new Insets(20, 12, 16, 12));
        sidebar.getStyleClass().add("bg-subtle");

        Label appName = new Label("coldCalling");
        appName.getStyleClass().add("title-2");
        appName.setPadding(new Insets(0, 0, 12, 4));

        Button dialerBtn      = navButton("Dialer",       this::showDialerInCurrentContext);
        Button contactsBtn    = navButton("Contacts",     () -> root.setCenter(contactsView));
        Button historyBtn     = navButton("Call History", () -> root.setCenter(callHistoryView));
        Button messagesBtn    = navButton("Messages",     () -> {
            messagesController.refresh();
            root.setCenter(messagesView);
        });
        Button powerDialerBtn = navButton("Power Dialer", () -> {
            powerDialerController.refresh();
            root.setCenter(powerDialerView);
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

    private void showDialerInCurrentContext() {
        if (dialerDetached) {
            root.setCenter(buildDetachedDialerPlaceholder());
            if (dialerPopoutStage != null) {
                dialerPopoutStage.show();
                dialerPopoutStage.toFront();
                dialerPopoutStage.requestFocus();
            }
            return;
        }
        root.setCenter(dialerView);
    }

    private void toggleDialerPopOut() {
        if (dialerDetached) {
            dockDialer();
        } else {
            detachDialer();
        }
    }

    private void detachDialer() {
        if (dialerDetached) {
            return;
        }

        dialerPopoutStage = new Stage();
        dialerPopoutStage.initOwner(stage);
        dialerPopoutStage.initModality(Modality.NONE);
        dialerPopoutStage.setTitle("Dialer");
        dialerPopoutStage.setMinWidth(680);
        dialerPopoutStage.setMinHeight(520);

        BorderPane popoutRoot = new BorderPane(dialerView);
        Scene popoutScene = new Scene(popoutRoot, DIALER_POPOUT_WIDTH, DIALER_POPOUT_HEIGHT);
        popoutScene.getStylesheets().add(
                Objects.requireNonNull(
                        MainWindow.class.getResource("/css/cupertino-light.css"),
                        "cupertino-light.css not found in UI resources"
                ).toExternalForm()
        );
        dialerPopoutStage.setScene(popoutScene);
        dialerPopoutStage.setOnCloseRequest(event -> dockDialer());

        dialerDetached = true;
        dialerController.setDetached(true);
        root.setCenter(buildDetachedDialerPlaceholder());

        dialerPopoutStage.show();
        dialerPopoutStage.toFront();
    }

    private void dockDialer() {
        if (!dialerDetached) {
            return;
        }

        if (dialerPopoutStage != null) {
            dialerPopoutStage.setOnCloseRequest(null);
            dialerPopoutStage.close();
            dialerPopoutStage = null;
        }

        dialerDetached = false;
        dialerController.setDetached(false);
        root.setCenter(dialerView);
    }

    private VBox buildDetachedDialerPlaceholder() {
        Label title = new Label("Dialer is open in a separate window");
        title.getStyleClass().add("title-2");

        Button bringToFront = new Button("Bring To Front");
        bringToFront.getStyleClass().add("flat");
        bringToFront.setOnAction(event -> {
            if (dialerPopoutStage != null) {
                dialerPopoutStage.show();
                dialerPopoutStage.toFront();
                dialerPopoutStage.requestFocus();
            }
        });

        Button dockNow = new Button("Dock Dialer");
        dockNow.getStyleClass().add("accent");
        dockNow.setOnAction(event -> dockDialer());

        HBox actions = new HBox(12, bringToFront, dockNow);
        actions.setAlignment(javafx.geometry.Pos.CENTER);

        VBox placeholder = new VBox(16, title, actions);
        placeholder.setAlignment(javafx.geometry.Pos.CENTER);
        placeholder.setPadding(new Insets(24));
        return placeholder;
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
