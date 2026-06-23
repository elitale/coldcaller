package com.elitale.coldbirds.coldcalling.ui;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.model.Contact;
import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.domain.value.CountryLookup;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.services.CallService;
import com.elitale.coldbirds.coldcalling.services.ContactService;
import com.elitale.coldbirds.coldcalling.services.PhoneNumberService;
import com.elitale.coldbirds.coldcalling.services.SmsService;
import com.elitale.coldbirds.coldcalling.ui.support.ContactEditForm;
import com.elitale.coldbirds.coldcalling.ui.support.FlagImages;
import com.elitale.coldbirds.coldcalling.ui.support.RecentCallFormatter;
import com.elitale.coldbirds.coldcalling.ui.support.RecordingPlayer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Non-blocking, in-window side panel showing everything known about one phone
 * number: quick actions (call, message, add/edit contact, copy), inline
 * disposition + note editing on the latest call, a slim stats strip, the
 * contact card, and a merged call + SMS timeline with recording playback.
 *
 * <p>Docked via {@code BorderPane.setRight(panel.getRoot())} so it never blocks
 * the dialer. Dismissed with {@code Esc} or the close button. Keyboard:
 * {@code C} call · {@code M} message · {@code E} edit · {@code Esc} close ·
 * {@code I/X/V/A/B/D/K} dispositions.
 *
 * <p>Threading: built on the FX thread; DB reads run off-thread and render back
 * on it. One {@link RecordingPlayer} backs all rows.
 */
public final class NumberDetailPanel {

    private static final Logger LOG = LoggerFactory.getLogger(NumberDetailPanel.class);
    private static final double PANEL_WIDTH = 380;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private final CallService callService;
    private final ContactService contactService;
    private final SmsService smsService;
    private final PhoneNumberService phoneNumberService;
    private final List<Country> catalog;
    private final Consumer<String> onCall;
    private final Consumer<String> onMessage;
    private final Runnable onClose;
    private final Runnable onChanged;

    private final ZoneId localZone = ZoneId.systemDefault();
    private final RecordingPlayer player = new RecordingPlayer();
    private final List<Button> playButtons = new ArrayList<>();
    private final List<Button> dispositionButtons = new ArrayList<>();

    private final VBox root = new VBox();
    private final VBox body = new VBox(16);

    private String currentRaw = "";
    private Optional<PhoneNumber> currentNumber = Optional.empty();
    private Optional<Call> latestCall = Optional.empty();
    private boolean editingContact = false;

    public NumberDetailPanel(
            final CallService callService,
            final ContactService contactService,
            final SmsService smsService,
            final PhoneNumberService phoneNumberService,
            final List<Country> catalog,
            final Consumer<String> onCall,
            final Consumer<String> onMessage,
            final Runnable onClose,
            final Runnable onChanged) {

        this.callService = Objects.requireNonNull(callService, "callService");
        this.contactService = Objects.requireNonNull(contactService, "contactService");
        this.smsService = Objects.requireNonNull(smsService, "smsService");
        this.phoneNumberService = Objects.requireNonNull(phoneNumberService, "phoneNumberService");
        this.catalog = List.copyOf(Objects.requireNonNull(catalog, "catalog"));
        this.onCall = Objects.requireNonNull(onCall, "onCall");
        this.onMessage = Objects.requireNonNull(onMessage, "onMessage");
        this.onClose = Objects.requireNonNull(onClose, "onClose");
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");

        root.getStyleClass().add("detail-panel");
        root.setPrefWidth(PANEL_WIDTH);
        root.setMinWidth(PANEL_WIDTH);

        body.setPadding(new Insets(20));
        final ScrollPane scroller = new ScrollPane(body);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("detail-scroll");
        VBox.setVgrow(scroller, Priority.ALWAYS);
        root.getChildren().add(scroller);

        root.setFocusTraversable(true);
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        player.setOnChange(() -> Platform.runLater(this::refreshPlayButtons));
    }

    /** The node to dock into the layout's right slot. */
    public Region getRoot() {
        return root;
    }

