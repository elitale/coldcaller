package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * ListCell for the conversation preview list in MessagesController.
 * Shows: phone number, last-message preview, direction arrow, and time.
 */
final class ConversationListCell extends ListCell<SmsMessage> {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    @Override
    protected void updateItem(SmsMessage msg, boolean empty) {
        super.updateItem(msg, empty);
        if (empty || msg == null) {
            setGraphic(null);
            return;
        }

        Label number = new Label(msg.remoteNumber().value());
        number.setStyle("-fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label time = new Label(FMT.format(msg.sentAt()));
        time.getStyleClass().add("caption");

        HBox topRow = new HBox(8, number, spacer, time);

        String arrow   = msg.direction() == CallDirection.OUTBOUND ? "→ " : "← ";
        String preview = msg.body().length() > 42 ? msg.body().substring(0, 39) + "…" : msg.body();
        Label previewLabel = new Label(arrow + preview);
        previewLabel.getStyleClass().add("caption");

        VBox box = new VBox(2, topRow, previewLabel);
        box.setPadding(new Insets(8, 12, 8, 12));

        setGraphic(box);
        setText(null);
    }
}
