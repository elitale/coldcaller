package com.elitale.coldbirds.coldcalling.ui;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.ui.support.LeadFieldDigest;

import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The "Lead" section of the number-detail panel, rendered with progressive disclosure.
 *
 * <p>Shows the 3-second glance always (name, company·title, DNC, status, tags) and
 * hides every other field — email, all custom columns, notes, added date — behind a
 * single <em>"Show all fields ▸"</em> expander. This satisfies "all the data with all
 * the columns" without dumping a spreadsheet on screen mid-call.
 *
 * <p>Field tiering is decided by the headless {@link LeadFieldDigest}; this class only
 * renders it. Build on the FX thread.
 */
public final class LeadGlanceCard {

    private final VBox root = new VBox(6);

    public LeadGlanceCard(final Optional<Lead> lead) {
        Objects.requireNonNull(lead, "lead must not be null");
        root.getStyleClass().add("detail-glance");
        root.getChildren().add(sectionTitle("Lead"));

        if (lead.isEmpty()) {
            root.getChildren().add(muted("No lead saved for this number."));
            return;
        }
        render(LeadFieldDigest.of(lead.get()));
    }

    /** The node to add into the panel body. */
    public Region getRoot() {
        return root;
    }

    private void render(final LeadFieldDigest digest) {
        // Tier 1 — the glance.
        final Label name = new Label(digest.name());
        name.getStyleClass().add("detail-lead-name");
        name.setWrapText(true);
        root.getChildren().add(name);
        digest.companyTitle().ifPresent(ct -> {
            final Label org = muted(ct);
            org.setWrapText(true);
            root.getChildren().add(org);
        });

        // Tier 2 — context badges + tags.
        final FlowPane badges = new FlowPane(6, 6);
        if (digest.dnc()) {
            badges.getChildren().add(badge("DNC", "detail-badge-dnc"));
        }
        badges.getChildren().add(badge(digest.statusLabel(), "detail-badge-status"));
        root.getChildren().add(badges);
        if (!digest.tags().isEmpty()) {
            final Label tags = muted("Tags: " + String.join(", ", digest.tags()));
            tags.setWrapText(true);
            root.getChildren().add(tags);
        }

        // Tier 3 — every other field, collapsed behind one expander.
        if (!digest.detailFields().isEmpty()) {
            root.getChildren().add(expander(digest.detailFields()));
        }
    }

    private VBox expander(final List<LeadFieldDigest.Field> fields) {
        final VBox rows = new VBox(6);
        rows.getStyleClass().add("detail-fields");
        rows.setVisible(false);
        rows.setManaged(false);
        for (final LeadFieldDigest.Field field : fields) {
            rows.getChildren().add(fieldRow(field));
        }

        final Hyperlink toggle = new Hyperlink("Show all fields ▸");
        toggle.getStyleClass().add("detail-expander");
        toggle.setOnAction(e -> {
            final boolean show = !rows.isVisible();
            rows.setVisible(show);
            rows.setManaged(show);
            toggle.setText(show ? "Hide fields ▾" : "Show all fields ▸");
        });
        return new VBox(4, toggle, rows);
    }

    private static HBox fieldRow(final LeadFieldDigest.Field field) {
        final Label label = new Label(field.label());
        label.getStyleClass().add("detail-field-label");
        label.setMinWidth(96);
        label.setPrefWidth(96);
        final Label value = new Label(field.value());
        value.getStyleClass().add("detail-field-value");
        value.setWrapText(true);
        HBox.setHgrow(value, Priority.ALWAYS);
        final HBox row = new HBox(8, label, value);
        return row;
    }

    private static Label badge(final String text, final String styleClass) {
        final Label label = new Label(text);
        label.getStyleClass().addAll("detail-badge", styleClass);
        return label;
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
}
