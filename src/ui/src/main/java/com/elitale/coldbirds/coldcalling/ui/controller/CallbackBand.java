package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.ui.support.CallbackBuckets;
import com.elitale.coldbirds.coldcalling.ui.support.CallbackBuckets.Buckets;
import com.elitale.coldbirds.coldcalling.ui.support.CallbackBuckets.Entry;
import com.elitale.coldbirds.coldcalling.ui.support.CallbackWhen;
import com.elitale.coldbirds.coldcalling.ui.support.RecentCallFormatter;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The pinned "Callbacks" band — Overdue (red) then Due today (amber), capped to a few rows with a
 * "+N more" expander, with in-band <b>Call · +1d · Next week · Done</b> actions and timezone-aware
 * de-emphasis of callbacks the lead can't be reached for yet. View helper — not unit-tested; the
 * bucketing/ordering logic lives in {@link CallbackBuckets}.
 */
final class CallbackBand {

    private static final int MAX_VISIBLE = 3;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE h:mm a");

    /** Sink for the band's row actions. */
    interface Actions {
        void call(String number);
        void reschedule(String number, Instant newWhen);
        void resolve(String number);
        void open(String number);
    }

    private final Actions actions;
    private final ZoneId zone;
    private final VBox root = new VBox(6);

    private List<CallbackBuckets.Item> items = List.of();
    private List<MissedItem> missed = List.of();
    private boolean expanded;

    /** An unanswered inbound call (a lead calling back) — surfaced above scheduled overdue. */
    record MissedItem(String number, Optional<Lead> lead, Optional<Country> country, Instant at) {
        MissedItem {
            Objects.requireNonNull(number, "number must not be null");
            Objects.requireNonNull(lead, "lead must not be null");
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(at, "at must not be null");
        }
    }

    CallbackBand(Actions actions, ZoneId zone) {
        this.actions = Objects.requireNonNull(actions, "actions must not be null");
        this.zone = zone != null ? zone : ZoneId.systemDefault();
        root.getStyleClass().add("callback-band");
        root.setPadding(new Insets(12, 16, 12, 16));
        root.setVisible(false);
        root.setManaged(false);
    }

    Region node() {
        return root;
    }

    void setItems(List<CallbackBuckets.Item> items) {
        this.items = List.copyOf(items);
        render();
    }

    void setMissedInbound(List<MissedItem> missed) {
        this.missed = List.copyOf(missed);
        render();
    }

    private void render() {
        root.getChildren().clear();
        final Buckets buckets = CallbackBuckets.bucket(items, Instant.now(), zone);
        final List<Entry> due = concat(buckets.overdue(), buckets.dueToday());
        final boolean show = !missed.isEmpty() || !due.isEmpty();
        root.setVisible(show);
        root.setManaged(show);
        if (!show) {
            return;
        }

        final List<HBox> rows = new ArrayList<>();

        if (!missed.isEmpty()) {
            root.getChildren().add(sectionTitle("\uD83D\uDCDE  Missed calls (" + missed.size() + ")"));
            for (MissedItem m : missed) {
                final HBox row = missedRow(m);
                rows.add(row);
                root.getChildren().add(row);
            }
        }

        if (!due.isEmpty()) {
            root.getChildren().add(sectionTitle("\u23F0  Callbacks (" + due.size() + ")"));
            final int limit = expanded ? due.size() : Math.min(MAX_VISIBLE, due.size());
            for (int i = 0; i < limit; i++) {
                final HBox row = rowFor(buckets, due.get(i));
                rows.add(row);
                root.getChildren().add(row);
            }
            if (due.size() > MAX_VISIBLE) {
                final Hyperlink more = new Hyperlink(expanded
                        ? "Show less"
                        : "+" + (due.size() - MAX_VISIBLE) + " more");
                more.setOnAction(e -> { expanded = !expanded; render(); });
                root.getChildren().add(more);
            }
        }
        installArrowNav(rows);
    }

    private static Label sectionTitle(String text) {
        final Label title = new Label(text);
        title.getStyleClass().add("title-3");
        return title;
    }

