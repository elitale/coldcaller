package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.ui.support.LeadSelectionModel;
import com.elitale.coldbirds.coldcalling.ui.support.LeadStatusLabel;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Bulk-action bar shown above the grid when one or more leads are selected: set status,
 * toggle DNC, add to a list, or delete — applied to the whole {@link LeadSelectionModel}.
 *
 * <p>View helper (not unit-tested). Status/DNC/delete run through {@link LeadService}
 * off the FX thread; "Add to list" is delegated to the controller (it owns the dialog +
 * {@code CallListService}). Every mutation fires {@code onChanged} to refresh the grid.
 */
final class LeadBulkBar {

    private final HBox node = new HBox(8);
    private final LeadService leadService;
    private final LeadSelectionModel selection;
    private final Label countLabel = new Label();

    private Runnable onChanged = () -> { };
    private Runnable onAddToList = () -> { };

    LeadBulkBar(LeadService leadService, LeadSelectionModel selection) {
        this.leadService = Objects.requireNonNull(leadService, "leadService must not be null");
        this.selection = Objects.requireNonNull(selection, "selection must not be null");
        build();
    }

    HBox node() {
        return node;
    }

    /** Re-run the grid query after a bulk mutation (also clears the selection). */
    void setOnChanged(Runnable callback) {
        this.onChanged = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Open the add-to-list flow (controller-owned: needs the dialog + CallListService). */
    void setOnAddToList(Runnable callback) {
        this.onAddToList = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Show or hide the bar based on the current selection, and update the count label. */
    void syncVisibility() {
        boolean any = !selection.isEmpty();
        node.setVisible(any);
        node.setManaged(any);
        countLabel.setText(selection.count() + " selected");
    }

    private void build() {
        node.setAlignment(Pos.CENTER_LEFT);
        node.setPadding(new Insets(8, 32, 8, 32));
        node.getStyleClass().add("bulk-bar");
        node.setVisible(false);
        node.setManaged(false);

        countLabel.getStyleClass().add("type-label");

        MenuButton statusBtn = new MenuButton("Set status");
        for (LeadStatus status : LeadStatus.values()) {
            MenuItem item = new MenuItem(LeadStatusLabel.of(status));
            item.setOnAction(e -> applyStatus(status));
            statusBtn.getItems().add(item);
        }

        MenuButton dncBtn = new MenuButton("DNC");
        MenuItem markDnc = new MenuItem("Mark Do-Not-Call");
        markDnc.setOnAction(e -> applyDnc(true));
        MenuItem clearDnc = new MenuItem("Clear Do-Not-Call");
        clearDnc.setOnAction(e -> applyDnc(false));
        dncBtn.getItems().addAll(markDnc, clearDnc);

        Button addToList = new Button("Add to list");
        addToList.getStyleClass().add("flat");
        addToList.setOnAction(e -> onAddToList.run());

        Button delete = new Button("Delete");
        delete.getStyleClass().add("danger");
        delete.setOnAction(e -> deleteSelected());

        Button clear = new Button("Clear");
        clear.getStyleClass().add("flat");
        clear.setOnAction(e -> {
            selection.clear();
            onChanged.run();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        node.getChildren().addAll(countLabel, spacer, statusBtn, dncBtn, addToList, delete, clear);
    }

    private void applyStatus(LeadStatus status) {
        List<LeadId> ids = selection.selectedIds();
        if (ids.isEmpty()) return;
        CompletableFuture
                .runAsync(() -> leadService.bulkSetStatus(ids, status))
                .thenRunAsync(this::afterMutation, Platform::runLater);
    }

    private void applyDnc(boolean dnc) {
        List<LeadId> ids = selection.selectedIds();
        if (ids.isEmpty()) return;
        CompletableFuture
                .runAsync(() -> leadService.bulkSetDnc(ids, dnc))
                .thenRunAsync(this::afterMutation, Platform::runLater);
    }

    private void deleteSelected() {
        List<LeadId> ids = selection.selectedIds();
        if (ids.isEmpty()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Leads");
        confirm.setHeaderText("Delete " + ids.size() + " selected lead(s)?");
        confirm.setContentText("They will be soft-deleted and hidden from all lists.");
        confirm.showAndWait().ifPresent(button -> {
            if (button == ButtonType.OK) {
                CompletableFuture
                        .runAsync(() -> leadService.bulkDelete(ids))
                        .thenRunAsync(this::afterMutation, Platform::runLater);
            }
        });
    }

    private void afterMutation() {
        selection.clear();
        onChanged.run();
    }
}
