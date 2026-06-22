package com.elitale.coldbirds.coldcalling.ui;

import com.elitale.coldbirds.coldcalling.ui.support.CallDurationFormatter;
import com.elitale.coldbirds.coldcalling.ui.support.Motion;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Mini Call HUD — the alt-tab call pill. A small always-on-top, frameless, rounded
 * window that surfaces the live call (name · timer · REC dot · Mute · Hang up) when
 * the rep tabs away to a CRM or sheet. It is the only floating element in the app
 * (per the 6–0 calling-screen veto). All methods must run on the FX Application Thread.
 */
public final class CallHudWindow {

    private static final double WIDTH  = 280;
    private static final double HEIGHT = 64;
    private static final double EDGE_GAP = 24;

    private final Stage    owner;
    private final Stage    stage;
    private final Label    nameLabel  = new Label();
    private final Label    timerLabel = new Label("0:00");
    private final Region   recDot     = new Region();
    private final Button   muteButton;
    private final FontIcon muteIcon   = new FontIcon("bi-mic");
    private final AnimationTimer pulse;

    private long    connectedAtMillis = 0L;
    private long    lastShownSecond   = -1L;
    private double  recPhase          = 0.0;
    private boolean visible           = false;
    private boolean placed            = false;
    private double  dragOffsetX       = 0.0;
    private double  dragOffsetY       = 0.0;

    private Runnable        onHangUp     = () -> {};
    private Runnable        onMuteToggle = () -> {};
    private BooleanSupplier recording    = () -> false;

    public CallHudWindow(final Stage owner) {
        this.owner = Objects.requireNonNull(owner, "owner must not be null");

        nameLabel.getStyleClass().add("call-hud-name");
        timerLabel.getStyleClass().add("call-hud-timer");

        recDot.getStyleClass().add("call-rec-dot");
        recDot.setMinSize(8, 8);
        recDot.setPrefSize(8, 8);
        recDot.setMaxSize(8, 8);
        recDot.setVisible(false);

        final HBox meta = new HBox(6, recDot, timerLabel);
        meta.setAlignment(Pos.CENTER_LEFT);

        final VBox info = new VBox(2, nameLabel, meta);
        info.setAlignment(Pos.CENTER_LEFT);
        info.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(info, Priority.ALWAYS);

        muteIcon.setIconSize(16);
        muteButton = iconButton(muteIcon, "call-hud-btn");
        muteButton.setOnAction(e -> onMuteToggle.run());

        final FontIcon hangIcon = new FontIcon("bi-telephone-x-fill");
        hangIcon.setIconSize(16);
        final Button hangButton = iconButton(hangIcon, "call-hud-btn", "call-hud-btn--hangup");
        hangButton.setOnAction(e -> onHangUp.run());

        final HBox content = new HBox(10, info, muteButton, hangButton);
        content.setAlignment(Pos.CENTER_LEFT);
        content.getStyleClass().add("call-hud");
        content.setPadding(new Insets(10, 12, 10, 16));
        content.setPrefSize(WIDTH, HEIGHT);

        final Scene scene = new Scene(content, WIDTH, HEIGHT, Color.TRANSPARENT);
        scene.getStylesheets().add(
                Objects.requireNonNull(
                        CallHudWindow.class.getResource("/css/cupertino-light.css"),
                        "cupertino-light.css not found in UI resources"
                ).toExternalForm());

        stage = new Stage(StageStyle.TRANSPARENT);
        stage.initOwner(owner);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setScene(scene);

        // Drag the pill anywhere — the buttons consume their own clicks first.
        content.setOnMousePressed(e -> {
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        content.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });

        pulse = new AnimationTimer() {
            @Override
            public void handle(final long now) {
                tick();
            }
        };
    }

    /** Register the hang-up action (reuses the call card's hang-up callback). */
    public void setOnHangUp(final Runnable cb) {
        this.onHangUp = Objects.requireNonNull(cb);
    }

    /** Register the mute toggle (routes through the call card so both stay in sync). */
    public void setOnMuteToggle(final Runnable cb) {
        this.onMuteToggle = Objects.requireNonNull(cb);
    }

    /** Register the live recording-state source that drives the REC dot. */
    public void setRecordingState(final BooleanSupplier supplier) {
        this.recording = Objects.requireNonNull(supplier);
    }

