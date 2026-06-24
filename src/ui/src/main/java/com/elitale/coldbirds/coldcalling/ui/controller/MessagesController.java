package com.elitale.coldbirds.coldcalling.ui.controller;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.kordamp.ikonli.javafx.FontIcon;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.domain.value.CountryLookup;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.services.CallService;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.services.PhoneNumberService;
import com.elitale.coldbirds.coldcalling.services.SmsService;
import com.elitale.coldbirds.coldcalling.ui.support.ContactSuggestion;
import com.elitale.coldbirds.coldcalling.ui.support.CountryCatalog;
import com.elitale.coldbirds.coldcalling.ui.support.FromNumberDefault;
import com.elitale.coldbirds.coldcalling.ui.support.Motion;
import com.elitale.coldbirds.coldcalling.ui.support.RecentContacts;
import com.elitale.coldbirds.coldcalling.ui.support.SmsConversationRow;
import com.elitale.coldbirds.coldcalling.ui.support.SmsSegments;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import javafx.util.StringConverter;

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
    @FXML private VBox                  newMessageOverlay;
    @FXML private TextField             toField;
    @FXML private ListView<ContactSuggestion> suggestionList;

    // ── State ────────────────────────────────────────────────

    private SmsService         smsService;
    private PhoneNumberService phoneNumberService;
    private LeadService        leadService;
    private CallService        callService;

    private final ObservableList<SmsConversationRow> conversations = FXCollections.observableArrayList();
    private final ObservableList<SmsMessage>         thread        = FXCollections.observableArrayList();

    /** True once a remote thread is open (existing conversation or a freshly started one). */
    private final BooleanProperty hasRemote = new SimpleBooleanProperty(false);

    /** True when the open thread's contact has opted out / is on DNC — compose is blocked. */
    private final BooleanProperty optedOut = new SimpleBooleanProperty(false);

    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("h:mm a");

    /** Message ids that should "pop in" on the next thread render (newly sent/received). */
    private final Set<Long> pendingBubbleAnimations = new HashSet<>();

    /** Backing list for the New-message picker. */
    private final ObservableList<ContactSuggestion> suggestions = FXCollections.observableArrayList();

    /** Debounce so each keystroke in the To: field doesn't hammer the lead search. */
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(150));

    private static final int RECENT_LIMIT = 8;

    /** The remote number whose thread is currently open, if any. */
    private Optional<PhoneNumber> selectedRemote = Optional.empty();

    /** Identity the rep picked in New — owns the header for a number shared by >1 lead. */
    private Optional<Lead> sessionIdentity = Optional.empty();

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

    public void setCallService(CallService callService) {
        this.callService = Objects.requireNonNull(callService, "callService must not be null");
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

        // New-message inline composer.
        newMessageOverlay.setVisible(false);
        suggestionList.setItems(suggestions);
        suggestionList.setCellFactory(lv -> new ContactSuggestionCell());
        suggestionList.setPlaceholder(caption("No matches"));
        suggestionList.setOnMouseClicked(e -> { if (e.getClickCount() >= 2) pickSelectedSuggestion(); });
        suggestionList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) { pickSelectedSuggestion(); e.consume(); }
            else if (e.getCode() == KeyCode.ESCAPE) { exitNewMessage(); e.consume(); }
        });
        searchDebounce.setOnFinished(e -> runContactSearch());
        toField.textProperty().addListener((o, a, b) -> searchDebounce.playFromStart());
        toField.setOnKeyPressed(this::onToFieldKey);

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
        startNewMessage();
    }

    /** Open the inline New-message composer and show the recent shortlist. Any-thread safe. */
    public void startNewMessage() {
        Platform.runLater(() -> {
            newMessageOverlay.setVisible(true);
            toField.clear();
            loadRecentShortlist();
            toField.requestFocus();
        });
    }

    @FXML
    private void onCancelNewMessage() {
        exitNewMessage();
    }

    /** Enter in the To: field resolves the selected suggestion (or the top one — paste-and-go). */
    @FXML
    private void onToFieldEnter() {
        pickSelectedSuggestion();
    }

    private void exitNewMessage() {
        newMessageOverlay.setVisible(false);
        suggestions.clear();
        toField.clear();
    }

    private void onToFieldKey(KeyEvent e) {
        switch (e.getCode()) {
            case DOWN -> {
                if (!suggestions.isEmpty()) {
                    suggestionList.getSelectionModel().select(0);
                    suggestionList.requestFocus();
                    e.consume();
                }
            }
            case ESCAPE -> {
                if (!toField.getText().isEmpty()) toField.clear();
                else exitNewMessage();
                e.consume();
            }
            default -> { }
        }
    }

    private void pickSelectedSuggestion() {
        ContactSuggestion picked = suggestionList.getSelectionModel().getSelectedItem();
        if (picked == null && !suggestions.isEmpty()) picked = suggestions.get(0);
        if (picked == null) return;
        sessionIdentity = picked.lead();
        final PhoneNumber number = picked.number();
        exitNewMessage();
        openConversation(number);
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
            Optional<Lead> lead = sessionIdentity.filter(l -> l.phone().equals(remoteNumber))
                    .or(() -> leadService == null ? Optional.empty() : leadService.findByPhone(remoteNumber));
            Optional<Country> country = CountryLookup.byE164(CountryCatalog.ALL, remoteNumber.value());
            List<SmsMessage> msgs = smsService.findThread(remoteNumber);
            List<OwnedNumber> owned = phoneNumberService == null ? List.of() : phoneNumberService.listOwned();
            Optional<PhoneNumberId> continuity = smsService.threadNumber(remoteNumber);
            Optional<OwnedNumber> pinned = phoneNumberService == null
                    ? Optional.empty() : phoneNumberService.getPinnedOutbound();
            Optional<OwnedNumber> defaultFrom = FromNumberDefault.resolve(owned, continuity, pinned);
            return new ThreadLoad(lead, country, msgs, defaultFrom);
        }).thenAcceptAsync(load -> {
            applyThreadHeader(remoteNumber, load.lead(), load.country());
            boolean leadOptedOut = load.lead().map(Lead::dnc).orElse(false);
            optedOut.set(leadOptedOut);
            optOutBanner.setVisible(leadOptedOut);
            load.defaultFrom().ifPresent(this::selectFrom);
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

    private record ThreadLoad(Optional<Lead> lead, Optional<Country> country, List<SmsMessage> messages,
                              Optional<OwnedNumber> defaultFrom) {}

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

    /** Blank field → recent shortlist; otherwise debounced lead search + a raw-number row. */
    private void runContactSearch() {
        final String query = toField.getText() == null ? "" : toField.getText().strip();
        if (query.isBlank()) { loadRecentShortlist(); return; }
        if (leadService == null) return;
        CompletableFuture.supplyAsync(() -> buildSuggestions(query))
                .thenAcceptAsync(this::showSuggestions, Platform::runLater);
    }

    private List<ContactSuggestion> buildSuggestions(String query) {
        List<ContactSuggestion> out = new ArrayList<>(
                leadService.search(query).stream().map(ContactSuggestion::ofLead).toList());
        parseNumber(query).ifPresent(num -> {
            if (out.stream().noneMatch(s -> s.number().equals(num))) {
                out.add(ContactSuggestion.ofNumber(num));
            }
        });
        return out;
    }

    /** The recent shortlist (most-recently called or texted) shown when the To: field is empty. */
    private void loadRecentShortlist() {
        if (callService == null || smsService == null || leadService == null) {
            suggestions.clear();
            return;
        }
        CompletableFuture.supplyAsync(() ->
                RecentContacts.recent(callService.findRecent(50), smsService.findConversations(), RECENT_LIMIT)
                        .stream().map(this::suggestionFor).toList())
                .thenAcceptAsync(this::showSuggestions, Platform::runLater);
    }

    private ContactSuggestion suggestionFor(PhoneNumber number) {
        return leadService.findByPhone(number).map(ContactSuggestion::ofLead)
                .orElseGet(() -> ContactSuggestion.ofNumber(number));
    }

    private void showSuggestions(List<ContactSuggestion> items) {
        suggestions.setAll(items);
        if (!items.isEmpty()) suggestionList.getSelectionModel().select(0);
    }

    private static Optional<PhoneNumber> parseNumber(String raw) {
        String cleaned = raw.replace(" ", "").replace("-", "").replace("(", "").replace(")", "");
        try {
            return Optional.of(new PhoneNumber(cleaned));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void selectFrom(OwnedNumber target) {
        for (OwnedNumber n : fromNumberCombo.getItems()) {
            if (n.id().equals(target.id())) {
                fromNumberCombo.getSelectionModel().select(n);
                return;
            }
        }
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
