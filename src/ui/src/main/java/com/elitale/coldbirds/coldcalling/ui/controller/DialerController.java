package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.ui.support.CountryCatalog;
import com.elitale.coldbirds.coldcalling.ui.support.DialNumberFormatter;
import com.elitale.coldbirds.coldcalling.ui.support.FlagImages;
import com.elitale.coldbirds.coldcalling.ui.support.Motion;
import com.elitale.coldbirds.coldcalling.ui.support.RecentCallCell;
import com.elitale.coldbirds.coldcalling.ui.support.RecentCallRow;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Controller for the manual dial-pad view (dialer-view.fxml).
 * <p>
 * Digit buttons on the FXML declare {@code userData="N"} and all fire
 * {@link #onDigitPressed} — one handler for all 12 keys. A country-code picker
 * supplies the E.164 prefix and drives a live local-time / timezone readout.
 * <p>
 * Threading: all methods must be called on the FX Application Thread.
 */
public final class DialerController {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    @FXML private VBox               dialerRoot;
    @FXML private ComboBox<Country>  countrySelector;
    @FXML private Label              localTimeLabel;
    @FXML private TextField          numberField;
    @FXML private Button             callButton;
    @FXML private Button             backspaceButton;
    @FXML private ListView<RecentCallRow> recentCallsList;

    private final ObjectProperty<Country> selectedCountry = new SimpleObjectProperty<>(null);

    /** Last committed country — persisted, and restored on Escape / focus-loss. */
    private Country committedCountry;

    /** All selectable countries shown in the dropdown. */
    private final ObservableList<Country> allCountries = FXCollections.observableArrayList();

    /** Callback invoked with the full E.164 string when the user presses Call. */
    private Consumer<String> onDial = ignored -> {};

    /** Callback invoked when the user changes the active country (for persistence). */
    private Consumer<Country> onCountrySelected = ignored -> {};

    /** Callback invoked with the E.164 number when a recent call is opened. */
    private Consumer<String> onRecentSelected = ignored -> {};

    /** Callback invoked with the E.164 number when the row's Call button is pressed. */
    private Consumer<String> onRecentCall = ignored -> {};

    /** Callback invoked with the E.164 number when the row's Message button is pressed. */
    private Consumer<String> onRecentMessage = ignored -> {};

    private Timeline clock;

    /** Reused flag image beside the local-time readout (avoids per-tick allocation). */
    private final ImageView localFlag = new ImageView();

    /** Default no-arg constructor — required by FXMLLoader. */
    public DialerController() {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Register a callback for when the user initiates a call. */
    public void setOnDial(Consumer<String> callback) {
        this.onDial = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Register a callback invoked whenever the selected country changes. */
    public void setOnCountrySelected(Consumer<Country> callback) {
        this.onCountrySelected = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Populate the country-code picker. Call after {@code FXMLLoader.load()}. */
    public void setCountries(List<Country> countries) {
        Objects.requireNonNull(countries, "countries must not be null");
        allCountries.setAll(countries);
    }

    /** Select the default country by ISO code. Falls back to the first entry. */
    public void selectCountryByIso(String isoCode) {
        Country match = allCountries.stream()
                .filter(c -> c.isoCode().equalsIgnoreCase(isoCode))
                .findFirst()
                .orElse(allCountries.isEmpty() ? null : allCountries.get(0));
        if (match != null) {
            committedCountry = match;
            countrySelector.getSelectionModel().select(match);
        }
    }

    /** Populate the recent calls list. */
    public void setRecentCalls(ObservableList<RecentCallRow> calls) {
        recentCallsList.setItems(Objects.requireNonNull(calls, "calls must not be null"));
    }

    /** Register a callback invoked (with the E.164 number) when a recent call is opened. */
    public void setOnRecentSelected(Consumer<String> callback) {
        this.onRecentSelected = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Register a callback invoked (with the E.164 number) when a row's Call button is pressed. */
    public void setOnRecentCall(Consumer<String> callback) {
        this.onRecentCall = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Register a callback invoked (with the E.164 number) when a row's Message button is pressed. */
    public void setOnRecentMessage(Consumer<String> callback) {
        this.onRecentMessage = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Pre-fill the number field — e.g. when clicking a number in Contacts. */
    public void prefillNumber(String number) {
        Objects.requireNonNull(number, "number must not be null");
        numberField.setText(number);
        numberField.positionCaret(numberField.getLength());
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        configureCountrySelector();
        configureNumberField();
        configureRecentCalls();

        callButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !DialNumberFormatter.isDialable(numberField.getText()),
                numberField.textProperty()));
        backspaceButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> numberField.getText().isEmpty(),
                numberField.textProperty()));

        // A selection change updates the local-time readout and the dial prefix.
        selectedCountry.addListener((obs, old, now) -> {
            updateLocalTime();
            if (now != null) {
                swapDialPrefix(old, now);
            }
        });

        startClock();
    }

    /** Render rows by their display string and open detail on double-click / Enter. */
    private void configureRecentCalls() {
        recentCallsList.setCellFactory(list ->
                new RecentCallCell(ZoneId.systemDefault(), onRecentCall, onRecentMessage));
        recentCallsList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelectedRecent();
            }
        });
        recentCallsList.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                openSelectedRecent();
            }
        });
    }

    private void openSelectedRecent() {
        RecentCallRow selected = recentCallsList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            onRecentSelected.accept(selected.number());
        }
    }

    private void configureCountrySelector() {
        countrySelector.setItems(allCountries);
        countrySelector.setEditable(false);
        countrySelector.setVisibleRowCount(10);
        countrySelector.setConverter(COUNTRY_CONVERTER);
        countrySelector.setCellFactory(list -> new CountryCell());
        // Closed-control display: plain converter text. CountryCell paints an
        // opaque white background for popup rows, which would show as a white box
        // inside the grey pill if reused as the button cell.
        countrySelector.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Country item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : COUNTRY_CONVERTER.toString(item));
            }
        });

        // Pick-only dropdown — no text input. Selecting a country drives the dial
        // prefix + local time via selectedCountry. Persist only once the popup is
        // closed so arrowing through the open list doesn't write settings per step.
        countrySelector.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (now != null) {
                selectedCountry.set(now);
                if (!countrySelector.isShowing()) {
                    commitSelection();
                }
            }
        });
        countrySelector.showingProperty().addListener((obs, was, showing) -> {
            if (!showing) {
                commitSelection();
            }
        });
    }

    /** Persist the selected country once, when it differs from the committed one. */
    private void commitSelection() {
        Country current = selectedCountry.get();
        if (current != null && !current.equals(committedCountry)) {
            committedCountry = current;
            onCountrySelected.accept(current);
        }
    }

    /**
     * Wire the number field: a sanitising formatter, Enter-to-call, and initial
     * focus so the user can type immediately. The native TextField owns the
     * caret, selection, copy/cut/paste, and the context menu.
     */
    private void configureNumberField() {
        numberField.setTextFormatter(new TextFormatter<>(this::sanitizeChange));
        numberField.setOnAction(event -> onCall());
        numberField.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null) {
                Platform.runLater(numberField::requestFocus);
            }
        });
    }

    /**
     * Restrict the field to E.164 characters via {@link DialNumberFormatter}.
     * Non-conforming edits (including pastes) are rewritten to the sanitised
     * text, with the caret collapsed to the end.
     */
    private TextFormatter.Change sanitizeChange(TextFormatter.Change change) {
        if (!change.isContentChange()) {
            return change; // caret / selection moves carry no text to clean
        }
        String proposed = change.getControlNewText();
        String clean = DialNumberFormatter.sanitize(proposed);
        if (clean.equals(proposed)) {
            return change;
        }
        change.setRange(0, change.getControlText().length());
        change.setText(clean);
        change.setCaretPosition(clean.length());
        change.setAnchor(clean.length());
        return change;
    }

    /**
     * On country change, swap the dial prefix only when the field is empty or
     * still holds just the previous country's dial code — never clobber a number
     * the user has actually typed.
     */
    private void swapDialPrefix(Country previous, Country now) {
        String text = numberField.getText().strip();
        String previousDial = (previous != null) ? previous.dialCode() : null;
        if (text.isEmpty() || text.equals(previousDial)) {
            numberField.setText(now.dialCode());
            numberField.positionCaret(numberField.getLength());
        }
    }

    private void startClock() {
        clock = new Timeline(
                new KeyFrame(Duration.ZERO, e -> {
                    updateLocalTime();
                    recentCallsList.refresh();
                }),
                new KeyFrame(Duration.seconds(1)));
        clock.setCycleCount(Animation.INDEFINITE);
        clock.play();
    }

    private void updateLocalTime() {
        Country country = selectedCountry.get();
        if (country == null) {
            localTimeLabel.setText("");
            localTimeLabel.setGraphic(null);
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(country.zone());
        String zoneShort = country.zone().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        Optional<Image> flag = FlagImages.load(country.isoCode());
        if (flag.isPresent()) {
            localFlag.setImage(flag.get());
            localFlag.setFitHeight(14);
            localFlag.setPreserveRatio(true);
            localTimeLabel.setGraphic(localFlag);
            localTimeLabel.setText("%s  ·  %s".formatted(TIME_FORMAT.format(now), zoneShort));
        } else {
            localTimeLabel.setGraphic(null);
            localTimeLabel.setText("%s  ·  %s  ·  %s"
                    .formatted(country.isoCode(), TIME_FORMAT.format(now), zoneShort));
        }
    }

    // ── FXML event handlers ───────────────────────────────────────────────────

    /** Handles every digit/star/hash button via {@code userData}; inserts at the caret. */
    @FXML
    private void onDigitPressed(ActionEvent event) {
        Button btn = (Button) event.getSource();
        Motion.pressFlash(btn);
        Object ud = btn.getUserData();
        String digit = (ud != null) ? ud.toString() : btn.getText();
        numberField.replaceSelection(digit);
        numberField.requestFocus();
    }

    @FXML
    private void onBackspace() {
        numberField.deletePreviousChar();
        // Keep focus on the field so keyboard entry continues after a backspace.
        numberField.requestFocus();
    }

    @FXML
    private void onCall() {
        String visible = numberField.getText();
        if (DialNumberFormatter.isDialable(visible)) {
            onDial.accept(DialNumberFormatter.toE164(visible, selectedCountry.get()));
        }
    }

    /** Editor string form: name + dial code, used for display and matching. */
    private static final StringConverter<Country> COUNTRY_CONVERTER = new StringConverter<>() {
        @Override
        public String toString(Country country) {
            if (country == null) {
                return "";
            }
            return "%s  %s".formatted(country.displayName(), country.dialCode());
        }

        @Override
        public Country fromString(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String trimmed = text.strip();
            return CountryCatalog.byIso(trimmed)
                    .or(() -> CountryCatalog.ALL.stream()
                            .filter(c -> toString(c).equalsIgnoreCase(trimmed))
                            .findFirst())
                    .orElse(null);
        }
    };

    /** Combo popup cell: ISO badge · name (grows) · muted dial code, aligned. */
    /** Combo popup cell: flag (or ISO badge fallback) · name (grows) · muted dial code. */
    private static final class CountryCell extends ListCell<Country> {

        private static final String BG_BASE     = "-fx-background-color: #FFFFFF;";
        private static final String BG_HOVER    =
                "-fx-background-color: #F0F0F3; -fx-background-radius: 8px; -fx-background-insets: 1 4 1 4;";
        private static final String BG_SELECTED =
                "-fx-background-color: #E5F0FF; -fx-background-radius: 8px; -fx-background-insets: 1 4 1 4;";

        private final ImageView flag = new ImageView();
        private final Label badge = new Label();
        private final Label name = new Label();
        private final Label dial = new Label();
        private final HBox layout;

        private CountryCell() {
            flag.setFitHeight(18);
            flag.setPreserveRatio(true);
            flag.getStyleClass().add("country-flag");
            badge.getStyleClass().add("country-badge");
            name.getStyleClass().add("country-name");
            dial.getStyleClass().add("country-dial");
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            // Leading slot starts with the flag; swapped for the badge if no image.
            layout = new HBox(12, flag, name, dial);
            layout.setAlignment(Pos.CENTER_LEFT);
            layout.getStyleClass().add("country-cell");

            // Inline background beats the theme's transparent .list-cell rules,
            // which otherwise win the specificity race and let the dial-pad bleed
            // through the popup. Re-apply on hover/selection state changes.
            selectedProperty().addListener((obs, was, now) -> applyBackground());
            hoverProperty().addListener((obs, was, now) -> applyBackground());
        }

        private void applyBackground() {
            if (isSelected()) {
                setStyle(BG_SELECTED);
            } else if (isHover()) {
                setStyle(BG_HOVER);
            } else {
                setStyle(BG_BASE);
            }
        }

        @Override
        protected void updateItem(Country country, boolean empty) {
            super.updateItem(country, empty);
            // Always paint an opaque background, including empty trailing rows.
            applyBackground();
            if (empty || country == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            name.setText(country.displayName());
            dial.setText(country.dialCode());
            Optional<Image> image = FlagImages.load(country.isoCode());
            if (image.isPresent()) {
                flag.setImage(image.get());
                if (layout.getChildren().get(0) != flag) {
                    layout.getChildren().set(0, flag);
                }
            } else {
                badge.setText(country.isoCode());
                if (layout.getChildren().get(0) != badge) {
                    layout.getChildren().set(0, badge);
                }
            }
            setText(null);
            setGraphic(layout);
        }
    }
}