    /** Load and render the panel for {@code rawNumber}. Call on the FX thread. */
    public void show(final String rawNumber) {
        Objects.requireNonNull(rawNumber, "rawNumber");
        // Only drop edit mode when switching to a different number; re-showing the
        // same number (e.g. after pressing Edit) must preserve the editing flag.
        if (!rawNumber.equals(this.currentRaw)) {
            this.editingContact = false;
        }
        this.currentRaw = rawNumber;
        body.getChildren().setAll(muted("Loading…"));
        Platform.runLater(root::requestFocus);

        Optional<PhoneNumber> parsed;
        try {
            parsed = Optional.of(new PhoneNumber(rawNumber));
        } catch (final IllegalArgumentException ex) {
            parsed = Optional.empty();
        }
        this.currentNumber = parsed;

        if (parsed.isEmpty()) {
            renderInvalid();
            return;
        }
        final PhoneNumber number = parsed.get();
        CompletableFuture
                .supplyAsync(() -> new Loaded(
                        contactService.findByPhone(number),
                        callService.findByRemoteNumber(number),
                        smsService.findThread(number),
                        ownedNumbersById()))
                .whenComplete((data, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        LOG.error("Failed to load detail panel for {}", number.value(), error);
                        renderError(error);
                        return;
                    }
                    try {
                        renderLoaded(data);
                    } catch (final RuntimeException ex) {
                        LOG.error("Failed to render detail panel for {}", number.value(), ex);
                        renderError(ex);
                    }
                }));
    }

    /** Stop any playback and release resources. */
    public void dispose() {
        player.close();
    }

    /** Stop any in-progress recording playback (e.g. when the panel is hidden). */
    public void stopPlayback() {
        player.stop();
    }

    /** All owned numbers keyed by id, to resolve the local number used per call. */
    private Map<PhoneNumberId, String> ownedNumbersById() {
        final Map<PhoneNumberId, String> byId = new HashMap<>();
        for (final OwnedNumber owned : phoneNumberService.listAll()) {
            byId.put(owned.id(), owned.number().value());
        }
        return byId;
    }

    private record Loaded(Optional<Contact> contact, List<Call> calls, List<SmsMessage> sms,
                          Map<PhoneNumberId, String> ownedNumbers) {}

    // ── Rendering ───────────────────────────────────────────────────────────────

    private void renderInvalid() {
        latestCall = Optional.empty();
        final VBox box = new VBox(12);
        box.getChildren().addAll(
                headerBar(currentRaw),
                muted("Not a valid phone number — can't call, message, or save it."));
        body.getChildren().setAll(box);
    }

    private void renderError(final Throwable error) {
        latestCall = Optional.empty();
        final Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        final VBox box = new VBox(12);
        box.getChildren().addAll(
                headerBar(currentRaw),
                muted("Couldn't load this number: " + cause.getMessage()));
        body.getChildren().setAll(box);
    }

    private void renderLoaded(final Loaded data) {
        playButtons.clear();
        latestCall = data.calls().isEmpty() ? Optional.empty() : Optional.of(data.calls().get(0));

        final String primary = data.contact()
                .map(Contact::displayName)
                .filter(s -> !s.equals(currentRaw))
                .orElse(currentRaw);

        body.getChildren().setAll(
                headerBar(primary),
                subHeader(),
                actionBar(data.contact()),
                new Separator());

        if (editingContact) {
            body.getChildren().add(new ContactEditForm(
                    contactService, currentNumber.orElseThrow(), data.contact(),
                    () -> { editingContact = false; onChanged.run(); show(currentRaw); },
                    () -> { editingContact = false; show(currentRaw); }).getRoot());
            return;
        }

        body.getChildren().addAll(
                dispositionZone(),
                noteZone(),
                new Separator(),
                statsStrip(data.calls()),
                contactCard(data.contact()),
                new Separator(),
                timeline(data.calls(), data.sms(), data.ownedNumbers()));
        refreshPlayButtons();
    }

    private HBox headerBar(final String primary) {
        final Label name = new Label(primary);
        name.getStyleClass().add("detail-primary");
        name.setWrapText(true);
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final Button close = new Button("✕");
        close.getStyleClass().addAll("flat", "detail-close");
        close.setOnAction(e -> onClose.run());
        final HBox bar = new HBox(8, name, spacer, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private VBox subHeader() {
        final Optional<Country> country = CountryLookup.byE164(catalog, currentRaw);

        final Label number = new Label(currentRaw);
        number.getStyleClass().add("detail-number");
        number.setMinWidth(Region.USE_PREF_SIZE); // never ellipsize the number
        final Button copy = new Button("Copy");
        copy.getStyleClass().addAll("flat", "detail-action");
        copy.setMinWidth(Region.USE_PREF_SIZE);
        copy.setOnAction(e -> copyNumber());

        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final HBox numberRow = new HBox(8, number, spacer, copy);
        numberRow.setAlignment(Pos.CENTER_LEFT);

        final HBox locRow = new HBox(6);
        locRow.setAlignment(Pos.CENTER_LEFT);
        country.ifPresentOrElse(c -> {
            FlagImages.load(c.isoCode()).ifPresent(img -> {
                final ImageView flag = new ImageView(img);
                flag.setFitHeight(14);
                flag.setPreserveRatio(true);
                locRow.getChildren().add(flag);
            });
            final Label loc = new Label(
                    c.displayName() + "  ·  " + Instant.now().atZone(c.zone()).format(CLOCK) + " local");
            loc.getStyleClass().add("caption");
            locRow.getChildren().add(loc);
        }, () -> {
            final Label loc = new Label("Unknown");
            loc.getStyleClass().add("caption");
            locRow.getChildren().add(loc);
        });

        return new VBox(4, numberRow, locRow);
    }

    private HBox actionBar(final Optional<Contact> contact) {
        final boolean valid = currentNumber.isPresent();
        final Button call = new Button("Call");
        call.getStyleClass().addAll("accent", "detail-action");
        call.setOnAction(e -> onCall.accept(currentRaw));
        call.setDisable(!valid);

        final Button message = new Button("Message");
        message.getStyleClass().addAll("flat", "detail-action");
        message.setOnAction(e -> onMessage.accept(currentRaw));
        message.setDisable(!valid);

        final Button edit = new Button(contact.isPresent() ? "Edit" : "+ Add");
        edit.getStyleClass().addAll("flat", "detail-action");
        edit.setOnAction(e -> openContactEditor());
        edit.setDisable(!valid);

        final HBox bar = new HBox(8, call, message, edit);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private VBox dispositionZone() {
        dispositionButtons.clear();
        final FlowPane chips = new FlowPane(8, 8);
        final Optional<CallDisposition> active = latestCall.flatMap(Call::disposition);
        for (final Chip chip : CHIPS) {
            final Button b = new Button(chip.label());
            b.getStyleClass().add("detail-chip");
            if (active.map(d -> d.getClass()).map(cl -> cl.equals(chip.sample().get().getClass())).orElse(false)) {
                b.getStyleClass().add("detail-chip-active");
            }
            b.setDisable(latestCall.isEmpty());
            b.setOnAction(e -> applyDisposition(chip.sample().get(), b));
            dispositionButtons.add(b);
            chips.getChildren().add(b);
        }
        final VBox box = new VBox(6, sectionTitle("Disposition"), chips);
        if (latestCall.isEmpty()) {
            box.getChildren().add(muted("No call to disposition yet."));
        }
        return box;
    }

    private VBox noteZone() {
        final TextField note = new TextField(latestCall.flatMap(Call::notes).orElse(""));
        note.setPromptText("Add a note — saves automatically");
        note.setDisable(latestCall.isEmpty());

        final Label status = new Label();
        status.getStyleClass().add("detail-save-status");
        status.setVisible(false);
        status.setManaged(false);

        final String[] saved = { note.getText() };
        final PauseTransition debounce = new PauseTransition(javafx.util.Duration.millis(600));
        final Runnable persist = () -> {
            final String text = note.getText();
            if (text.equals(saved[0])) return;
            saved[0] = text;
            saveNote(text, status);
        };
        debounce.setOnFinished(e -> persist.run());

        note.textProperty().addListener((obs, old, val) -> {
            if (note.isDisabled()) return;
            setStatus(status, "Saving…");
            debounce.playFromStart();
        });
        note.setOnAction(e -> { debounce.stop(); persist.run(); });        // Enter saves now
        note.focusedProperty().addListener((obs, was, has) -> {
            if (!has) { debounce.stop(); persist.run(); }                  // blur saves too
        });

        return new VBox(6, sectionTitle("Note"), note, status);
    }

    private VBox statsStrip(final List<Call> calls) {
        final long talkMs = calls.stream().mapToLong(c -> c.durationMs().orElse(0L)).sum();
        final String last = calls.isEmpty() ? "—"
                : RecentCallFormatter.timeAgo(calls.get(0).startedAt(), Instant.now(), localZone);
        final HBox strip = new HBox(24,
                stat("Last contacted", last),
                stat("Total calls", Integer.toString(calls.size())),
                stat("Talk time", formatDuration(talkMs)));
        strip.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, strip);
    }

    private VBox contactCard(final Optional<Contact> contact) {
        final VBox box = new VBox(4, sectionTitle("Lead / Contact"));
        if (contact.isEmpty()) {
            box.getChildren().add(muted("No contact saved for this number."));
            return box;
        }
        final Contact c = contact.get();
        final Label name = new Label(c.displayName() + (c.dnc() ? "   • DNC" : ""));
        name.getStyleClass().add("detail-contact-name");
        box.getChildren().add(name);

        final String org = List.of(c.company().orElse(""), c.title().orElse("")).stream()
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + " · " + b)
                .orElse("");
        if (!org.isBlank()) box.getChildren().add(muted(org));
        c.email().filter(s -> !s.isBlank()).ifPresent(e -> box.getChildren().add(muted(e)));
        if (!c.tags().isEmpty()) box.getChildren().add(muted("Tags: " + String.join(", ", c.tags())));
        c.notes().filter(s -> !s.isBlank()).ifPresent(n -> {
            final Label notes = new Label(n);
            notes.setWrapText(true);
            box.getChildren().add(notes);
        });
        return box;
    }

    private VBox timeline(final List<Call> calls, final List<SmsMessage> sms,
                          final Map<PhoneNumberId, String> ownedNumbers) {
        final int total = calls.size() + sms.size();
        final VBox box = new VBox(8, sectionTitle("Timeline (" + total + ")"));
        if (total == 0) {
            box.getChildren().add(muted("No calls or messages for this number yet."));
            return box;
        }
        final List<Entry> entries = new ArrayList<>();
        for (final Call c : calls) entries.add(new Entry(c.startedAt(), callRow(c, ownedNumbers)));
        for (final SmsMessage m : sms) entries.add(new Entry(m.sentAt(), smsRow(m)));
        entries.sort(Comparator.comparing(Entry::at).reversed());
        for (final Entry e : entries) box.getChildren().add(e.node());
        return box;
    }

    private record Entry(Instant at, Region node) {}

    private HBox callRow(final Call call, final Map<PhoneNumberId, String> ownedNumbers) {
        final String arrow = call.direction() == CallDirection.OUTBOUND ? "↗" : "↙";
        final String primary = arrow + "  " + STAMP.format(call.startedAt().atZone(localZone))
                + "   ·   " + formatDuration(call.durationMs().orElse(0L));
        final String dispo = call.disposition()
                .map(NumberDetailPanel::dispositionLabel)
                .orElse("—");
        final String prep = call.direction() == CallDirection.OUTBOUND ? "From " : "To ";
        final String secondary = Optional.ofNullable(ownedNumbers.get(call.phoneNumberId()))
                .map(local -> dispo + "   ·   " + prep + local)
                .orElse(dispo);
        final VBox info = new VBox(2, new Label(primary), muted(secondary));
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final HBox row = new HBox(12, info, spacer, recordingControl(call));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 0, 8, 0));
        return row;
    }

    private HBox smsRow(final SmsMessage sms) {
        final String arrow = sms.direction() == CallDirection.OUTBOUND ? "↗" : "↙";
        final Label head = new Label(arrow + "  ✉  " + STAMP.format(sms.sentAt().atZone(localZone)));
        final Label text = new Label(sms.body());
        text.setWrapText(true);
        text.getStyleClass().add("caption");
        final VBox info = new VBox(2, head, text);
        final HBox row = new HBox(12, info);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 0, 8, 0));
        return row;
    }

    private Region recordingControl(final Call call) {
        final Optional<Path> recording = call.recordingPath()
                .map(Path::of)
                .filter(Files::exists);
        if (recording.isEmpty()) {
            return muted("No recording");
        }
        final Path file = recording.get();
        final Button play = new Button("▶ Play");
        play.getStyleClass().addAll("flat", "detail-action");
        play.setOnAction(e -> player.toggle(file));
        play.setUserData(file);
        playButtons.add(play);
        return play;
    }

    // ── Actions ─────────────────────────────────────────────────────────────────

    private void applyDisposition(final CallDisposition disposition, final Button clicked) {
        // Restyle in place so a pending note edit isn't lost to a panel rebuild.
        for (final Button b : dispositionButtons) b.getStyleClass().remove("detail-chip-active");
        clicked.getStyleClass().add("detail-chip-active");
        latestCall.ifPresent(call -> CompletableFuture
                .supplyAsync(() -> callService.updateDisposition(call.id(), disposition))
                .thenAccept(r -> Platform.runLater(onChanged)));
    }

    private void saveNote(final String text, final Label status) {
        latestCall.ifPresent(call -> CompletableFuture
                .supplyAsync(() -> callService.updateNotes(call.id(), text))
                .thenAccept(r -> Platform.runLater(() -> {
                    setStatus(status, "Saved ✓");
                    onChanged.run();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> setStatus(status, "Save failed"));
                    return null;
                }));
    }

    private static void setStatus(final Label status, final String text) {
        status.setText(text);
        final boolean show = !text.isBlank();
        status.setVisible(show);
        status.setManaged(show);
    }

    private void openContactEditor() {
        if (currentNumber.isEmpty()) return;
        editingContact = true;
        show(currentRaw);
    }

    private void copyNumber() {
        final ClipboardContent content = new ClipboardContent();
        content.putString(currentRaw);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void refreshPlayButtons() {
        for (final Button b : playButtons) {
            final Path file = (Path) b.getUserData();
            b.setText(player.isPlaying(file) ? "⏸ Pause" : "▶ Play");
        }
    }

    // ── Keyboard ────────────────────────────────────────────────────────────────

    private void onKey(final KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            onClose.run();
            event.consume();
            return;
        }
        // Don't hijack typing inside the note/edit fields.
        if (event.getTarget() instanceof TextInputControl) {
            return;
        }
        final boolean handled = switch (event.getCode()) {
            case C -> { onCall.accept(currentRaw); yield currentNumber.isPresent(); }
            case M -> { onMessage.accept(currentRaw); yield currentNumber.isPresent(); }
            case E -> { openContactEditor(); yield currentNumber.isPresent(); }
            case I -> applyHotkeyDisposition(new CallDisposition.Interested());
            case X -> applyHotkeyDisposition(new CallDisposition.NotInterested());
            case V -> applyHotkeyDisposition(new CallDisposition.Voicemail());
            case A -> applyHotkeyDisposition(new CallDisposition.NoAnswer());
            case B -> applyHotkeyDisposition(new CallDisposition.Busy());
            case D -> applyHotkeyDisposition(new CallDisposition.DNC());
            case K -> applyHotkeyDisposition(new CallDisposition.Callback(Instant.now().plus(Duration.ofDays(1))));
            default -> false;
        };
        if (handled) event.consume();
    }

    private boolean applyHotkeyDisposition(final CallDisposition disposition) {
        if (latestCall.isEmpty()) return false;
        latestCall.ifPresent(call -> CompletableFuture
                .supplyAsync(() -> callService.updateDisposition(call.id(), disposition))
                .thenAccept(r -> Platform.runLater(() -> { onChanged.run(); show(currentRaw); })));
        return true;
    }

    // ── Disposition chips ───────────────────────────────────────────────────────

    private record Chip(String label, Supplier<CallDisposition> sample) {}

    private static final List<Chip> CHIPS = List.of(
            new Chip("Interested",     CallDisposition.Interested::new),
            new Chip("Not interested", CallDisposition.NotInterested::new),
            new Chip("Callback",       () -> new CallDisposition.Callback(Instant.now().plus(Duration.ofDays(1)))),
            new Chip("Voicemail",      CallDisposition.Voicemail::new),
            new Chip("No answer",      CallDisposition.NoAnswer::new),
            new Chip("Busy",           CallDisposition.Busy::new),
            new Chip("DNC",            CallDisposition.DNC::new));

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static VBox stat(final String label, final String value) {
        final Label v = new Label(value);
        v.getStyleClass().add("detail-stat-value");
        final Label l = new Label(label);
        l.getStyleClass().add("caption");
        return new VBox(2, v, l);
    }

    private static Label sectionTitle(final String text) {
        final Label label = new Label(text);
        label.getStyleClass().add("detail-section-title");
        return label;
    }

    private static Label muted(final String text) {
        final Label label = new Label(text);
        label.getStyleClass().add("caption");
        return label;
    }

    private static String dispositionLabel(final CallDisposition disposition) {
        return switch (disposition) {
            case CallDisposition.Interested ignored    -> "Interested";
            case CallDisposition.NotInterested ignored -> "Not interested";
            case CallDisposition.Callback ignored      -> "Callback";
            case CallDisposition.Voicemail ignored     -> "Voicemail";
            case CallDisposition.NoAnswer ignored      -> "No answer";
            case CallDisposition.Busy ignored          -> "Busy";
            case CallDisposition.DNC ignored           -> "Do not call";
            case CallDisposition.Failed f              -> "Failed: " + f.reason();
        };
    }

    private static String formatDuration(final long durationMs) {
        final long totalSeconds = Math.max(0L, durationMs) / 1000L;
        return "%d:%02d".formatted(totalSeconds / 60L, totalSeconds % 60L);
    }
}
