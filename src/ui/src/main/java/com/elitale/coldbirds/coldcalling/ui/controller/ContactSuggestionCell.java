package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.ui.support.Avatars;
import com.elitale.coldbirds.coldcalling.ui.support.ContactSuggestion;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * A row in the New-message contact picker: monogram avatar + name + company·number, and a red
 * "Opted out" badge for DNC leads (so the rep never picks the wrong, or an un-textable, contact). A
 * raw-number row reads "Text {number} · No matching lead". Pure view.
 */
final class ContactSuggestionCell extends ListCell<ContactSuggestion> {

    private static final String[] PALETTE = {
            "#0071E3", "#34C759", "#FF9F0A", "#FF3B30",
            "#AF52DE", "#5AC8FA", "#FF2D55", "#A2845E"
    };

    @Override
    protected void updateItem(ContactSuggestion s, boolean empty) {
        super.updateItem(s, empty);
        if (empty || s == null) {
            setGraphic(null);
            setText(null);
            return;
        }

        final boolean raw = s.isRawNumber();
        final String title = raw ? "Text " + s.number().value() : s.displayName();
        final String sub   = raw ? "No matching lead" : s.subtitle();

        Label name = new Label(title);
        name.getStyleClass().add("conv-name");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label subtitle = new Label(sub);
        subtitle.getStyleClass().add("conv-preview");

        VBox center = new VBox(2, name, subtitle);
        center.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(center, Priority.ALWAYS);

        HBox box = new HBox(10, avatar(title), center);
        box.setAlignment(Pos.CENTER_LEFT);

        if (s.dnc()) {
            Label badge = new Label("Opted out");
            badge.setStyle("-fx-text-fill: #FF3B30; -fx-font-size: 11px; -fx-font-weight: 600;");
            box.getChildren().add(badge);
        }

        setGraphic(box);
        setText(null);
    }

    private static StackPane avatar(String key) {
        Circle disc = new Circle(16, Color.web(PALETTE[Avatars.colorIndex(key)]));
        Label mono = new Label(Avatars.initials(key));
        mono.setStyle("-fx-text-fill: white; -fx-font-weight: 600; -fx-font-size: 12px;");
        return new StackPane(disc, mono);
    }
}