    /**
     * Show (or refresh) the pill for a live call. Fades + scales in on first appearance;
     * subsequent calls just refresh the name, timer anchor, and mute glyph.
     */
    public void showFor(final String contactName, final Instant connectedAt, final boolean muted) {
        Objects.requireNonNull(contactName, "contactName must not be null");
        Objects.requireNonNull(connectedAt, "connectedAt must not be null");
        nameLabel.setText(contactName);
        connectedAtMillis = connectedAt.toEpochMilli();
        syncMuted(muted);
        refreshTimer();
        if (visible) {
            return;
        }
        visible = true;
        lastShownSecond = -1L;
        recPhase = 0.0;
        stage.show();
        if (!placed) {
            positionInitially();
            placed = true;
        }
        pulse.start();
        playEnter();
    }

    /** Dismiss the pill (fades out) — on refocus or call end. */
    public void hide() {
        if (!visible) {
            return;
        }
        visible = false;
        pulse.stop();
        playExit();
    }

    /** Reflect the current mute state on the pill's Mute button. */
    public void syncMuted(final boolean muted) {
        muteIcon.setIconLiteral(muted ? "bi-mic-mute-fill" : "bi-mic");
        if (muted) {
            if (!muteButton.getStyleClass().contains("call-hud-btn--muted")) {
                muteButton.getStyleClass().add("call-hud-btn--muted");
            }
        } else {
            muteButton.getStyleClass().remove("call-hud-btn--muted");
        }
    }

    /** Tear down at shutdown. */
    public void dispose() {
        pulse.stop();
        visible = false;
        stage.hide();
    }

    private void tick() {
        final long elapsedSec = Math.max(0L, (System.currentTimeMillis() - connectedAtMillis) / 1000L);
        if (elapsedSec != lastShownSecond) {
            lastShownSecond = elapsedSec;
            refreshTimer();
        }
        final boolean rec = recording.getAsBoolean();
        recDot.setVisible(rec);
        if (!rec || Motion.isReduced()) {
            recPhase = 0.0;
            recDot.setOpacity(1.0);
            return;
        }
        recPhase += 0.05;
        final double sine = 0.5 + 0.5 * Math.sin(recPhase);
        recDot.setOpacity(0.35 + 0.65 * sine);
    }

    private void refreshTimer() {
        final long elapsed = Math.max(0L, System.currentTimeMillis() - connectedAtMillis);
        timerLabel.setText(CallDurationFormatter.format(java.time.Duration.ofMillis(elapsed)));
    }

    private void playEnter() {
        final Region content = (Region) stage.getScene().getRoot();
        if (Motion.isReduced()) {
            content.setOpacity(1.0);
            content.setScaleX(1.0);
            content.setScaleY(1.0);
            return;
        }
        content.setOpacity(0.0);
        content.setScaleX(0.92);
        content.setScaleY(0.92);
        final FadeTransition fade = new FadeTransition(Duration.millis(150), content);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        final ScaleTransition scale = new ScaleTransition(Duration.millis(150), content);
        scale.setFromX(0.92);
        scale.setFromY(0.92);
        scale.setToX(1.0);
        scale.setToY(1.0);
        fade.play();
        scale.play();
    }

    private void playExit() {
        final Region content = (Region) stage.getScene().getRoot();
        if (Motion.isReduced()) {
            stage.hide();
            return;
        }
        final FadeTransition fade = new FadeTransition(Duration.millis(150), content);
        fade.setFromValue(content.getOpacity());
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            if (!visible) {
                stage.hide();
            }
        });
        fade.play();
    }

    private void positionInitially() {
        final double ox = owner.getX();
        final double oy = owner.getY();
        final double ow = owner.getWidth();
        final double oh = owner.getHeight();
        if (Double.isNaN(ox) || ow <= 0 || oh <= 0) {
            stage.centerOnScreen();
            return;
        }
        stage.setX(ox + ow - WIDTH - EDGE_GAP);
        stage.setY(oy + oh - HEIGHT - EDGE_GAP);
    }

    private static Button iconButton(final FontIcon icon, final String... styleClasses) {
        final Button button = new Button();
        button.setGraphic(icon);
        button.getStyleClass().addAll(styleClasses);
        return button;
    }
}
