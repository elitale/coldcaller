package com.elitale.coldbirds.coldcalling.ui.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.model.PowerDialerSession;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.PowerDialerState;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.services.PowerDialerService;
import com.elitale.coldbirds.coldcalling.services.PowerDialerService.SessionStats;
import com.elitale.coldbirds.coldcalling.ui.support.DialTarget;
import com.elitale.coldbirds.coldcalling.ui.support.PowerDialerReadiness;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

/**
 * Controller for power-dialer-view.fxml.
 *
 * <p>The screen has two modes that swap in the centre: an <strong>idle</strong> panel (pick a list,
 * see how far it got, Start/Resume) and a <strong>session</strong> panel (live lead, stats,
 * Pause/Stop/Next). There is no list authoring here — leads are managed on the Leads screen
 * ({@link #setOnGoToLeads}); this screen only selects a list and dials it, resuming from the first
 * un-dialed lead.
 *
 * <p>Threading: all FXML callbacks run on the FX Application Thread. Service calls that do I/O are
 * dispatched to a background thread via {@link CompletableFuture}; service event callbacks fire on
 * non-FX threads and are routed back via {@link Platform#runLater}.
 */
public final class PowerDialerController {

    /** How many upcoming leads to preview in the "Up Next" queue. */
    private static final int UP_NEXT_LIMIT = 5;

    // ── FXML: left (list selector) ──────────────────────────────────────────
    @FXML private ListView<DialTarget> callListView;
    @FXML private Label                statsLabel;

    // ── FXML: centre / idle panel ───────────────────────────────────────────
    @FXML private VBox   idleBox;
    @FXML private Label  selectedTitle;
    @FXML private Label  readinessLabel;
    @FXML private Button startBtn;
    @FXML private Button buildListBtn;

    // ── FXML: centre / session panel ────────────────────────────────────────
    @FXML private VBox   sessionBox;
    @FXML private Label  noActiveLabel;
    @FXML private VBox   leadDetails;
    @FXML private Label  leadNameLabel;
    @FXML private Label  leadPhoneLabel;
    @FXML private Label  leadCompanyLabel;
    @FXML private Label  dialedCountLabel;
    @FXML private Label  connectedCountLabel;
    @FXML private Label  remainingCountLabel;
    @FXML private Button pauseBtn;
    @FXML private Button stopBtn;
    @FXML private Button advanceBtn;
    @FXML private VBox             upNextSection;
    @FXML private ListView<Lead> upNextView;

    // ── State ────────────────────────────────────────────────────────────────
    private PowerDialerService powerDialerService;
    private Runnable goToLeads = () -> {};
    private final ObservableList<DialTarget> targets = FXCollections.observableArrayList();
    private final ObservableList<Lead>       upNext  = FXCollections.observableArrayList();

    /** Required no-arg constructor for FXMLLoader. */
    public PowerDialerController() {}

    public void setPowerDialerService(PowerDialerService powerDialerService) {
        this.powerDialerService = Objects.requireNonNull(powerDialerService);
    }

    /** Navigate-to-Leads action, wired by the host window. */
    public void setOnGoToLeads(Runnable goToLeads) {
        this.goToLeads = Objects.requireNonNull(goToLeads);
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        callListView.setItems(targets);
        callListView.setCellFactory(lv -> PowerDialerCells.target());
        callListView.setPlaceholder(new Label("No leads yet. Add leads on the Leads screen."));
        callListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> renderReadiness(sel));

        upNextView.setItems(upNext);
        upNextView.setCellFactory(lv -> PowerDialerCells.upNext());
        upNextView.setPlaceholder(new Label("End of list"));

        powerDialerService.setOnSessionChanged(s -> Platform.runLater(() -> applySessionState(s)));
        powerDialerService.setOnLeadChanged(c -> Platform.runLater(() -> applyLead(c)));
        powerDialerService.setOnStatsChanged(s -> Platform.runLater(() -> applyStats(s)));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Reload lists, auto-select the last-used one, and sync the session panel; call on navigate. */
    public void refresh() {
        reloadLists();
        renderSession(powerDialerService.getCurrentSession());
    }

    private void reloadLists() {
        CompletableFuture
                .supplyAsync(() -> new Loaded(powerDialerService.getCallLists(),
                        powerDialerService.countAllLeads(),
                        powerDialerService.lastUsedListId(),
                        powerDialerService.lastUsedAllLeads()))
                .thenAcceptAsync(d -> {
                    final List<DialTarget> items = new ArrayList<>();
                    items.add(new DialTarget.AllLeads(d.allLeadsCount()));
                    d.lists().forEach(l -> items.add(new DialTarget.OneList(l)));
                    targets.setAll(items);
                    selectTarget(d);
                }, Platform::runLater);
    }

    private record Loaded(List<CallList> lists, int allLeadsCount,
                          Optional<CallListId> lastId, boolean lastAll) {}

    /** Auto-select the last-used target — "All Leads" by default (first run or after dialing it). */
    private void selectTarget(Loaded d) {
        final DialTarget target = (d.lastAll() || d.lastId().isEmpty())
                ? targets.get(0) // "All Leads" is always first
                : targets.stream()
                        .filter(t -> t instanceof DialTarget.OneList o && o.list().id().equals(d.lastId().get()))
                        .findFirst()
                        .orElse(targets.get(0));
        callListView.getSelectionModel().select(target);
        renderReadiness(target); // ensure render even when selection is unchanged
    }

