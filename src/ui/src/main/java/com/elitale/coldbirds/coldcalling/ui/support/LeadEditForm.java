package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.services.LeadService.NewLead;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Inline add/edit form for a {@link Lead}, shown in place inside the
 * number-detail panel (no modal — keeps the calling loop unblocked).
 *
 * <p>Builds a {@link NewLead} (add) or an updated {@link Lead} (edit)
 * and persists it through {@link LeadService}, then notifies the host.
 */
public final class LeadEditForm {

    private final VBox root = new VBox(8);
    private final TextField first   = field("First name");
    private final TextField last    = field("Last name");
    private final TextField company = field("Company");
    private final TextField title   = field("Title");
    private final TextField email   = field("Email");

    private final LeadService leadService;
    private final PhoneNumber phone;
    private final Optional<Lead> existing;

    public LeadEditForm(
            final LeadService leadService,
            final PhoneNumber phone,
            final Optional<Lead> existing,
            final Runnable onSaved,
            final Runnable onCancel) {

        this.leadService = Objects.requireNonNull(leadService, "leadService");
        this.phone = Objects.requireNonNull(phone, "phone");
        this.existing = Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(onSaved, "onSaved");
        Objects.requireNonNull(onCancel, "onCancel");

        existing.ifPresent(c -> {
            first.setText(c.firstName().orElse(""));
            last.setText(c.lastName().orElse(""));
            company.setText(c.company().orElse(""));
            title.setText(c.title().orElse(""));
            email.setText(c.email().orElse(""));
        });

        final Label heading = new Label(existing.isPresent() ? "Edit lead" : "Add to leads");
        heading.getStyleClass().add("detail-section-title");

        final Button save = new Button(existing.isPresent() ? "Save" : "Add");
        save.getStyleClass().addAll("accent", "detail-action");
        save.setDefaultButton(true);
        save.setOnAction(e -> { if (persist()) onSaved.run(); });

        final Button cancel = new Button("Cancel");
        cancel.getStyleClass().addAll("flat", "detail-action");
        cancel.setOnAction(e -> onCancel.run());

        final HBox buttons = new HBox(8, save, cancel);
        root.setPadding(new Insets(4, 0, 4, 0));
        root.getChildren().addAll(heading, first, last, company, title, email, buttons);
    }

    public Region getRoot() {
        return root;
    }

    private boolean persist() {
        if (existing.isPresent()) {
            final Lead c = existing.get();
            final Lead updated = new Lead(
                    c.id(), opt(first), opt(last), c.phone(), opt(company), opt(title),
                    opt(email), c.tags(), c.notes(), c.dnc(), c.customFields(), c.leadStatus(),
                    c.createdAt(), Instant.now());
            return leadService.update(updated).isOk();
        }
        return leadService.save(new NewLead(
                opt(first), opt(last), phone, opt(company), opt(title), opt(email),
                List.of(), Optional.empty())).isOk();
    }

    private static Optional<String> opt(final TextField f) {
        final String v = f.getText() == null ? "" : f.getText().strip();
        return v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    private static TextField field(final String prompt) {
        final TextField f = new TextField();
        f.setPromptText(prompt);
        return f;
    }
}
