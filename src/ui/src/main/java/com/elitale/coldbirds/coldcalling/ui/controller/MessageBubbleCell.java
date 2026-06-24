package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;
import com.elitale.coldbirds.coldcalling.ui.support.Motion;
import com.elitale.coldbirds.coldcalling.ui.support.SmsStatusLabel;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Thread bubble (iMessage convention): outbound = right-aligned blue, inbound = left-aligned grey,
 * 18px radius. Times are not stamped on every bubble — a centered separator appears at the start of
 * a conversation and after a gap, and each bubble carries its full time on a tooltip. The status
 * caption (Sent / Sending… / Failed · Retry) shows only under the last message, or under any Failed
 * outbound so a retry is always reachable.
 */
final class MessageBubbleCell extends ListCell<SmsMessage> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a");

    /** Gap after which a fresh centered timestamp is shown. */
    private static final Duration GAP = Duration.ofMinutes(30);

    private final Consumer<SmsMessage> onRetry;
    private final Set<Long> animateIds;

    MessageBubbleCell(Consumer<SmsMessage> onRetry, Set<Long> animateIds) {
        this.onRetry = onRetry;
        this.animateIds = animateIds;
    }

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
        body.setWrappingWidth(320);

        VBox bubble = new VBox(body);
        bubble.setPadding(new Insets(8, 13, 8, 13));
        bubble.setMaxWidth(380);
        if (out) {
            bubble.setStyle("-fx-background-color: #0071E3; -fx-background-radius: 18 18 4 18;");
            body.setStyle("-fx-fill: white;");
        } else {
            bubble.setStyle("-fx-background-color: #F0F0F2; -fx-background-radius: 18 18 18 4;");
            body.setStyle("-fx-fill: #1D1D1F;");
        }
        Tooltip.install(bubble, new Tooltip(FULL.format(msg.sentAt().atZone(ZoneId.systemDefault()))));

        HBox bubbleRow = new HBox(bubble);
        bubbleRow.setPadding(new Insets(2, 16, 1, 16));
        bubbleRow.setAlignment(out ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        VBox wrap = new VBox(bubbleRow);
        separatorText(msg).ifPresent(text -> wrap.getChildren().add(0, timeSeparator(text)));
        if (out && (isLastRow() || msg.status() instanceof SmsStatus.Failed)) {
            wrap.getChildren().add(statusRow(msg));
        }

        setGraphic(wrap);
        setText(null);
        setStyle("-fx-background-color: transparent;");

        maybeAnimateIn(msg, bubble);
    }

    /** Pop a freshly sent/received bubble in (fade + slide up), once. Respects Reduce Motion. */
    private void maybeAnimateIn(SmsMessage msg, Node bubble) {
        if (animateIds == null || Motion.isReduced()) return;
        if (!animateIds.remove(msg.id().value())) return;
        bubble.setOpacity(0);
        bubble.setTranslateY(10);
        FadeTransition fade = new FadeTransition(javafx.util.Duration.millis(220), bubble);
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(javafx.util.Duration.millis(240), bubble);
        slide.setFromY(10);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(fade, slide).playFromStart();
    }

    private boolean isLastRow() {
        return getListView() != null && getIndex() == getListView().getItems().size() - 1;
    }

    /** A centered timestamp when this is the first message or follows a {@link #GAP}. */
    private java.util.Optional<String> separatorText(SmsMessage msg) {
        int i = getIndex();
        if (getListView() == null) return java.util.Optional.empty();
        if (i <= 0) return java.util.Optional.of(format(msg.sentAt()));
        Instant prev = getListView().getItems().get(i - 1).sentAt();
        return Duration.between(prev, msg.sentAt()).compareTo(GAP) >= 0
                ? java.util.Optional.of(format(msg.sentAt()))
                : java.util.Optional.empty();
    }

    private static HBox timeSeparator(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("msg-time-sep");
        HBox row = new HBox(l);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(10, 0, 6, 0));
        return row;
    }

    /** The "Sent / Sending… / Failed · Retry" caption under an outbound bubble. */
    private HBox statusRow(SmsMessage msg) {
        final SmsStatus status = msg.status();
        final boolean failed = status instanceof SmsStatus.Failed;

        Label label = new Label(SmsStatusLabel.label(status));
        label.getStyleClass().addAll("msg-status", SmsStatusLabel.styleClass(status));
        if (failed) label.getStyleClass().add("failed");

        HBox statusRow = new HBox(6, label);
        statusRow.setAlignment(Pos.CENTER_RIGHT);
        statusRow.setPadding(new Insets(1, 18, 4, 16));

        if (failed && onRetry != null) {
            Hyperlink retry = new Hyperlink("Retry");
            retry.setStyle("-fx-font-size: 11px; -fx-padding: 0;");
            retry.setOnAction(e -> onRetry.accept(msg));
            statusRow.getChildren().add(retry);
        }
        return statusRow;
    }

    /** Today → "Today 3:41 PM", yesterday → "Yesterday 3:41 PM", older → "Jun 18, 2026 at 3:41 PM". */
    private static String format(Instant at) {
        ZonedDateTime z = at.atZone(ZoneId.systemDefault());
        LocalDate day = z.toLocalDate();
        if (day.equals(LocalDate.now()))             return "Today " + TIME.format(z);
        if (day.equals(LocalDate.now().minusDays(1))) return "Yesterday " + TIME.format(z);
        return FULL.format(z);
    }
}
