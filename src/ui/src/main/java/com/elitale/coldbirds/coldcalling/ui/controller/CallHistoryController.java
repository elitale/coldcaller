package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.services.CallService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Controller for the Call History screen (call-history-view.fxml).
 * <p>
 * Shows the most recent calls with direction, number, duration, disposition,
 * and date columns. Supports inbound/outbound filtering and one-click call-back.
 * <p>
 * Threading: all methods must be called on the FX Application Thread.
 */
public final class CallHistoryController {

    private static final int MAX_ROWS = 200;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
            .ofPattern("MMM d, yyyy  h:mm a")
            .withZone(ZoneId.systemDefault());

    // ── FXML-injected fields ──────────────────────────────────────────────────

    @FXML private TableView<Call>            table;
    @FXML private TableColumn<Call, String>  directionCol;
    @FXML private TableColumn<Call, String>  numberCol;
    @FXML private TableColumn<Call, String>  durationCol;
    @FXML private TableColumn<Call, String>  dispositionCol;
    @FXML private TableColumn<Call, String>  dateCol;
    @FXML private Button                     callBackBtn;
    @FXML private ToggleButton               allBtn;
    @FXML private ToggleButton               inboundBtn;
    @FXML private ToggleButton               outboundBtn;

    // ── State ─────────────────────────────────────────────────────────────────

    private CallService      callService;
    private Consumer<String> onDial = ignored -> {};

    /** null = no filter (show all directions). */
    private CallDirection filterDirection = null;

    private final ObservableList<Call> allCalls = FXCollections.observableArrayList();
    private FilteredList<Call>         filtered;

    /** Default no-arg constructor — required by FXMLLoader. */
    public CallHistoryController() {}

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Must be called before {@code FXMLLoader.load()}. */
    public void setCallService(CallService service) {
        this.callService = Objects.requireNonNull(service, "service must not be null");
    }

    public void setOnDial(Consumer<String> callback) {
        this.onDial = Objects.requireNonNull(callback, "callback must not be null");
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        // Direction column — arrow + label, coloured per direction
        directionCol.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().direction() == CallDirection.INBOUND ? "↓ In" : "↑ Out"));
        directionCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    boolean inbound = item.startsWith("↓");
                    setStyle(inbound
                            ? "-fx-text-fill: #34C759; -fx-font-weight: bold;"
                            : "-fx-text-fill: #0071E3; -fx-font-weight: bold;");
                }
            }
        });

        numberCol.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().remoteNumber().value()));
        durationCol.setCellValueFactory(cell ->
                new SimpleStringProperty(formatDuration(cell.getValue().durationMs())));
        dispositionCol.setCellValueFactory(cell ->
                new SimpleStringProperty(formatDisposition(cell.getValue().disposition())));
        dateCol.setCellValueFactory(cell ->
                new SimpleStringProperty(DATE_FMT.format(cell.getValue().startedAt())));

        // Filtered list — predicate set by direction filter buttons
        filtered = new FilteredList<>(allCalls, c -> true);
        table.setItems(filtered);

        // Placeholder
        Label placeholder = new Label("No call history yet.");
        placeholder.getStyleClass().add("caption");
        table.setPlaceholder(placeholder);

        // Call Back button disabled unless a row is selected
        callBackBtn.disableProperty().bind(
                table.getSelectionModel().selectedItemProperty().isNull());

        // Double-click to call back
        table.setRowFactory(tv -> {
            TableRow<Call> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    onDial.accept(row.getItem().remoteNumber().value());
                }
            });
            return row;
        });

        // Direction filter toggle group
        ToggleGroup group = new ToggleGroup();
        allBtn.setToggleGroup(group);
        inboundBtn.setToggleGroup(group);
        outboundBtn.setToggleGroup(group);
        allBtn.setSelected(true);

        group.selectedToggleProperty().addListener((obs, old, newToggle) -> {
            if (newToggle == allBtn)      filterDirection = null;
            else if (newToggle == inboundBtn)  filterDirection = CallDirection.INBOUND;
            else if (newToggle == outboundBtn) filterDirection = CallDirection.OUTBOUND;
            applyFilter();
        });

        loadCalls();
    }

    // ── FXML event handlers ───────────────────────────────────────────────────

    @FXML
    private void onCallBack() {
        Call selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) {
            onDial.accept(selected.remoteNumber().value());
        }
    }

    @FXML
    private void onRefresh() {
        loadCalls();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void loadCalls() {
        if (callService == null) return;
        CompletableFuture
                .supplyAsync(() -> callService.findRecent(MAX_ROWS))
                .thenAcceptAsync(allCalls::setAll, Platform::runLater);
    }

    private void applyFilter() {
        final CallDirection dir = filterDirection;
        filtered.setPredicate(call ->
                dir == null || call.direction() == dir);
    }

    private static String formatDuration(Optional<Long> ms) {
        return ms.map(millis -> {
            long secs = millis / 1000L;
            return String.format("%d:%02d", secs / 60, secs % 60);
        }).orElse("—");
    }

    private static String formatDisposition(Optional<CallDisposition> disp) {
        return disp.map(d -> switch (d) {
            case CallDisposition.Interested    ignored -> "Interested";
            case CallDisposition.NotInterested ignored -> "Not Interested";
            case CallDisposition.Callback      c       -> "Callback";
            case CallDisposition.Voicemail     ignored -> "Voicemail";
            case CallDisposition.NoAnswer      ignored -> "No Answer";
            case CallDisposition.Busy          ignored -> "Busy";
            case CallDisposition.DNC           ignored -> "DNC";
            case CallDisposition.Failed        f       -> "Failed: " + f.reason();
        }).orElse("—");
    }
}
