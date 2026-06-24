package com.elitale.coldbirds.coldcalling.ui;

import com.elitale.coldbirds.coldcalling.ui.support.NavSelectionModel;
import com.elitale.coldbirds.coldcalling.ui.support.NavSelectionModel.Destination;
import com.elitale.coldbirds.coldcalling.ui.support.RegistrationHealth;
import com.elitale.coldbirds.coldcalling.ui.support.SidebarStatusModel;
import com.elitale.coldbirds.coldcalling.ui.support.SidebarStatusModel.ReturnKind;
import com.elitale.coldbirds.coldcalling.ui.support.Motion;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The left navigation rail: branded header + honest SIP status dot, iconified nav items with a
 * single active state, a clickable return row (inbound ring → live call → power dialer), a
 * Messages activity dot, and a "dialing from" account chip.
 *
 * <p>Owns the headless models ({@link NavSelectionModel}, {@link SidebarStatusModel},
 * {@link RegistrationHealth}) and a single 1s tick that advances the live timer, escalates the
 * registration grace window, and refreshes the power-dialer/account snapshots. Motion (pulse) is
 * reserved for attention-needed states (inbound ring, reconnecting) and gated by {@link Motion}.
 *
 * <p>JavaFX view — not unit-tested; all decision logic lives in the models. Every method runs on
 * the FX Application Thread (callers marshal via {@code Platform.runLater}).
 */
final class SidebarView {

    private static final double WIDTH = 190;

    /** Sink for navigation + return-to-call intents. */
    interface Listener {
        void onNavigate(Destination destination);
        void onReturnRow(ReturnKind kind);
        /** The user picked an outbound number to dial from; empty = automatic rotation. */
        void onSelectOutbound(Optional<String> e164);
    }

    /** One selectable owned number for the "dialing from" picker. */
    record NumberOption(String e164, String label) {
        NumberOption {
            Objects.requireNonNull(e164, "e164 must not be null");
            Objects.requireNonNull(label, "label must not be null");
        }
    }

    private final NavSelectionModel nav = new NavSelectionModel();
    private final SidebarStatusModel status = new SidebarStatusModel();
    private final RegistrationHealth health = new RegistrationHealth();

    private final Listener listener;
    private final Supplier<Optional<String>> powerDialerProgress;

    private final VBox root = new VBox(2);
    private final Region statusDot = new Region();
    private final Label statusLabel = new Label();
    private final Map<Destination, Button> navButtons = new EnumMap<>(Destination.class);
    private final Region messagesBadge = new Region();
    private final Button returnRow = new Button();
    private final MenuButton accountChip = new MenuButton();

    private Instant liveSince;
    private FadeTransition dotPulse;
    private FadeTransition ringPulse;

    SidebarView(Listener listener, Supplier<Optional<String>> powerDialerProgress) {
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
        this.powerDialerProgress =
                Objects.requireNonNull(powerDialerProgress, "powerDialerProgress must not be null");

        build();
        status.addListener(this::renderStatus);
        nav.addListener(d -> renderActive());

        Timeline tick = new Timeline(new KeyFrame(Duration.seconds(1), e -> onTick()));
        tick.setCycleCount(Animation.INDEFINITE);
        tick.play();

        renderActive();
        renderStatus();
    }

    Region node() {
        return root;
    }

    // ── Inputs from MainWindow (FX thread) ─────────────────────────────────────

    void onRegistrationChanged(boolean registered) {
        health.onRegistrationChanged(registered);
        status.setRegistration(health.current());
    }

    void onCredentialsAbsent() {
        health.onCredentialsAbsent();
        status.setRegistration(health.current());
    }

    void onLiveCall(boolean live, Instant connectedAt) {
        liveSince = live ? connectedAt : null;
        status.setLiveCall(live);
    }

    void onInboundRing(Optional<String> caller) {
        status.setInboundRing(caller);
    }

    void notifyInboundSms() {
        status.setMessagesActivity(true);
    }

    void markMessagesSeen() {
        status.setMessagesActivity(false);
    }

