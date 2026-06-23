package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
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
 * Two-line list cell for the dialer's "Recent Calls" list:
 * <pre>
 *   Jane Doe   +91 75973 65803     3 min ago             12 calls
 *   🇮🇳 India                      7:42 PM · IST    [Call] [Message]
 * </pre>
 *
 * <p>Line 1: lead name (or number) · the saved number when a lead matches ·
 * humanized last-call time. Line 2: country flag + name, current local time and
 * timezone, plus Call and Message action buttons. The time / "ago" labels
 * recompute on every render, so a periodic {@code ListView.refresh()} keeps them
 * ticking.
 */
public final class RecentCallCell extends ListCell<RecentCallRow> {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private static final double FLAG_HEIGHT = 13;

    private final ZoneId localZone;
    private final Consumer<String> onCall;
    private final Consumer<String> onMessage;

    private final Label     primaryLabel = new Label();
    private final Label     numberLabel  = new Label();
    private final Label     agoLabel     = new Label();
    private final Label     countLabel   = new Label();
    private final ImageView flagView     = new ImageView();
    private final Label     countryLabel = new Label();
    private final Label     timeLabel    = new Label();
    private final Button    callButton   = new Button("Call");
    private final Button    messageButton = new Button("Message");
    private final VBox      root;

    private String currentNumber = "";

    public RecentCallCell(final ZoneId localZone,
                          final Consumer<String> onCall,
                          final Consumer<String> onMessage) {
        this.localZone = (localZone != null) ? localZone : ZoneId.systemDefault();
        this.onCall = Objects.requireNonNull(onCall, "onCall must not be null");
        this.onMessage = Objects.requireNonNull(onMessage, "onMessage must not be null");

        primaryLabel.getStyleClass().add("recent-number");
        numberLabel.getStyleClass().add("caption");
        agoLabel.getStyleClass().add("caption");
        countLabel.getStyleClass().add("caption");
        countryLabel.getStyleClass().add("caption");
        timeLabel.getStyleClass().add("caption");

        // Allow the long text labels to shrink and ellipsize when the panel
        // narrows the dialer, so the action buttons are never clipped.
        primaryLabel.setMinWidth(0);
        numberLabel.setMinWidth(0);
        countryLabel.setMinWidth(0);
        timeLabel.setMinWidth(0);

        flagView.setFitHeight(FLAG_HEIGHT);
        flagView.setPreserveRatio(true);

        callButton.getStyleClass().addAll("accent", "recent-action");
        messageButton.getStyleClass().addAll("flat", "recent-action");
        callButton.setMinWidth(Region.USE_PREF_SIZE);
        messageButton.setMinWidth(Region.USE_PREF_SIZE);
        callButton.setOnAction(e -> onCall.accept(currentNumber));
        messageButton.setOnAction(e -> onMessage.accept(currentNumber));

        final Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        final HBox line1 = new HBox(8, primaryLabel, numberLabel, agoLabel, topSpacer, countLabel);
        line1.setAlignment(Pos.CENTER_LEFT);

        final Region bottomSpacer = new Region();
        HBox.setHgrow(bottomSpacer, Priority.ALWAYS);
        final HBox actions = new HBox(8, callButton, messageButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        final HBox line2 = new HBox(6, flagView, countryLabel, timeLabel, bottomSpacer, actions);
        line2.setAlignment(Pos.CENTER_LEFT);

        root = new VBox(4, line1, line2);
        // Clamp the cell to the list viewport width so content shrinks instead
        // of overflowing (which previously clipped the Call/Message buttons).
        setMaxWidth(Double.MAX_VALUE);
        setPrefWidth(0);
        root.setMinWidth(0);
        root.setMaxWidth(Double.MAX_VALUE);
        setGraphic(root);
    }

    @Override
    protected void updateItem(final RecentCallRow row, final boolean empty) {
        super.updateItem(row, empty);
        if (empty || row == null) {
            setGraphic(null);
            return;
        }
        setGraphic(root);
        currentNumber = row.number();

        final Instant now = Instant.now();
        final Optional<Lead> lead = row.lead();
        if (lead.isPresent()) {
            primaryLabel.setText(lead.get().displayName());
            numberLabel.setText(row.number());
            numberLabel.setManaged(true);
            numberLabel.setVisible(true);
        } else {
            primaryLabel.setText(row.number());
            numberLabel.setManaged(false);
            numberLabel.setVisible(false);
        }
        agoLabel.setText(RecentCallFormatter.timeAgo(row.lastCallAt(), now, localZone));
        countLabel.setText(RecentCallFormatter.callCountLabel(row.callCount()));

        final Optional<Country> country = row.country();
        if (country.isPresent()) {
            renderCountry(country.get());
        } else {
            flagView.setImage(null);
            flagView.setManaged(false);
            countryLabel.setText("Unknown");
            timeLabel.setText("");
        }
    }

    private void renderCountry(final Country country) {
        final Optional<Image> flag = FlagImages.load(country.isoCode());
        flagView.setManaged(flag.isPresent());
        flagView.setImage(flag.orElse(null));

        countryLabel.setText(country.displayName());

        final ZonedDateTime localNow = ZonedDateTime.now(country.zone());
        final String zoneShort = country.zone().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        timeLabel.setText("%s  ·  %s".formatted(TIME.format(localNow), zoneShort));
    }
}
