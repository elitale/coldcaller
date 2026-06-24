package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;
import com.elitale.coldbirds.coldcalling.ui.support.Avatars;
import com.elitale.coldbirds.coldcalling.ui.support.ConversationReplyState;
import com.elitale.coldbirds.coldcalling.ui.support.FlagImages;
import com.elitale.coldbirds.coldcalling.ui.support.SmsConversationRow;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * iMessage-style conversation row: monogram avatar, lead name + flag, a relative timestamp, the
 * last-message preview ("You:" for outbound), and a triage badge (needs-reply dot / Failed /
 * Opted out). Company lives in the thread header, not here — the list stays scannable. Pure view.
 */
final class ConversationListCell extends ListCell<SmsConversationRow> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d");

    /** Soft avatar palette (Apple-system-ish), indexed by {@link Avatars#colorIndex}. */
    private static final String[] PALETTE = {
            "#0071E3", "#34C759", "#FF9F0A", "#FF3B30",
            "#AF52DE", "#5AC8FA", "#FF2D55", "#A2845E"
    };

    @Override
    protected void updateItem(SmsConversationRow row, boolean empty) {
        super.updateItem(row, empty);
        if (empty || row == null) {
            setGraphic(null);
            setText(null);
            return;
        }

        final boolean unread = row.replyState() == ConversationReplyState.UNREAD;

        Label name = new Label(row.displayName());
        name.getStyleClass().add("conv-name");
        if (unread) name.getStyleClass().add("unread");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        HBox topRow = new HBox(6, name);
        topRow.setAlignment(Pos.CENTER_LEFT);
        row.country()
                .flatMap(c -> FlagImages.load(c.isoCode()))
                .ifPresent(img -> {
                    ImageView flag = new ImageView(img);
                    flag.setFitHeight(11);
                    flag.setPreserveRatio(true);
                    topRow.getChildren().add(flag);
                });
        Label time = new Label(relativeTime(row.sentAt()));
        time.getStyleClass().add("conv-time");
        topRow.getChildren().add(time);

        String prefix = row.direction() == CallDirection.OUTBOUND ? "You: " : "";
        Label preview = new Label(prefix + row.preview());
        preview.getStyleClass().add("conv-preview");
        preview.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(preview, Priority.ALWAYS);

        HBox bottomRow = new HBox(6, preview);
        bottomRow.setAlignment(Pos.CENTER_LEFT);
        Node badge = stateBadge(row);
        if (badge != null) bottomRow.getChildren().add(badge);

        VBox center = new VBox(2, topRow, bottomRow);
        center.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(center, Priority.ALWAYS);

        HBox box = new HBox(10, avatar(row), center);
        box.setAlignment(Pos.CENTER_LEFT);

        setGraphic(box);
        setText(null);
    }

    /** Today → "3:41 PM", yesterday → "Yesterday", older → "Jun 18". */
    private static String relativeTime(Instant at) {
        ZonedDateTime z = at.atZone(ZoneId.systemDefault());
        LocalDate day = z.toLocalDate();
        if (day.equals(LocalDate.now()))            return TIME.format(z);
        if (day.equals(LocalDate.now().minusDays(1))) return "Yesterday";
        return DATE.format(z);
    }

    private static StackPane avatar(SmsConversationRow row) {
        String key = row.displayName();
        Circle disc = new Circle(18, Color.web(PALETTE[Avatars.colorIndex(key)]));
        Label mono = new Label(Avatars.initials(key));
        mono.setStyle("-fx-text-fill: white; -fx-font-weight: 600; -fx-font-size: 13px;");
        return new StackPane(disc, mono);
    }

    /** Triage badge: opted-out / failed / needs-reply dot, else a spacer (keeps rows aligned). */
    private static Node stateBadge(SmsConversationRow row) {
        if (row.optedOut()) return pill("Opted out", "#98989D");
        if (row.status() instanceof SmsStatus.Failed) return pill("Failed", "#FF3B30");
        if (row.replyState().needsReply()) return new Circle(4, Color.web("#0071E3"));
        return null;
    }

    private static Label pill(String text, String hex) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + hex + "; -fx-font-size: 11px; -fx-font-weight: 600;");
        return l;
    }
}
