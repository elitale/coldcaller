package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.ui.support.CountryCatalog;
import com.elitale.coldbirds.coldcalling.ui.support.CountrySearch;
import com.elitale.coldbirds.coldcalling.ui.support.FlagImages;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

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

    /** Characters accepted from the physical keyboard for dialing. */
    private static final String DIALPAD_KEYS = "0123456789*#+";

    @FXML private VBox               dialerRoot;
    @FXML private ComboBox<Country>  countrySelector;
    @FXML private Label              localTimeLabel;
    @FXML private Label              numberDisplay;
    @FXML private HBox               numberDisplayBox;
    @FXML private Region             inputCaret;
    @FXML private Button             callButton;
    @FXML private Button             backspaceButton;
    @FXML private ListView<String>   recentCallsList;

    private final StringProperty          dialedNumber    = new SimpleStringProperty("");
    private final ObjectProperty<Country> selectedCountry = new SimpleObjectProperty<>(null);

    /** Master, unfiltered country list; the combo shows a filtered view of this. */
    private final ObservableList<Country> allCountries = FXCollections.observableArrayList();
    private FilteredList<Country>         filteredCountries;

    /** Callback invoked with the full E.164 string when the user presses Call. */
    private Consumer<String> onDial = ignored -> {};

    /** Callback invoked when the user changes the active country (for persistence). */
    private Consumer<Country> onCountrySelected = ignored -> {};

    private Timeline clock;

    /** Blinking caret animation for the focused number input. */
    private Timeline caretBlink;

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
        filteredCountries.setPredicate(c -> true);
    }

    /** Select the default country by ISO code. Falls back to the first entry. */
    public void selectCountryByIso(String isoCode) {
        filteredCountries.setPredicate(c -> true);
        Country match = allCountries.stream()
                .filter(c -> c.isoCode().equalsIgnoreCase(isoCode))
                .findFirst()
                .orElse(allCountries.isEmpty() ? null : allCountries.get(0));
        if (match != null) {
            countrySelector.getSelectionModel().select(match);
            setEditorText(match);
        }
    }

    /** Populate the recent calls list. */
    public void setRecentCalls(ObservableList<String> calls) {
        recentCallsList.setItems(Objects.requireNonNull(calls, "calls must not be null"));
    }

    /** Pre-fill the number display — e.g. when clicking a number in Contacts. */
    public void prefillNumber(String number) {
        dialedNumber.set(Objects.requireNonNull(number, "number must not be null"));
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        configureCountrySelector();
        configureKeyboardEntry();
        configureCaret();

        numberDisplay.textProperty().bind(Bindings.createStringBinding(
                this::composeDisplay, dialedNumber, selectedCountry));

        callButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> dialedNumber.get().isBlank(), dialedNumber));
        backspaceButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> dialedNumber.get().isEmpty(), dialedNumber));

        selectedCountry.addListener((obs, old, now) -> {
            updateLocalTime();
            if (now != null) {
                onCountrySelected.accept(now);
                // Keep the closed editor in sync with the model even if the combo's
                // own value diverged (it can be nulled during filtered searches).
                if (!countrySelector.isShowing()) {
                    setEditorText(now);
                }
            }
        });

        startClock();
    }

    private void configureCountrySelector() {
        filteredCountries = new FilteredList<>(allCountries, c -> true);
        countrySelector.setItems(filteredCountries);
        countrySelector.setEditable(true);
        countrySelector.setVisibleRowCount(10);
        countrySelector.setConverter(COUNTRY_CONVERTER);
        countrySelector.setCellFactory(list -> new CountryCell());

        // Track real selections only. While searching, the FilteredList may hide
        // the selected row (selectedItem → null); we ignore those transient nulls
        // so the chosen country — and the dial prefix — is never lost mid-typing.
        countrySelector.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (now != null) {
                selectedCountry.set(now);
            }
        });

        TextField editor = countrySelector.getEditor();
        // Filter only on real keystrokes. Listening to the editor's textProperty
        // also fired during programmatic updates (selection, close restore),
        // which re-ran the search mid-selection and made picking a country fail.
        editor.addEventHandler(KeyEvent.KEY_RELEASED, event -> {
            KeyCode code = event.getCode();
            if (code == KeyCode.ENTER) {
                commitFirstMatch();
                return;
            }
            boolean editsText = code.isLetterKey() || code.isDigitKey()
                    || code == KeyCode.BACK_SPACE || code == KeyCode.DELETE
                    || code == KeyCode.SPACE;
            if (!editsText) {
                return; // navigation / modifier keys must not re-filter
            }
            String query = editor.getText();
            filteredCountries.setPredicate(c -> CountrySearch.matches(c, query));
            if (!countrySelector.isShowing() && countrySelector.getScene() != null) {
                countrySelector.show();
            }
        });

        // When the popup closes, restore the full list, re-assert the visual
        // selection (filtering may have cleared it), and reset the editor text so
        // a stale search query never lingers in the closed control.
        countrySelector.showingProperty().addListener((obs, was, showing) -> {
            if (!showing) {
                filteredCountries.setPredicate(c -> true);
                Country current = selectedCountry.get();
                if (current != null && countrySelector.getSelectionModel().getSelectedItem() == null) {
                    countrySelector.getSelectionModel().select(current);
                }
                setEditorText(current);
            }
        });
    }

    /** Commit the top filtered match (used when the user presses Enter on a search). */
    private void commitFirstMatch() {
        if (!filteredCountries.isEmpty()) {
            countrySelector.getSelectionModel().select(filteredCountries.get(0));
            countrySelector.hide();
        }
    }

    private void setEditorText(Country country) {
        countrySelector.getEditor().setText(COUNTRY_CONVERTER.toString(country));
    }

    /** Wire physical-keyboard dialing and grab focus so typing works immediately. */
    private void configureKeyboardEntry() {
        dialerRoot.setFocusTraversable(true);
        dialerRoot.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        dialerRoot.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null) {
                Platform.runLater(dialerRoot::requestFocus);
            }
        });
    }

    /** Translate physical key presses into dial-pad input (digits, * # +, backspace, enter). */
    private void onKeyPressed(KeyEvent event) {
        // Never hijack keys while the user is searching in the country box.
        if (countrySelector.isShowing() || countrySelector.getEditor().isFocused()) {
            return;
        }
        KeyCode code = event.getCode();
        if (code == KeyCode.BACK_SPACE || code == KeyCode.DELETE) {
            onBackspace();
            event.consume();
            return;
        }
        if (code == KeyCode.ENTER) {
            if (!callButton.isDisabled()) {
                onCall();
            }
            event.consume();
            return;
        }
        String text = event.getText();
        if (text != null && text.length() == 1 && DIALPAD_KEYS.indexOf(text.charAt(0)) >= 0) {
            dialedNumber.set(dialedNumber.get() + text);
            focusDialpad();
            event.consume();
        }
    }

    /** Return key focus to the dialer root so keyboard entry keeps working. */
    private void focusDialpad() {
        if (dialerRoot != null) {
            dialerRoot.requestFocus();
        }
    }

    /**
     * Show a blinking text caret and an accent focus ring on the number input
     * whenever the dialer has key focus — mirroring an HTML text field.
     */
    private void configureCaret() {
        caretBlink = new Timeline(
                new KeyFrame(Duration.ZERO, e -> inputCaret.setOpacity(1)),
                new KeyFrame(Duration.seconds(0.5), e -> inputCaret.setOpacity(0)),
                new KeyFrame(Duration.seconds(1.0)));
        caretBlink.setCycleCount(Animation.INDEFINITE);

        dialerRoot.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                if (!numberDisplayBox.getStyleClass().contains("input-focused")) {
                    numberDisplayBox.getStyleClass().add("input-focused");
                }
                caretBlink.playFromStart();
            } else {
                numberDisplayBox.getStyleClass().remove("input-focused");
                caretBlink.stop();
                inputCaret.setOpacity(0);
            }
        });
    }

    private void startClock() {
        clock = new Timeline(
                new KeyFrame(Duration.ZERO, e -> updateLocalTime()),
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

    private String composeDisplay() {
        String typed = dialedNumber.get();
        if (typed.startsWith("+")) {
            return typed;
        }
        Country country = selectedCountry.get();
        String prefix = (country != null) ? country.dialCode() + " " : "";
        return prefix + typed;
    }

    // ── FXML event handlers ───────────────────────────────────────────────────

    /** Handles every digit/star/hash button via {@code userData}. */
    @FXML
    private void onDigitPressed(ActionEvent event) {
        Button btn = (Button) event.getSource();
        Object ud = btn.getUserData();
        String digit = (ud != null) ? ud.toString() : btn.getText();
        dialedNumber.set(dialedNumber.get() + digit);
        focusDialpad();
    }

    @FXML
    private void onBackspace() {
        String current = dialedNumber.get();
        if (!current.isEmpty()) {
            dialedNumber.set(current.substring(0, current.length() - 1));
        }
        // Keep focus on the dialer so keyboard entry continues after a backspace.
        focusDialpad();
    }

    @FXML
    private void onCall() {
        String typed = dialedNumber.get().strip();
        if (!typed.isBlank()) {
            onDial.accept(toE164(typed));
        }
    }

    private String toE164(String typed) {
        if (typed.startsWith("+")) {
            return typed;
        }
        Country country = selectedCountry.get();
        String digits = typed.replaceAll("[^0-9]", "");
        String prefix = (country != null) ? country.dialCode() : "";
        return prefix + digits;
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
