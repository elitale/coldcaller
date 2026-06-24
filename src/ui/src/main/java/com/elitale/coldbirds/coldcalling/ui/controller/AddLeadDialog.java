package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.LeadService.NewLead;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.util.List;
import java.util.Optional;

/**
 * Modal Add-Lead dialog. Collects first/last name, phone (required) and company,
 * validates the phone as E.164, and returns a {@link NewLead} ready to persist.
 *
 * <p>Pure view helper — it performs no persistence. On an invalid phone it shows an
 * error alert and returns {@link Optional#empty()}.
 */
final class AddLeadDialog {

    private AddLeadDialog() {
    }

    static Optional<NewLead> show() {
        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add Lead");
        dialog.setHeaderText("New Lead");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        final GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(16, 16, 8, 16));

        final TextField firstNameField = new TextField();
        firstNameField.setPromptText("First name");
        final TextField lastNameField = new TextField();
        lastNameField.setPromptText("Last name");
        final TextField phoneField = new TextField();
        phoneField.setPromptText("+15550001234 (E.164 required)");
        final TextField companyField = new TextField();
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

        final Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        phoneField.textProperty().addListener((obs, o, n) ->
                okButton.setDisable(n.strip().isBlank()));

        final Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }

        final String rawPhone = phoneField.getText().strip()
                .replace(" ", "")
                .replace("-", "")
                .replace("(", "")
                .replace(")", "");
        try {
            final PhoneNumber phone = new PhoneNumber(rawPhone);
            return Optional.of(new NewLead(
                    blankToEmpty(firstNameField.getText()),
                    blankToEmpty(lastNameField.getText()),
                    phone,
                    blankToEmpty(companyField.getText()),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    Optional.empty()));
        } catch (IllegalArgumentException e) {
            final Alert err = new Alert(Alert.AlertType.ERROR);
            err.setTitle("Invalid Phone Number");
            err.setHeaderText("\"" + rawPhone + "\" is not a valid E.164 number");
            err.setContentText("Enter the number with country code, e.g. +15550001234");
            err.showAndWait();
            return Optional.empty();
        }
    }

    private static Optional<String> blankToEmpty(String value) {
        final String trimmed = (value != null) ? value.strip() : "";
        return trimmed.isBlank() ? Optional.empty() : Optional.of(trimmed);
    }
}
