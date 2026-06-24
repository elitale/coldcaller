package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.domain.value.CountryLookup;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.services.PhoneNumberService;
import com.elitale.coldbirds.coldcalling.services.SmsService;
import com.elitale.coldbirds.coldcalling.ui.support.CountryCatalog;
import com.elitale.coldbirds.coldcalling.ui.support.Motion;
import com.elitale.coldbirds.coldcalling.ui.support.SmsConversationRow;
import com.elitale.coldbirds.coldcalling.ui.support.SmsSegments;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    @FXML private ListView<SmsConversationRow> conversationList;
    @FXML private Label                 threadHeader;
    @FXML private Label                 threadSubtitle;
    @FXML private Label                 optOutBanner;
    @FXML private ListView<SmsMessage>  threadList;
    @FXML private ComboBox<OwnedNumber> fromNumberCombo;
    @FXML private TextField             composeField;
    @FXML private Label                 composeHint;
    @FXML private Button                sendBtn;
    @FXML private Button                refreshBtn;

    // ── State ─────────────────────────────────────────────────────────────────

    private SmsService         smsService;
    private PhoneNumberService phoneNumberService;
    private LeadService        leadService;

    private final ObservableList<SmsConversationRow> conversations = FXCollections.observableArrayList();
    private final ObservableList<SmsMessage>         thread        = FXCollections.observableArrayList();

    /** True once a remote thread is open (existing conversation or a freshly started one). */
    private final BooleanProperty hasRemote = new SimpleBooleanProperty(false);

    /** True when the open thread's contact has opted out / is on DNC — compose is blocked. */
    private final BooleanProperty optedOut = new SimpleBooleanProperty(false);

    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("h:mm a");

    /** Message ids that should "pop in" on the next thread render (newly sent/received). */
    private final Set<Long> pendingBubbleAnimations = new HashSet<>();

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

    public void setLeadService(LeadService leadService) {
        this.leadService = Objects.requireNonNull(leadService, "leadService must not be null");
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        conversationList.setItems(conversations);
        conversationList.setCellFactory(lv -> new ConversationListCell());
        conversationList.setPlaceholder(caption("No messages yet."));

        threadList.setItems(thread);
        threadList.setCellFactory(lv -> new MessageBubbleCell(this::resend, pendingBubbleAnimations));
        threadList.setPlaceholder(emptyState("bi-chat-dots", "Select a conversation"));

        fromNumberCombo.setConverter(new StringConverter<>() {
            @Override public String toString(OwnedNumber n) {
                return n == null ? "" : n.friendlyName().orElse(n.number().value());
            }
            @Override public OwnedNumber fromString(String s) { return null; }
        });

        // Compose is blocked while the contact is opted-out / on DNC (mirrors the dial path).
        sendBtn.disableProperty().bind(
                composeField.textProperty().isEmpty()
                        .or(fromNumberCombo.getSelectionModel().selectedItemProperty().isNull())
                        .or(hasRemote.not())
                        .or(optedOut));
        composeField.disableProperty().bind(optedOut);
        optOutBanner.managedProperty().bind(optOutBanner.visibleProperty());
        optOutBanner.setVisible(false);
        composeHint.managedProperty().bind(composeHint.visibleProperty());
        composeHint.setVisible(false);
        composeField.textProperty().addListener((o, a, b) -> updateComposeHint());
        optedOut.addListener((o, a, b) -> updateComposeHint());

        conversationList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> {
                    if (selected != null) loadThread(selected.remote(), true);
                });

        FontIcon sendIcon = new FontIcon("bi-arrow-up-circle-fill");
        sendIcon.setIconSize(30);
        sendIcon.setIconColor(Color.web("#0071E3"));
        sendBtn.setGraphic(sendIcon);
        sendBtn.setText(null);
        sendBtn.getStyleClass().add("compose-send");

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
        Motion.pressFlash(sendBtn);
        composeField.clear();
        CompletableFuture.runAsync(() -> smsService.send(from.number(), to, body))
                .thenRunAsync(() -> { loadThread(to, false); loadConversations(); }, Platform::runLater);
    }

    @FXML
    private void onNewMessage() {
        showNewMessageDialog();
    }

    /**
     * Manually pull new inbound SMS from Twilio, then reload the conversation list
     * and the open thread. Wired to the Refresh button now that automatic background
     * polling is disabled. Network and DB work run off the FX thread.
     */
    @FXML
    private void onRefresh() {
        if (smsService == null) return;
        refreshBtn.setDisable(true);
        CompletableFuture.supplyAsync(() -> smsService.refreshInbound())
                .whenCompleteAsync((count, ex) -> {
                    refreshBtn.setDisable(false);
                    loadConversations();
                    selectedRemote.ifPresent(r -> loadThread(r, false));
                }, Platform::runLater);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Reload the conversation list (and the open thread, so inbound replies appear). Any-thread safe. */
    public void refresh() {
        loadConversations();
        selectedRemote.ifPresent(r -> loadThread(r, false));
    }

    /**
     * Open (or start) the SMS thread for {@code remote} and bring it into view —
     * used when the user taps "Message" on a recent call. If a conversation with
     * this number already exists it is selected; otherwise a fresh, empty thread
     * is opened ready to compose.
     *
     * @param remote the remote party's number; must not be null
     */
    public void openConversation(final PhoneNumber remote) {
        Objects.requireNonNull(remote, "remote must not be null");
        loadConversations();
        loadThread(remote, true);
        Platform.runLater(() -> {
            conversations.stream()
                    .filter(r -> r.remote().equals(remote))
                    .findFirst()
                    .ifPresent(conversationList.getSelectionModel()::select);
            composeField.requestFocus();
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void loadConversations() {
        if (smsService == null) return;
        CompletableFuture
                .supplyAsync(() -> smsService.findConversations().stream().map(this::toRow).toList())
                .thenAcceptAsync(conversations::setAll, Platform::runLater);
    }

    /** Resolve lead identity, country, and opt-out for a conversation's latest message (off-thread). */
    private SmsConversationRow toRow(SmsMessage last) {
        Optional<Lead> lead = leadService == null
                ? Optional.empty() : leadService.findByPhone(last.remoteNumber());
        Optional<Country> country = CountryLookup.byE164(CountryCatalog.ALL, last.remoteNumber().value());
        boolean leadOptedOut = lead.map(Lead::dnc).orElse(false);
        return new SmsConversationRow(last, lead, country, false, leadOptedOut);
    }

    private void loadThread(PhoneNumber remoteNumber, boolean animateListIn) {
        final boolean sameThread = selectedRemote.map(r -> r.equals(remoteNumber)).orElse(false);
        selectedRemote = Optional.of(remoteNumber);
        hasRemote.set(true);
        CompletableFuture.supplyAsync(() -> {
            Optional<Lead> lead = leadService == null
                    ? Optional.empty() : leadService.findByPhone(remoteNumber);
            Optional<Country> country = CountryLookup.byE164(CountryCatalog.ALL, remoteNumber.value());
            List<SmsMessage> msgs = smsService.findThread(remoteNumber);
            return new ThreadLoad(lead, country, msgs);
        }).thenAcceptAsync(load -> {
            applyThreadHeader(remoteNumber, load.lead(), load.country());
            boolean leadOptedOut = load.lead().map(Lead::dnc).orElse(false);
            optedOut.set(leadOptedOut);
            optOutBanner.setVisible(leadOptedOut);
            markNewBubbles(sameThread && !animateListIn, load.messages());
            thread.setAll(load.messages());
            if (!load.messages().isEmpty()) threadList.scrollTo(load.messages().size() - 1);
            if (animateListIn) fadeInThread();
        }, Platform::runLater);
    }

    /** Flag messages not already in the open thread so their bubbles pop in (same-thread refresh only). */
    private void markNewBubbles(boolean enabled, List<SmsMessage> incoming) {
        pendingBubbleAnimations.clear();
        if (!enabled) return;
        final Set<Long> previous = new HashSet<>();
        for (SmsMessage m : thread) previous.add(m.id().value());
        for (SmsMessage m : incoming) {
            if (!previous.contains(m.id().value())) pendingBubbleAnimations.add(m.id().value());
        }
    }

    /** Soft fade-in of the whole thread when switching conversations. Respects Reduce Motion. */
    private void fadeInThread() {
        if (Motion.isReduced()) return;
        threadList.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(160), threadList);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.playFromStart();
    }

    private record ThreadLoad(Optional<Lead> lead, Optional<Country> country, List<SmsMessage> messages) {}

    private void applyThreadHeader(PhoneNumber remote, Optional<Lead> lead, Optional<Country> country) {
        threadHeader.setText(lead.map(Lead::displayName).filter(s -> !s.isBlank()).orElse(remote.value()));
        StringBuilder sub = new StringBuilder();
        lead.flatMap(Lead::company).filter(s -> !s.isBlank()).ifPresent(sub::append);
        country.ifPresent(c -> {
            if (sub.length() > 0) sub.append("  \u00B7  ");
            sub.append(ZonedDateTime.now(c.zone()).format(LOCAL_TIME)).append(' ').append(c.isoCode());
        });
        threadSubtitle.setText(sub.length() == 0 ? remote.value() : sub.toString());
    }

    /** Resend a Failed outbound message from the thread's current "From" number. */
    private void resend(SmsMessage failed) {
        final OwnedNumber from = fromNumberCombo.getSelectionModel().getSelectedItem();
        if (from == null) return;
        CompletableFuture.runAsync(() -> smsService.send(from.number(), failed.remoteNumber(), failed.body()))
                .thenRunAsync(() -> { loadThread(failed.remoteNumber(), false); loadConversations(); },
                        Platform::runLater);
    }

    /** Opt-out reason takes precedence; otherwise show a segment warning only when non-trivial. */
    private void updateComposeHint() {
        if (optedOut.get()) {
            composeHint.setText("This contact opted out \u2014 texting is disabled.");
            composeHint.setVisible(true);
            return;
        }
        SmsSegments.Warning warning = SmsSegments.warn(composeField.getText());
        composeHint.setText(warning == SmsSegments.Warning.NONE ? "" : warning.message());
        composeHint.setVisible(warning != SmsSegments.Warning.NONE);
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
                        .thenRunAsync(() -> { loadConversations(); loadThread(to, true); },
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

    private static Node emptyState(String iconLiteral, String text) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(38);
        icon.setIconColor(Color.web("#C7C7CC"));
        VBox box = new VBox(10, icon, caption(text));
        box.setAlignment(Pos.CENTER);
        return box;
    }
}
