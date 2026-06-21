package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.PhoneNumberService;
import com.elitale.coldbirds.coldcalling.services.SmsService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for the Messages screen (messages-view.fxml).
 * <p>
 * Two-pane layout: left = conversation list, right = thread + compose bar.
 * <p>
 * Threading: all methods must be called on the FX Application Thread except
 * where CompletableFuture dispatches to a background thread explicitly.
 */
public final class MessagesController {

    // ── FXML-injected fields ──────────────────────────────────────────────────

    @FXML private ListView<SmsMessage>  conversationList;
    @FXML private Label                 threadHeader;
    @FXML private Label                 threadSubtitle;
    @FXML private ListView<SmsMessage>  threadList;
    @FXML private ComboBox<OwnedNumber> fromNumberCombo;
    @FXML private TextField             composeField;
    @FXML private Button                sendBtn;

    // ── State ─────────────────────────────────────────────────────────────────

    private SmsService         smsService;
    private PhoneNumberService phoneNumberService;

    private final ObservableList<SmsMessage> conversations = FXCollections.observableArrayList();
    private final ObservableList<SmsMessage> thread        = FXCollections.observableArrayList();

    /** The remote number whose thread is currently open, if any. */
    private Optional<PhoneNumber> selectedRemote = Optional.empty();

    /** Default no-arg constructor — required by FXMLLoader. */
    public MessagesController() {}

    // ── Configuration (called before FXMLLoader.load()) ───────────────────────

    public void setSmsService(SmsService smsService) {
        this.smsService = Objects.requireNonNull(smsService, "smsService must not be null");
    }

    public void setPhoneNumberService(PhoneNumberService phoneNumberService) {
        this.phoneNumberService = Objects.requireNonNull(phoneNumberService,
                "phoneNumberService must not be null");
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        conversationList.setItems(conversations);
        conversationList.setCellFactory(lv -> new ConversationListCell());
        conversationList.setPlaceholder(caption("No messages yet."));

        threadList.setItems(thread);
        threadList.setCellFactory(lv -> new MessageBubbleCell());
        threadList.setPlaceholder(caption("Select a conversation to view messages."));

        fromNumberCombo.setConverter(new StringConverter<>() {
            @Override public String toString(OwnedNumber n) {
                return n == null ? "" : n.friendlyName().orElse(n.number().value());
            }
            @Override public OwnedNumber fromString(String s) { return null; }
        });

        sendBtn.disableProperty().bind(
                composeField.textProperty().isEmpty()
                        .or(fromNumberCombo.getSelectionModel().selectedItemProperty().isNull())
                        .or(conversationList.getSelectionModel().selectedItemProperty().isNull()));

        conversationList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> {
                    if (selected != null) loadThread(selected.remoteNumber());
                });

        loadOwnedNumbers();
        loadConversations();
    }

    // ── FXML event handlers ───────────────────────────────────────────────────

    @FXML
    private void onSend() {
        final OwnedNumber from = fromNumberCombo.getSelectionModel().getSelectedItem();
        final String body = composeField.getText().strip();
        if (from == null || body.isBlank() || selectedRemote.isEmpty()) return;

        final PhoneNumber to = selectedRemote.get();
        composeField.clear();
        CompletableFuture.runAsync(() -> smsService.send(from.number(), to, body))
                .thenRunAsync(() -> { loadThread(to); loadConversations(); }, Platform::runLater);
    }

    @FXML
    private void onNewMessage() {
        showNewMessageDialog();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Reload the conversation list. Call when the Messages tab becomes visible. */
    public void refresh() {
        loadConversations();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void loadConversations() {
        if (smsService == null) return;
        CompletableFuture.supplyAsync(() -> smsService.findConversations())
                .thenAcceptAsync(conversations::setAll, Platform::runLater);
    }

    private void loadThread(PhoneNumber remoteNumber) {
        selectedRemote = Optional.of(remoteNumber);
        threadHeader.setText(remoteNumber.value());
        threadSubtitle.setText("SMS thread");
        CompletableFuture.supplyAsync(() -> smsService.findThread(remoteNumber))
                .thenAcceptAsync(msgs -> {
                    thread.setAll(msgs);
                    if (!msgs.isEmpty()) threadList.scrollTo(msgs.size() - 1);
                }, Platform::runLater);
    }

    private void loadOwnedNumbers() {
        if (phoneNumberService == null) return;
        CompletableFuture.supplyAsync(() -> phoneNumberService.listOwned())
                .thenAcceptAsync(numbers -> {
                    fromNumberCombo.setItems(FXCollections.observableArrayList(numbers));
                    if (!numbers.isEmpty()) fromNumberCombo.getSelectionModel().selectFirst();
                }, Platform::runLater);
    }

    private void showNewMessageDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("New Message");
        dialog.setHeaderText("Start a new conversation");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        final OwnedNumber from = fromNumberCombo.getSelectionModel().getSelectedItem();

        VBox content = new VBox(8);
        content.setPadding(new Insets(16));
        TextField toField   = new TextField();
        toField.setPromptText("+15550001234 (E.164 required)");
        TextField bodyField = new TextField();
        bodyField.setPromptText("Message body");
        content.getChildren().addAll(new Label("To:"), toField, new Label("Message:"), bodyField);
        dialog.getDialogPane().setContent(content);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setDisable(true);
        Runnable validate = () -> okBtn.setDisable(
                toField.getText().strip().isBlank() || bodyField.getText().strip().isBlank());
        toField.textProperty().addListener((o, a, b) -> validate.run());
        bodyField.textProperty().addListener((o, a, b) -> validate.run());

        dialog.showAndWait().ifPresent(result -> {
            if (result != ButtonType.OK || from == null) return;
            String rawTo = toField.getText().strip()
                    .replace(" ", "").replace("-", "").replace("(", "").replace(")", "");
            try {
                PhoneNumber to = new PhoneNumber(rawTo);
                String body = bodyField.getText().strip();
                CompletableFuture.runAsync(() -> smsService.send(from.number(), to, body))
                        .thenRunAsync(() -> { loadConversations(); loadThread(to); },
                                Platform::runLater);
            } catch (IllegalArgumentException e) {
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("Invalid Number");
                err.setHeaderText("\"" + rawTo + "\" is not a valid E.164 number");
                err.showAndWait();
            }
        });
    }

    private static Label caption(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("caption");
        return l;
    }
}
