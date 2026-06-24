package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Keyboard-first inline add-row pinned above the grid: phone (required, live-validated),
 * first, last, company. Enter in any field commits and re-focuses phone for rapid
 * sequential entry — no modal.
 *
 * <p>View helper (not unit-tested). Phone parsing is injected as a {@link Function} so the
 * same seam can use the strict E.164 parser today and {@code PhoneNormalizer} later.
 */
final class LeadQuickAddBar {

    private final HBox row = new HBox(8);
    private final Label phoneError = new Label();
    private final VBox node = new VBox(2, row, phoneError);
    private final LeadService leadService;
    private final Function<String, Optional<PhoneNumber>> phoneParser;

    private final TextField phone = new TextField();
    private final TextField first = new TextField();
    private final TextField last = new TextField();
    private final TextField company = new TextField();

    private Runnable onAdded = () -> { };

    LeadQuickAddBar(LeadService leadService, Function<String, Optional<PhoneNumber>> phoneParser) {
        this.leadService = Objects.requireNonNull(leadService, "leadService must not be null");
        this.phoneParser = Objects.requireNonNull(phoneParser, "phoneParser must not be null");
        build();
    }

    Region node() {
        return node;
    }

    void setOnAdded(Runnable callback) {
        this.onAdded = Objects.requireNonNull(callback, "callback must not be null");
    }

    void focusPhone() {
        phone.requestFocus();
    }

    private void build() {
        node.setPadding(new Insets(8, 32, 8, 32));
        node.getStyleClass().add("quick-add-bar");
        row.setAlignment(Pos.CENTER_LEFT);

        phone.setPromptText("Phone (required)");
        first.setPromptText("First");
        last.setPromptText("Last");
        company.setPromptText("Company");
        HBox.setHgrow(company, Priority.ALWAYS);
        company.setMaxWidth(Double.MAX_VALUE);

        phoneError.getStyleClass().add("caption");
        phoneError.setStyle("-fx-text-fill: #FF3B30;");
        phoneError.setManaged(false);
        phoneError.setVisible(false);

        for (TextField field : List.of(phone, first, last, company)) {
            field.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER) {
                    commit();
                }
            });
        }
        // Live in-cell phone validation: red border + reason while non-blank and unparseable.
        phone.textProperty().addListener((obs, o, n) -> markPhoneValidity());

        Button add = new Button("Add");
        add.getStyleClass().add("accent");
        add.setOnAction(e -> commit());

        row.getChildren().addAll(phone, first, last, company, add);
    }

    private void markPhoneValidity() {
        String raw = phone.getText();
        boolean invalid = raw != null && !raw.isBlank() && phoneParser.apply(raw).isEmpty();
        phone.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("error"), invalid);
        showError(invalid ? reasonFor(raw) : "");
    }

    private void showError(String message) {
        boolean show = !message.isBlank();
        phoneError.setText(message);
        phoneError.setManaged(show);
        phoneError.setVisible(show);
    }

    /** A specific, actionable reason for an unparseable phone — the most common miss is the +. */
    private static String reasonFor(String raw) {
        String cleaned = raw.strip().replaceAll("[\\s\\-()./]", "");
        String digits = cleaned.replaceAll("[^0-9]", "");
        if (!cleaned.startsWith("+") && !digits.isBlank()) {
            return "Add the country code with a +, e.g. +" + digits;
        }
        return "Enter a valid number in international format, e.g. +14155550123";
    }

    private void commit() {
        Optional<PhoneNumber> parsed = phoneParser.apply(phone.getText());
        if (parsed.isEmpty()) {
            phone.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("error"), true);
            String raw = phone.getText();
            showError(raw == null || raw.isBlank() ? "Phone is required" : reasonFor(raw));
            phone.requestFocus();
            return;
        }
        LeadService.NewLead draft = new LeadService.NewLead(
                blankToEmpty(first.getText()),
                blankToEmpty(last.getText()),
                parsed.get(),
                blankToEmpty(company.getText()),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.empty());
        CompletableFuture
                .runAsync(() -> leadService.save(draft))
                .thenRunAsync(() -> {
                    clearFields();
                    onAdded.run();
                    phone.requestFocus();
                }, Platform::runLater);
    }

    private void clearFields() {
        phone.clear();
        first.clear();
        last.clear();
        company.clear();
        phone.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("error"), false);
        showError("");
    }

    private static Optional<String> blankToEmpty(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }
}