    // ── FXML action handlers ──────────────────────────────────────────────────

    @FXML
    private void onStart() {
        final DialTarget selected = callListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("Nothing selected", "Please select a target before starting.");
            return;
        }
        final CompletableFuture<Result<Void>> started = switch (selected) {
            case DialTarget.AllLeads ignored ->
                    CompletableFuture.supplyAsync(powerDialerService::startAllLeads);
            case DialTarget.OneList o ->
                    CompletableFuture.supplyAsync(() -> powerDialerService.start(o.list().id()));
        };
        started.thenAcceptAsync(r -> {
            if (r instanceof Result.Err<?> err) showAlert("Cannot start", err.message());
        }, Platform::runLater);
    }

    @FXML
    private void onPause() {
        final boolean paused = powerDialerService.getCurrentSession()
                .filter(s -> s.state() instanceof PowerDialerState.Paused).isPresent();
        CompletableFuture.runAsync(paused ? powerDialerService::resume : powerDialerService::pause);
    }

    @FXML
    private void onStop() {
        CompletableFuture.runAsync(powerDialerService::stop);
    }

    @FXML
    private void onAdvance() {
        CompletableFuture.runAsync(powerDialerService::advance);
    }

    @FXML
    private void onGoToLeads() {
        goToLeads.run();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** React to a session change event; when the session just ended, reload list progress. */
    private void applySessionState(Optional<PowerDialerSession> sessionOpt) {
        if (!renderSession(sessionOpt)) {
            reloadLists(); // session ended → refresh "X of N left" / resume labels
        }
    }

    /** Toggle the idle/session panels and live stats; returns whether a session is active. */
    private boolean renderSession(Optional<PowerDialerSession> sessionOpt) {
        final boolean running = sessionOpt.filter(s -> s.state() instanceof PowerDialerState.Running).isPresent();
        final boolean paused  = sessionOpt.filter(s -> s.state() instanceof PowerDialerState.Paused).isPresent();
        final boolean active  = running || paused;
        setNodeVisible(sessionBox, active);
        setNodeVisible(idleBox, !active);
        pauseBtn.setDisable(!active);
        stopBtn.setDisable(!active);
        pauseBtn.setText(paused ? "Resume" : "Pause");
        setNodeVisible(upNextSection, active);
        if (active) {
            sessionOpt.ifPresent(s -> {
                dialedCountLabel.setText(String.valueOf(s.dialedCount()));
                connectedCountLabel.setText(String.valueOf(s.connectedCount()));
            });
            refreshUpNext();
        } else {
            upNext.clear();
            resetSessionStats();
            statsLabel.setText("");
        }
        return active;
    }

    private void renderReadiness(DialTarget target) {
        if (target == null) {
            selectedTitle.setText("Nothing selected");
            readinessLabel.setText("Select a target on the left");
            startBtn.setText("Start dialing");
            startBtn.setDisable(true);
            setNodeVisible(buildListBtn, false);
            return;
        }
        final PowerDialerReadiness r = target.readiness();
        selectedTitle.setText(target.title());
        readinessLabel.setText(r.statusLine());
        startBtn.setText(r.primaryLabel());
        startBtn.setDisable(!r.primaryEnabled());
        setNodeVisible(buildListBtn, r.showBuildList());
    }

    private void applyLead(Optional<Lead> leadOpt) {
        if (leadOpt.isEmpty()) {
            setNodeVisible(noActiveLabel, true);
            setNodeVisible(leadDetails, false);
            advanceBtn.setDisable(true);
            return;
        }
        final Lead c = leadOpt.get();
        leadNameLabel.setText(c.displayName());
        leadPhoneLabel.setText(c.phone().value());
        leadCompanyLabel.setText(c.company().orElse(""));
        setNodeVisible(noActiveLabel, false);
        setNodeVisible(leadDetails, true);
        refreshUpNext();
    }

    /** Reload the "Up Next" preview off the FX thread, then publish on it. */
    private void refreshUpNext() {
        CompletableFuture.supplyAsync(() -> powerDialerService.upcoming(UP_NEXT_LIMIT))
                .thenAcceptAsync(upNext::setAll, Platform::runLater);
    }

    private void applyStats(SessionStats stats) {
        dialedCountLabel.setText(String.valueOf(stats.dialedCount()));
        connectedCountLabel.setText(String.valueOf(stats.connectedCount()));
        remainingCountLabel.setText(String.valueOf(stats.remaining()));
        statsLabel.setText("Dialed " + stats.dialedCount()
                + "  ·  Connected " + stats.connectedCount()
                + "  ·  Remaining " + stats.remaining());
        advanceBtn.setDisable(false);
    }

    private void resetSessionStats() {
        dialedCountLabel.setText("0");
        connectedCountLabel.setText("0");
        remainingCountLabel.setText("—");
        advanceBtn.setDisable(true);
        setNodeVisible(noActiveLabel, true);
        setNodeVisible(leadDetails, false);
    }

    private static void setNodeVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static void showAlert(String title, String message) {
        Platform.runLater(() -> {
            final Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.setTitle(title);
            alert.showAndWait();
        });
    }
}
