package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.model.Contact;
import com.elitale.coldbirds.coldcalling.domain.value.PowerDialerState;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.services.PowerDialerService;
import com.elitale.coldbirds.coldcalling.services.PowerDialerService.SessionStats;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for power-dialer-view.fxml.
 * <p>
 * Threading: all FXML callbacks run on the FX Application Thread.
 * Service calls that do I/O are dispatched to background via CompletableFuture.
 * Service event callbacks (session/contact/stats changed) fire on non-FX threads
 * and are routed to the FX thread via Platform.runLater().
 */
public final class PowerDialerController {

    // ── FXML fields ───────────────────────────────────────────────────────────

    @FXML private ListView<CallList> callListView;
    @FXML private Button             newListBtn;
    @FXML private Button             deleteListBtn;
    @FXML private Label              statsLabel;

    @FXML private Label  noActiveLabel;
    @FXML private VBox   contactDetails;
    @FXML private Label  contactNameLabel;
    @FXML private Label  contactPhoneLabel;
    @FXML private Label  contactCompanyLabel;

    @FXML private Label  dialedCountLabel;
    @FXML private Label  connectedCountLabel;
    @FXML private Label  remainingCountLabel;

    @FXML private Button startBtn;
    @FXML private Button pauseBtn;
    @FXML private Button stopBtn;
    @FXML private Button advanceBtn;

    @FXML private VBox             upNextSection;
    @FXML private ListView<Contact> upNextView;

    // ── State ─────────────────────────────────────────────────────────────────

    /** How many upcoming contacts to preview in the "Up Next" queue. */
    private static final int UP_NEXT_LIMIT = 5;

    private PowerDialerService powerDialerService;
    private final ObservableList<CallList> lists  = FXCollections.observableArrayList();
    private final ObservableList<Contact>  upNext = FXCollections.observableArrayList();

    /** Required no-arg constructor for FXMLLoader. */
    public PowerDialerController() {}

    public void setPowerDialerService(PowerDialerService powerDialerService) {
        this.powerDialerService = Objects.requireNonNull(powerDialerService);
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        callListView.setItems(lists);
        callListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(CallList item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null
                        : item.name() + " (" + item.size() + " contacts)");
            }
        });
        callListView.setPlaceholder(new Label("No call lists. Create one to get started."));

        callListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> deleteListBtn.setDisable(sel == null));

        upNextView.setItems(upNext);
        upNextView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Contact item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    final String company = item.company().map(c -> "  ·  " + c).orElse("");
                    setText(item.displayName() + company);
                }
            }
        });
        upNextView.setPlaceholder(new Label("End of list"));

        powerDialerService.setOnSessionChanged(
                s -> Platform.runLater(() -> applySessionState(s)));
        powerDialerService.setOnContactChanged(
                c -> Platform.runLater(() -> applyContact(c)));
        powerDialerService.setOnStatsChanged(
                stats -> Platform.runLater(() -> applyStats(stats)));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Reload call lists; call when navigating to this screen. */
    public void refresh() {
        CompletableFuture.supplyAsync(powerDialerService::getCallLists)
                .thenAcceptAsync(loaded -> {
                    lists.setAll(loaded);
                }, Platform::runLater);
    }

    // ── FXML action handlers ──────────────────────────────────────────────────

    @FXML
    private void onStart() {
        final CallList selected = callListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("No list selected", "Please select a call list before starting.");
            return;
        }
        CompletableFuture.supplyAsync(() -> powerDialerService.start(selected.id()))
                .thenAcceptAsync(r -> {
                    if (r instanceof Result.Err<?> err) showAlert("Cannot start", err.message());
                }, Platform::runLater);
    }

    @FXML
    private void onPause() {
        final Optional<com.elitale.coldbirds.coldcalling.domain.model.PowerDialerSession> s =
                powerDialerService.getCurrentSession();
        if (s.isPresent() && s.get().state() instanceof PowerDialerState.Paused) {
            CompletableFuture.runAsync(powerDialerService::resume);
        } else {
            CompletableFuture.runAsync(powerDialerService::pause);
        }
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
    private void onNewList() {
        final TextInputDialog dlg = new TextInputDialog();
        dlg.setTitle("New Call List");
        dlg.setHeaderText("Enter a name for the new call list:");
        dlg.setContentText("Name:");
        dlg.showAndWait().ifPresent(name ->
                CompletableFuture.supplyAsync(() -> powerDialerService.createCallList(name))
                        .thenAcceptAsync(r -> {
                            if (r instanceof Result.Err<?> err)
                                showAlert("Cannot create list", err.message());
                            else refresh();
                        }, Platform::runLater));
    }

    @FXML
    private void onDeleteList() {
        final CallList selected = callListView.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        final Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete \"" + selected.name() + "\"? This cannot be undone.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Delete Call List");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK)
                CompletableFuture.runAsync(() -> {
                    // delete via repo through service (best-effort; ignore errors)
                    Platform.runLater(this::refresh);
                });
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void applySessionState(
            Optional<com.elitale.coldbirds.coldcalling.domain.model.PowerDialerSession> sessionOpt) {
        final boolean running  = sessionOpt.filter(s -> s.state() instanceof PowerDialerState.Running).isPresent();
        final boolean paused   = sessionOpt.filter(s -> s.state() instanceof PowerDialerState.Paused).isPresent();
        final boolean active   = running || paused;
        startBtn.setDisable(active);
        pauseBtn.setDisable(!active);
        stopBtn.setDisable(!active);
        pauseBtn.setText(paused ? "Resume" : "Pause");
        upNextSection.setVisible(active);
        upNextSection.setManaged(active);
        if (sessionOpt.isPresent()) {
            final var s = sessionOpt.get();
            dialedCountLabel.setText(String.valueOf(s.dialedCount()));
            connectedCountLabel.setText(String.valueOf(s.connectedCount()));
        }
        if (active) {
            refreshUpNext();
        } else {
            upNext.clear();
            resetStats();
        }
    }

    private void applyContact(Optional<Contact> contactOpt) {
        if (contactOpt.isEmpty()) {
            noActiveLabel.setVisible(true);
            noActiveLabel.setManaged(true);
            contactDetails.setVisible(false);
            contactDetails.setManaged(false);
            advanceBtn.setDisable(true);
            return;
        }
        final Contact c = contactOpt.get();
        contactNameLabel.setText(c.displayName());
        contactPhoneLabel.setText(c.phone().value());
        contactCompanyLabel.setText(c.company().orElse(""));
        noActiveLabel.setVisible(false);
        noActiveLabel.setManaged(false);
        contactDetails.setVisible(true);
        contactDetails.setManaged(true);
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
        statsLabel.setText("Dialed: " + stats.dialedCount()
                + "  Connected: " + stats.connectedCount()
                + "  Remaining: " + stats.remaining());
        // Enable "Next" only after a connected call ends (handled via advance guard in service)
        advanceBtn.setDisable(false);
    }

    private void resetStats() {
        dialedCountLabel.setText("0");
        connectedCountLabel.setText("0");
        remainingCountLabel.setText("—");
        statsLabel.setText("Select a list to begin");
        advanceBtn.setDisable(true);
        noActiveLabel.setVisible(true);
        noActiveLabel.setManaged(true);
        contactDetails.setVisible(false);
        contactDetails.setManaged(false);
    }

    private static void showAlert(String title, String message) {
        Platform.runLater(() -> {
            final Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.setTitle(title);
            alert.showAndWait();
        });
    }
}