    private HBox rowFor(Buckets buckets, Entry entry) {
        final String number = entry.item().number();
        final boolean overdue = buckets.overdue().contains(entry);

        final Region dot = new Region();
        dot.getStyleClass().addAll("status-dot", overdue ? "off" : "warn");

        final Label who = new Label(entry.item().lead().map(Lead::displayName)
                .filter(s -> !s.isBlank()).orElse(number));
        who.getStyleClass().add("recent-number");

        final Label when = new Label(promisedLabel(entry, overdue));
        when.getStyleClass().add("caption");

        final Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        final Button call = action("Call", () -> actions.call(number));
        call.getStyleClass().remove("flat");
        call.getStyleClass().add("accent");
        final Button plus1 = action("+1d", () -> snooze(number, CallbackWhen.Preset.TOMORROW_AM));
        final Button nextWeek = action("Next wk", () -> snooze(number, CallbackWhen.Preset.NEXT_WEEK));
        final Button done = action("Done", () -> actions.resolve(number));

        final HBox row = new HBox(8, dot, who, when, grow, call, plus1, nextWeek, done);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("callback-row");
        row.setFocusTraversable(true);
        if (!entry.callableNow()) {
            row.setOpacity(0.55); // their local night — de-emphasise, don't scream
        }
        row.setOnMouseClicked(e -> { if (e.getClickCount() == 2) actions.open(number); });
        // Keyboard-first: Enter = call · S = snooze +1d · N = next week · D = done.
        row.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER -> { actions.call(number); e.consume(); }
                case S     -> { snooze(number, CallbackWhen.Preset.TOMORROW_AM); e.consume(); }
                case N     -> { snooze(number, CallbackWhen.Preset.NEXT_WEEK); e.consume(); }
                case D     -> { actions.resolve(number); e.consume(); }
                default    -> { }
            }
        });
        return row;
    }

    private HBox missedRow(MissedItem m) {
        final Region dot = new Region();
        dot.getStyleClass().addAll("status-dot", "off");

        final Label who = new Label(m.lead().map(Lead::displayName)
                .filter(s -> !s.isBlank()).orElse(m.number()));
        who.getStyleClass().add("recent-number");

        final Label when = new Label("missed call · "
                + RecentCallFormatter.timeAgo(m.at(), Instant.now(), zone));
        when.getStyleClass().add("caption");

        final Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        final Button call = action("Call back", () -> actions.call(m.number()));
        call.getStyleClass().remove("flat");
        call.getStyleClass().add("accent");

        final HBox row = new HBox(8, dot, who, when, grow, call);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("callback-row");
        row.setFocusTraversable(true);
        row.setOnMouseClicked(e -> { if (e.getClickCount() == 2) actions.open(m.number()); });
        row.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) { actions.call(m.number()); e.consume(); }
        });
        return row;
    }

    private void snooze(String number, CallbackWhen.Preset preset) {
        actions.reschedule(number, CallbackWhen.resolve(preset, Instant.now(), zone));
    }

    /** Up/Down move focus between band rows (Tab also works). */
    private void installArrowNav(List<HBox> rows) {
        for (int i = 0; i < rows.size(); i++) {
            final int idx = i;
            rows.get(i).addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.DOWN && idx + 1 < rows.size()) {
                    rows.get(idx + 1).requestFocus();
                    e.consume();
                } else if (e.getCode() == KeyCode.UP && idx - 1 >= 0) {
                    rows.get(idx - 1).requestFocus();
                    e.consume();
                }
            });
        }
    }

    private String promisedLabel(Entry entry, boolean overdue) {
        final String at = WHEN.format(entry.item().scheduledAt().atZone(zone));
        if (overdue) {
            return "promised " + at + " \u00b7 " + humanize(entry.overdueBy()) + " overdue";
        }
        return "promised " + at;
    }

    private static String humanize(Duration d) {
        final long days = d.toDays();
        if (days > 0) {
            return days + "d";
        }
        final long hours = d.toHours();
        if (hours > 0) {
            return hours + "h";
        }
        return Math.max(1, d.toMinutes()) + "m";
    }

    private static Button action(String text, Runnable onClick) {
        final Button button = new Button(text);
        button.getStyleClass().addAll("flat", "recent-action");
        button.setOnAction(e -> onClick.run());
        return button;
    }

    private static List<Entry> concat(List<Entry> a, List<Entry> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
