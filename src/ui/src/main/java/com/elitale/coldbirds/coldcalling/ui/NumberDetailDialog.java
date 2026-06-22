package com.elitale.coldbirds.coldcalling.ui;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.model.Contact;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.domain.value.CountryLookup;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.CallService;
import com.elitale.coldbirds.coldcalling.services.ContactService;
import com.elitale.coldbirds.coldcalling.ui.support.RecordingPlayer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Modal dialog showing everything known about one phone number: the matching
 * contact / lead (if any) and the full call history with in-app playback of any
 * recordings. Opened from the dialer's Recent Calls list.
 *
 * <p>Threading: built on the FX thread; the DB lookups run off-thread and the
 * result is rendered back on the FX thread. One {@link RecordingPlayer} backs
 * all rows so only a single recording plays at a time.
 */
public final class NumberDetailDialog {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    private final Stage stage;
    private final String e164;
    private final CallService callService;
    private final ContactService contactService;
    private final List<Country> catalog;
    private final ZoneId zone = ZoneId.systemDefault();

    private final RecordingPlayer player = new RecordingPlayer();
    private final Map<Button, Path> playButtons = new java.util.HashMap<>();
    private final VBox content = new VBox(16);

    private NumberDetailDialog(
            final Window owner,
            final String e164,
            final CallService callService,
            final ContactService contactService,
            final List<Country> catalog) {

        this.e164 = Objects.requireNonNull(e164, "e164 must not be null");
        this.callService = Objects.requireNonNull(callService, "callService must not be null");
        this.contactService = Objects.requireNonNull(contactService, "contactService must not be null");
        this.catalog = List.copyOf(Objects.requireNonNull(catalog, "catalog must not be null"));

        this.stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("Number details");

        content.setPadding(new Insets(24));
        content.getChildren().add(muted("Loading…"));

        final ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        stage.setScene(new Scene(scroller, 460, 560));

        player.setOnChange(() -> Platform.runLater(this::refreshButtons));
        stage.setOnHidden(event -> player.close());
    }

    /** Build and show the dialog. Must be called on the FX Application Thread. */
    public static void show(
            final Window owner,
            final String e164,
            final CallService callService,
            final ContactService contactService,
            final List<Country> catalog) {

        final NumberDetailDialog dialog =
                new NumberDetailDialog(owner, e164, callService, contactService, catalog);
        dialog.load();
        dialog.stage.show();
    }

    private void load() {
        final PhoneNumber number = new PhoneNumber(e164);
        CompletableFuture
                .supplyAsync(() -> new Loaded(
                        contactService.findByPhone(number),
                        callService.findByRemoteNumber(number)))
                .thenAccept(data -> Platform.runLater(() -> populate(data)));
    }

    private record Loaded(Optional<Contact> contact, List<Call> calls) {}

    private void populate(final Loaded data) {
        playButtons.clear();
        content.getChildren().setAll(header(), contactCard(data.contact()), new Separator());
        content.getChildren().add(callsSection(data.calls()));
        refreshButtons();
    }

    // ── Sections ───────────────────────────────────────────────────────────────

    private VBox header() {
        final Optional<Country> country = CountryLookup.byE164(catalog, e164);
        final Label number = new Label(e164);
        number.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        final String loc = country
                .map(c -> c.flag() + "  " + c.displayName() + " (" + c.dialCode() + ")")
                .orElse("Unknown country");
        return new VBox(2, number, muted(loc));
    }

    private VBox contactCard(final Optional<Contact> contact) {
        if (contact.isEmpty()) {
            final VBox box = new VBox(4, sectionTitle("Lead / Contact"),
                    muted("No contact saved for this number."));
            return box;
        }
        final Contact c = contact.get();
        final VBox box = new VBox(4);
        box.getChildren().add(sectionTitle("Lead / Contact"));

        final Label name = new Label(c.displayName() + (c.dnc() ? "   • DNC" : ""));
        name.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        box.getChildren().add(name);

        final String org = List.of(c.company().orElse(""), c.title().orElse("")).stream()
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + " · " + b)
                .orElse("");
        if (!org.isBlank()) {
            box.getChildren().add(muted(org));
        }
        c.email().filter(s -> !s.isBlank()).ifPresent(e -> box.getChildren().add(muted(e)));
        if (!c.tags().isEmpty()) {
            box.getChildren().add(muted("Tags: " + String.join(", ", c.tags())));
        }
        c.notes().filter(s -> !s.isBlank()).ifPresent(n -> {
            final Label notes = new Label(n);
            notes.setWrapText(true);
            box.getChildren().add(notes);
        });
        return box;
    }

    private VBox callsSection(final List<Call> calls) {
        final VBox box = new VBox(8);
        box.getChildren().add(sectionTitle("Call history (" + calls.size() + ")"));
        if (calls.isEmpty()) {
            box.getChildren().add(muted("No calls recorded for this number yet."));
            return box;
        }
        for (final Call call : calls) {
            box.getChildren().add(callRow(call));
        }
        return box;
    }

    private HBox callRow(final Call call) {
        final String arrow = call.direction() == CallDirection.OUTBOUND ? "↗" : "↙";
        final String primary = arrow + "  " + STAMP.format(call.startedAt().atZone(zone))
                + "   ·   " + formatDuration(call.durationMs().orElse(0L));
        final String secondary = call.disposition().map(NumberDetailDialog::dispositionLabel)
                .orElse("—");

        final VBox info = new VBox(2, new Label(primary), muted(secondary));
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        final HBox row = new HBox(12, info, spacer, recordingControl(call));
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
        play.setOnAction(event -> player.toggle(file));
        playButtons.put(play, file);
        return play;
    }

    private void refreshButtons() {
        playButtons.forEach((button, file) ->
                button.setText(player.isPlaying(file) ? "⏸ Pause" : "▶ Play"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static Label sectionTitle(final String text) {
        final Label label = new Label(text);
        label.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-opacity: 0.7;");
        return label;
    }

    private static Label muted(final String text) {
        final Label label = new Label(text);
        label.setStyle("-fx-opacity: 0.6;");
        return label;
    }

    private static String dispositionLabel(final CallDisposition disposition) {
        return switch (disposition) {
            case CallDisposition.Interested ignored    -> "Interested";
            case CallDisposition.NotInterested ignored  -> "Not interested";
            case CallDisposition.Callback ignored       -> "Callback";
            case CallDisposition.Voicemail ignored      -> "Voicemail";
            case CallDisposition.NoAnswer ignored       -> "No answer";
            case CallDisposition.Busy ignored           -> "Busy";
            case CallDisposition.DNC ignored            -> "Do not call";
            case CallDisposition.Failed f               -> "Failed: " + f.reason();
        };
    }

    private static String formatDuration(final long durationMs) {
        final long totalSeconds = Math.max(0L, durationMs) / 1000L;
        final long minutes = totalSeconds / 60L;
        final long seconds = totalSeconds % 60L;
        return "%d:%02d".formatted(minutes, seconds);
    }
}
