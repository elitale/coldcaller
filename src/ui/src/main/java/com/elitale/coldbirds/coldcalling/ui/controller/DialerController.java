package com.elitale.coldbirds.coldcalling.ui.controller;

import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Controller for the manual dial-pad view (dialer-view.fxml).
 * <p>
 * Digit buttons on the FXML declare {@code userData="N"} and all fire
 * {@link #onDigitPressed} — one handler for all 12 keys.
 * <p>
 * Threading: all methods must be called on the FX Application Thread.
 */
public final class DialerController {

    @FXML private Label           numberDisplay;
    @FXML private Button          callButton;
    @FXML private Button          backspaceButton;
    @FXML private Button          popOutButton;
    @FXML private ListView<String> recentCallsList;

    private final StringProperty dialedNumber = new SimpleStringProperty("");

    /** Callback invoked with the raw digit string when the user presses Call. */
    private Consumer<String> onDial = ignored -> {};
    private Runnable onPopOut = () -> {};

    /** Default no-arg constructor — required by FXMLLoader. */
    public DialerController() {}

    /** Register a callback for when the user initiates a call. */
    public void setOnDial(Consumer<String> callback) {
        this.onDial = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Register a callback for toggling pop out / dock of the dialer view. */
    public void setOnPopOut(Runnable callback) {
        this.onPopOut = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Update pop out button text to reflect current dialer attachment mode. */
    public void setDetached(boolean detached) {
        if (popOutButton != null) {
            popOutButton.setText(detached ? "Dock" : "Pop Out");
        }
    }

    /**
     * Populate the recent calls list. Must be called after
     * {@code FXMLLoader.load()} so that {@link #recentCallsList} is non-null.
     */
    public void setRecentCalls(ObservableList<String> calls) {
        recentCallsList.setItems(
                Objects.requireNonNull(calls, "calls must not be null")
        );
    }

    /**
     * Pre-fill the number display — e.g. when clicking a number in Contacts.
     * Must be called on the FX Application Thread.
     */
    public void prefillNumber(String number) {
        dialedNumber.set(Objects.requireNonNull(number, "number must not be null"));
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        numberDisplay.textProperty().bind(dialedNumber);
        callButton.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> dialedNumber.get().isBlank(), dialedNumber));
        backspaceButton.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> dialedNumber.get().isEmpty(), dialedNumber));
    }

    // ── FXML event handlers ───────────────────────────────────────────────────

    /** Handles every digit/star/hash button via {@code userData}. */
    @FXML
    private void onDigitPressed(ActionEvent event) {
        Button btn = (Button) event.getSource();
        Object ud  = btn.getUserData();
        String digit = (ud != null) ? ud.toString() : btn.getText();
        dialedNumber.set(dialedNumber.get() + digit);
    }

    @FXML
    private void onBackspace() {
        String current = dialedNumber.get();
        if (!current.isEmpty()) {
            dialedNumber.set(current.substring(0, current.length() - 1));
        }
    }

    @FXML
    private void onCall() {
        String number = dialedNumber.get().strip();
        if (!number.isBlank()) {
            onDial.accept(number);
        }
    }

    @FXML
    private void onPopOut() {
        onPopOut.run();
    }
}