    /** Populate the "dialing from" picker with the active owned numbers + current pin. */
    void setOutboundOptions(java.util.List<NumberOption> options, Optional<String> pinnedE164) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(pinnedE164, "pinnedE164 must not be null");
        accountChip.getItems().clear();
        final ToggleGroup group = new ToggleGroup();

        final RadioMenuItem auto = new RadioMenuItem(
                options.size() > 1 ? "Auto · rotate across " + options.size() : "Auto");
        auto.setToggleGroup(group);
        auto.setSelected(pinnedE164.isEmpty());
        auto.setOnAction(e -> listener.onSelectOutbound(Optional.empty()));
        accountChip.getItems().add(auto);
        if (!options.isEmpty()) {
            accountChip.getItems().add(new SeparatorMenuItem());
        }
        for (NumberOption option : options) {
            final RadioMenuItem item = new RadioMenuItem(option.label());
            item.setToggleGroup(group);
            item.setSelected(pinnedE164.filter(p -> p.equals(option.e164())).isPresent());
            item.setOnAction(e -> listener.onSelectOutbound(Optional.of(option.e164())));
            accountChip.getItems().add(item);
        }
        accountChip.setText(outboundLabel(options, pinnedE164));
    }

    private static String outboundLabel(java.util.List<NumberOption> options, Optional<String> pinnedE164) {
        if (pinnedE164.isPresent()) {
            final String label = options.stream()
                    .filter(o -> o.e164().equals(pinnedE164.get()))
                    .map(NumberOption::label)
                    .findFirst()
                    .orElse(pinnedE164.get());
            return "Dialing from " + label;
        }
        if (options.size() > 1) {
            return "Rotating · " + options.size() + " numbers";
        }
        if (options.size() == 1) {
            return "Dialing from " + options.get(0).label();
        }
        return "No calling number";
    }

    void setActive(Destination destination) {
        nav.select(destination);
    }

    // ── Build ──────────────────────────────────────────────────────────────────

    private void build() {
        root.setPrefWidth(WIDTH);
        root.setMinWidth(WIDTH);
        root.setPadding(new Insets(20, 12, 16, 12));
        root.getStyleClass().add("nav-rail");

        Label wordmark = new Label("coldCalling");
        wordmark.getStyleClass().add("title-2");

        statusDot.getStyleClass().add("status-dot");
        statusLabel.getStyleClass().add("caption");
        HBox statusRow = new HBox(6, statusDot, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.setPadding(new Insets(2, 0, 10, 2));

        messagesBadge.getStyleClass().add("nav-badge");
        messagesBadge.setVisible(false);
        messagesBadge.setManaged(false);

        Button dialer = navButton(Destination.DIALER, "bi-grid-3x3-gap-fill", "Dialer");
        Button leads = navButton(Destination.LEADS, "bi-people-fill", "Leads");
        Button history = navButton(Destination.CALL_HISTORY, "bi-clock-history", "Call History");
        Button messages = navButton(Destination.MESSAGES, "bi-chat-left-text-fill", "Messages");
        Button power = navButton(Destination.POWER_DIALER, "bi-lightning-charge-fill", "Power Dialer");
        Button settings = navButton(Destination.SETTINGS, "bi-gear-fill", "Settings");

        returnRow.setMaxWidth(Double.MAX_VALUE);
        returnRow.getStyleClass().add("live-pill");
        returnRow.setVisible(false);
        returnRow.setManaged(false);
        returnRow.setOnAction(e -> listener.onReturnRow(status.returnRow().kind()));

        accountChip.setMaxWidth(Double.MAX_VALUE);
        accountChip.getStyleClass().add("account-chip");
        accountChip.setText("No calling number");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        root.getChildren().addAll(
                wordmark, statusRow, new Separator(), gap(8),
                dialer, leads, history, messages, power,
                returnRow,
                spacer,
                accountChip, gap(6), new Separator(), gap(4),
                settings);
    }

    private Button navButton(Destination destination, String iconLiteral, String text) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.getStyleClass().add("nav-icon");
        Label label = new Label(text);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        HBox box = new HBox(10, icon, label, grow);
        box.setAlignment(Pos.CENTER_LEFT);
        if (destination == Destination.MESSAGES) {
            box.getChildren().add(messagesBadge);
        }

        Button button = new Button();
        button.setGraphic(box);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.getStyleClass().add("nav-item");
        button.setOnAction(e -> listener.onNavigate(destination));
        navButtons.put(destination, button);
        return button;
    }

    // ── Render ──────────────────────────────────────────────────────────────────

    private void onTick() {
        status.setRegistration(health.current());           // grace-window escalation
        status.setPowerDialer(powerDialerProgress.get());    // session start/stop/advance
        renderReturnRow();                                   // advance the live timer
    }

    private void renderStatus() {
        renderDot();
        renderReturnRow();
        messagesBadge.setVisible(status.messagesActivity());
        messagesBadge.setManaged(status.messagesActivity());
    }

    private void renderDot() {
        RegistrationHealth.State state = status.registration();
        statusDot.getStyleClass().removeAll("ok", "warn", "off");
        switch (state) {
            case REGISTERED -> { statusDot.getStyleClass().add("ok"); statusLabel.setText("Connected"); }
            case RECONNECTING -> { statusDot.getStyleClass().add("warn"); statusLabel.setText("Reconnecting…"); }
            case OFFLINE -> { statusDot.getStyleClass().add("off"); statusLabel.setText("Offline"); }
        }
        statusLabel.setTooltip(new Tooltip(switch (state) {
            case REGISTERED -> "SIP registered — your line is ready.";
            case RECONNECTING -> "Re-registering with your SIP provider…";
            case OFFLINE -> "Not registered — check your Twilio/SIP credentials in Settings.";
        }));
        pulse(statusDot, dotPulse, state == RegistrationHealth.State.RECONNECTING, p -> dotPulse = p);
    }

    private void renderReturnRow() {
        SidebarStatusModel.ReturnRow row = status.returnRow();
        boolean show = row.kind() != ReturnKind.NONE;
        returnRow.setVisible(show);
        returnRow.setManaged(show);
        returnRow.getStyleClass().removeAll("ring", "live", "power");
        boolean pulseRow = false;
        switch (row.kind()) {
            case INBOUND_RING -> {
                returnRow.getStyleClass().add("ring");
                returnRow.setText("◉  Incoming · " + row.label());
                pulseRow = true;
            }
            case LIVE_CALL -> {
                returnRow.getStyleClass().add("live");
                returnRow.setText("●  Live · " + elapsed());
            }
            case POWER_DIALER -> {
                returnRow.getStyleClass().add("power");
                returnRow.setText("⚡  " + row.label());
            }
            case NONE -> returnRow.setText("");
        }
        pulse(returnRow, ringPulse, pulseRow, p -> ringPulse = p);
    }

    private void renderActive() {
        for (Map.Entry<Destination, Button> entry : navButtons.entrySet()) {
            boolean active = nav.isActive(entry.getKey());
            entry.getValue().getStyleClass().remove("active");
            if (active) {
                entry.getValue().getStyleClass().add("active");
            }
        }
    }

    private String elapsed() {
        if (liveSince == null) {
            return "00:00";
        }
        long secs = Math.max(0, java.time.Duration.between(liveSince, Instant.now()).getSeconds());
        return String.format("%02d:%02d", secs / 60, secs % 60);
    }

    /** Start/stop a gentle fade pulse on a node; a no-op (steady) under reduce-motion. */
    private void pulse(Region node, FadeTransition existing, boolean on,
                       java.util.function.Consumer<FadeTransition> store) {
        if (on && !Motion.isReduced()) {
            if (existing == null) {
                FadeTransition fade = new FadeTransition(Duration.millis(800), node);
                fade.setFromValue(1.0);
                fade.setToValue(0.45);
                fade.setAutoReverse(true);
                fade.setCycleCount(Animation.INDEFINITE);
                fade.play();
                store.accept(fade);
            }
        } else if (existing != null) {
            existing.stop();
            node.setOpacity(1.0);
            store.accept(null);
        }
    }

    private static Region gap(double height) {
        Region region = new Region();
        region.setPrefHeight(height);
        return region;
    }
}
