package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * ListCell for the SMS thread view in MessagesController.
 * Outbound messages appear as right-aligned blue bubbles.
 * Inbound messages appear as left-aligned grey bubbles.
 */
final class MessageBubbleCell extends ListCell<SmsMessage> {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    @Override
    protected void updateItem(SmsMessage msg, boolean empty) {
        super.updateItem(msg, empty);
        if (empty || msg == null) {
            setGraphic(null);
            setStyle(null);
            return;
        }

        final boolean out = msg.direction() == CallDirection.OUTBOUND;

        Text body = new Text(msg.body());
        body.setWrappingWidth(300);

        Label time = new Label(FMT.format(msg.sentAt()));
        time.getStyleClass().add("caption");

        VBox bubble = new VBox(4, body, time);
        bubble.setPadding(new Insets(8, 12, 8, 12));
        bubble.setMaxWidth(360);

        if (out) {
            bubble.setStyle("""
                    -fx-background-color: #0071E3;
                    -fx-background-radius: 12 12 4 12;
                    """);
            body.setStyle("-fx-fill: white;");
            time.setStyle("-fx-text-fill: rgba(255,255,255,0.7); -fx-font-size: 11px;");
        } else {
            bubble.setStyle("""
                    -fx-background-color: #F5F5F7;
                    -fx-background-radius: 12 12 12 4;
                    """);
            body.setStyle("-fx-fill: #1D1D1F;");
            time.setStyle("-fx-text-fill: #98989D; -fx-font-size: 11px;");
        }

        HBox row = new HBox(bubble);
        row.setPadding(new Insets(4, 16, 4, 16));
        row.setAlignment(out ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        setGraphic(row);
        setText(null);
        setStyle("-fx-background-color: transparent;");
    }
}
