package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Contact;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.ContactService;
import com.elitale.coldbirds.coldcalling.services.ContactService.NewContact;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Controller for the Contacts screen (contacts-view.fxml).
 * <p>
 * Provides full-text search, async loading, an inline Add-Contact dialog,
 * soft delete, and one-click dial.
 * <p>
 * Threading: all methods must be called on the FX Application Thread except
 * where CompletableFuture dispatches to a background thread explicitly.
 */
public final class ContactsController {

    // ── FXML-injected fields ──────────────────────────────────────────────────

    @FXML private TextField                      searchField;
    @FXML private TableView<Contact>             table;
    @FXML private TableColumn<Contact, String>   nameCol;
    @FXML private TableColumn<Contact, String>   phoneCol;
    @FXML private TableColumn<Contact, String>   companyCol;
    @FXML private TableColumn<Contact, String>   dncCol;
    @FXML private Button                         callBtn;
    @FXML private Button                         deleteBtn;

    // ── State ─────────────────────────────────────────────────────────────────

    private ContactService   contactService;
    private Consumer<String> onDial = ignored -> {};

    private final ObservableList<Contact> contacts = FXCollections.observableArrayList();

    /** Default no-arg constructor — required by FXMLLoader. */
    public ContactsController() {}

    // ── Configuration (called before FXMLLoader.load()) ───────────────────────

    /**
     * Inject the service. Must be called before {@code FXMLLoader.load()}.
     */
    public void setContactService(ContactService service) {
        this.contactService = Objects.requireNonNull(service, "service must not be null");
    }

    /**
     * Register a callback for when the user clicks Call on a contact.
     * The callback receives the contact's E.164 phone number string.
     */
    public void setOnDial(Consumer<String> callback) {
        this.onDial = Objects.requireNonNull(callback, "callback must not be null");
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        // Column value factories
        nameCol.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().displayName()));
        phoneCol.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().phone().value()));
        companyCol.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().company().orElse("")));
        dncCol.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().dnc() ? "DNC" : ""));

        // DNC column — red bold text when set
        dncCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setStyle("-fx-text-fill: #FF3B30; -fx-font-weight: bold;");
                }
            }
        });

        // Placeholder shown when the table has no rows
        Label placeholder = new Label("No contacts yet. Click \"Add Contact\" to get started.");
        placeholder.getStyleClass().add("caption");
        table.setPlaceholder(placeholder);

        table.setItems(contacts);

        // Button state bound to selection
        callBtn.disableProperty().bind(
                table.getSelectionModel().selectedItemProperty().isNull());
        deleteBtn.disableProperty().bind(
                table.getSelectionModel().selectedItemProperty().isNull());

        // Double-click a row to dial
        table.setRowFactory(tv -> {
            TableRow<Contact> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    onDial.accept(row.getItem().phone().value());
                }
            });
            return row;
        });

        // Reactive search — fires on every keystroke
        searchField.textProperty().addListener(
                (obs, oldVal, newVal) -> loadContacts(newVal.strip()));

        // Initial load
        loadContacts("");
    }

    // ── FXML event handlers ───────────────────────────────────────────────────

    @FXML
    private void onCall() {
        Contact selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) {
            onDial.accept(selected.phone().value());
        }
    }

    @FXML
    private void onAdd() {
        showAddContactDialog();
    }

    @FXML
    private void onDelete() {
        Contact selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Contact");
        confirm.setHeaderText("Delete " + selected.displayName() + "?");
        confirm.setContentText("This contact will be soft-deleted and hidden from all lists.");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                CompletableFuture
                        .runAsync(() -> contactService.delete(selected.id()))
                        .thenRunAsync(() -> loadContacts(searchField.getText().strip()),
                                Platform::runLater);
            }
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void loadContacts(String query) {
        if (contactService == null) return;

        CompletableFuture
                .supplyAsync(() -> query.isBlank()
                        ? contactService.findAll()
                        : contactService.search(query))
                .thenAcceptAsync(list -> {
                    contacts.setAll(list);
                    if (list.isEmpty()) {
                        String msg = query.isBlank()
                                ? "No contacts yet. Click \"Add Contact\" to get started."
                                : "No results for \"" + query + "\"";
                        ((Label) table.getPlaceholder()).setText(msg);
                    }
                }, Platform::runLater);
    }

    private void showAddContactDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add Contact");
        dialog.setHeaderText("New Contact");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(16, 16, 8, 16));

        TextField firstNameField = new TextField();
        firstNameField.setPromptText("First name");
        TextField lastNameField = new TextField();
        lastNameField.setPromptText("Last name");
        TextField phoneField = new TextField();
        phoneField.setPromptText("+15550001234 (E.164 required)");
        TextField companyField = new TextField();
        companyField.setPromptText("Company (optional)");

        grid.add(new Label("First name:"), 0, 0); grid.add(firstNameField, 1, 0);
        grid.add(new Label("Last name:"),  0, 1); grid.add(lastNameField,  1, 1);
        grid.add(new Label("Phone:"),      0, 2); grid.add(phoneField,     1, 2);
        grid.add(new Label("Company:"),    0, 3); grid.add(companyField,   1, 3);

        GridPane.setHgrow(firstNameField, Priority.ALWAYS);
        GridPane.setHgrow(lastNameField,  Priority.ALWAYS);
        GridPane.setHgrow(phoneField,     Priority.ALWAYS);
        GridPane.setHgrow(companyField,   Priority.ALWAYS);

        dialog.getDialogPane().setContent(grid);

        // Disable OK until a phone number is entered
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        phoneField.textProperty().addListener((obs, o, n) ->
                okButton.setDisable(n.strip().isBlank()));

        dialog.showAndWait().ifPresent(result -> {
            if (result != ButtonType.OK) return;

            // Normalise: strip spaces and dashes before validation
            String rawPhone = phoneField.getText().strip()
                    .replace(" ", "")
                    .replace("-", "")
                    .replace("(", "")
                    .replace(")", "");
            try {
                PhoneNumber phone = new PhoneNumber(rawPhone);
                NewContact newContact = new NewContact(
                        blankToEmpty(firstNameField.getText()),
                        blankToEmpty(lastNameField.getText()),
                        phone,
                        blankToEmpty(companyField.getText()),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        Optional.empty()
                );
                CompletableFuture
                        .runAsync(() -> contactService.save(newContact))
                        .thenRunAsync(() -> loadContacts(searchField.getText().strip()),
                                Platform::runLater);
            } catch (IllegalArgumentException e) {
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("Invalid Phone Number");
                err.setHeaderText("\"" + rawPhone + "\" is not a valid E.164 number");
                err.setContentText("Enter the number with country code, e.g. +15550001234");
                err.showAndWait();
            }
        });
    }

    /** Convert a trimmed string to {@code Optional.empty()} if blank. */
    private static Optional<String> blankToEmpty(String s) {
        String trimmed = (s != null) ? s.strip() : "";
        return trimmed.isBlank() ? Optional.empty() : Optional.of(trimmed);
    }
}
