package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Rich two-line Call History row (lead-aware), inspired by the dialer's Recents:
 * <pre>
 *   ● Jane Doe   Acme          24 min ago        4 calls   [Interested]
 *   🇺🇸 USA       2:14 PM · PST       Dialed from +1 916…   [Call]   ›
 * </pre>
 * Leading dot = last-attempt outcome color; badge = most-valuable disposition; chevron / the row
 * open the detail panel. View helper — not unit-tested.
 */
public final class CallRowCell extends ListCell<CallHistoryRow> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final double FLAG_HEIGHT = 13;

    private final ZoneId localZone;
    private final Consumer<String> onCall;
    private final Consumer<String> onOpen;
    private final boolean showDialedFrom;

    private final Region    outcomeDot   = new Region();
    private final Label     primaryLabel = new Label();
    private final Label     subLabel     = new Label();
    private final Label     agoLabel     = new Label();
    private final Label     countLabel   = new Label();
    private final Label     badge        = new Label();
    private final Label     dncChip      = new Label("DNC");
    private final ImageView flagView     = new ImageView();
    private final Label     countryLabel = new Label();
    private final Label     timeLabel    = new Label();
    private final Label     dialedFrom   = new Label();
    private final Button    callButton   = new Button("Call");
    private final FontIcon  chevron      = new FontIcon("bi-chevron-right");
    private final HBox      root;

    private String currentNumber = "";

    public CallRowCell(ZoneId localZone, boolean showDialedFrom,
                       Consumer<String> onCall, Consumer<String> onOpen) {
        this.localZone = localZone != null ? localZone : ZoneId.systemDefault();
        this.showDialedFrom = showDialedFrom;
        this.onCall = Objects.requireNonNull(onCall, "onCall must not be null");
        this.onOpen = Objects.requireNonNull(onOpen, "onOpen must not be null");

        outcomeDot.getStyleClass().add("outcome-dot");
        primaryLabel.getStyleClass().add("recent-number");
        subLabel.getStyleClass().add("caption");
        agoLabel.getStyleClass().add("caption");
        countLabel.getStyleClass().add("caption");
        countryLabel.getStyleClass().add("caption");
        timeLabel.getStyleClass().add("caption");
        dialedFrom.getStyleClass().add("caption");
        badge.getStyleClass().add("disposition-badge");
        dncChip.getStyleClass().add("dnc-chip");
        for (Label l : new Label[]{primaryLabel, subLabel, countryLabel, timeLabel, dialedFrom}) {
            l.setMinWidth(0);
        }
        flagView.setFitHeight(FLAG_HEIGHT);
        flagView.setPreserveRatio(true);
        callButton.getStyleClass().addAll("flat", "recent-action");
        callButton.setMinWidth(Region.USE_PREF_SIZE);
        callButton.setOnAction(e -> onCall.accept(currentNumber));
        chevron.getStyleClass().add("recent-chevron");
        chevron.setOnMouseClicked(e -> { onOpen.accept(currentNumber); e.consume(); });

        Region top = new Region();
        HBox.setHgrow(top, Priority.ALWAYS);
        HBox line1 = new HBox(8, outcomeDot, primaryLabel, subLabel, agoLabel, top, countLabel, badge, dncChip);
        line1.setAlignment(Pos.CENTER_LEFT);

        Region bottom = new Region();
        HBox.setHgrow(bottom, Priority.ALWAYS);
        HBox line2 = new HBox(6, flagView, countryLabel, timeLabel, bottom, dialedFrom, callButton);
        line2.setAlignment(Pos.CENTER_LEFT);

        VBox textBlock = new VBox(4, line1, line2);
        textBlock.setMinWidth(0);
        textBlock.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textBlock, Priority.ALWAYS);

        root = new HBox(8, textBlock, chevron);
        root.setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Double.MAX_VALUE);
        setPrefWidth(0);
        root.setMinWidth(0);
        root.setMaxWidth(Double.MAX_VALUE);
        setGraphic(root);
    }

    @Override
    protected void updateItem(CallHistoryRow row, boolean empty) {
        super.updateItem(row, empty);
        if (empty || row == null) {
            setGraphic(null);
            return;
        }
        setGraphic(root);
        currentNumber = row.number();

        outcomeDot.getStyleClass().removeIf(c -> c.startsWith("outcome-") && !c.equals("outcome-dot"));
        outcomeDot.getStyleClass().add(row.summary().lastOutcome().styleClass());

        final boolean hasLead = row.lead().isPresent();
        primaryLabel.setText(row.displayName());
        final Optional<String> sub = hasLead ? row.company().or(() -> Optional.of(row.number())) : Optional.empty();
        subLabel.setText(sub.orElse(""));
        subLabel.setManaged(sub.isPresent());
        subLabel.setVisible(sub.isPresent());

        agoLabel.setText(RecentCallFormatter.timeAgo(row.summary().lastCallAt(), Instant.now(), localZone));
        countLabel.setText(RecentCallFormatter.callCountLabel(row.summary().callCount()));

        final Optional<CallDisposition> badgeDisp = row.summary().badgeDisposition();
        badge.setText(badgeDisp.map(DispositionCatalog::labelOf).orElse(row.summary().lastOutcome().label()));
        dncChip.setManaged(row.summary().containsDnc());
        dncChip.setVisible(row.summary().containsDnc());

        renderCountry(row.country());

        final boolean dialed = showDialedFrom && row.dialedFromLabel().isPresent();
        dialedFrom.setText(dialed ? "Dialed from " + row.dialedFromLabel().orElseThrow() : "");
        dialedFrom.setManaged(dialed);
        dialedFrom.setVisible(dialed);
    }

    private void renderCountry(Optional<Country> country) {
        if (country.isEmpty()) {
            flagView.setImage(null);
            flagView.setManaged(false);
            countryLabel.setText("Unknown");
            timeLabel.setText("");
            return;
        }
        final Country c = country.get();
        final Optional<Image> flag = FlagImages.load(c.isoCode());
        flagView.setManaged(flag.isPresent());
        flagView.setImage(flag.orElse(null));
        countryLabel.setText(c.displayName());
        final ZonedDateTime localNow = ZonedDateTime.now(c.zone());
        final String zoneShort = c.zone().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        timeLabel.setText("%s  ·  %s".formatted(TIME.format(localNow), zoneShort));
    }
}
