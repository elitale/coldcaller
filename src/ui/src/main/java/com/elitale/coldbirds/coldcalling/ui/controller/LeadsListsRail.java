package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.services.CallListService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Left rail of the Leads workbench: "All Leads" plus one row per lead list with a live
 * count badge. Clicking a row scopes the grid to that list; the context menu renames or
 * deletes it; the footer button creates a new list.
 *
 * <p>View helper (not unit-tested) — renders into a host {@link VBox} and loads counts
 * off the FX thread. The list↔lead logic it drives lives in {@link CallListService}.
 */
final class LeadsListsRail {

    private final VBox host;
    private final CallListService service;
    private final VBox listsBox = new VBox(2);

    private Consumer<Optional<CallListId>> onSelect = selection -> { };
    private Optional<CallListId> selected = Optional.empty();

    LeadsListsRail(VBox host, CallListService service) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        build();
    }

    /** Notified with the chosen list, or empty for "All Leads". */
    void setOnSelect(Consumer<Optional<CallListId>> callback) {
        this.onSelect = Objects.requireNonNull(callback, "callback must not be null");
    }

    Optional<CallListId> selectedList() {
        return selected;
    }

    void refresh() {
        CompletableFuture
                .supplyAsync(service::listsWithCounts)
                .thenAcceptAsync(this::render, Platform::runLater);
    }

    private void build() {
        host.getChildren().clear();
        host.setSpacing(8);
        host.setPadding(new Insets(16, 12, 16, 16));
        host.setPrefWidth(220);
        host.getStyleClass().add("lists-rail");

        Label header = new Label("LISTS");
        header.getStyleClass().add("caption");

        Button newBtn = new Button("+  New list");
        newBtn.getStyleClass().add("flat");
        newBtn.setMaxWidth(Double.MAX_VALUE);
        newBtn.setOnAction(e -> createList());

        host.getChildren().addAll(header, listsBox, newBtn);
        refresh();
    }

    private void render(List<CallListService.ListSummary> lists) {
        listsBox.getChildren().clear();
        listsBox.getChildren().add(railRow("All Leads", -1, Optional.empty()));
        for (CallListService.ListSummary summary : lists) {
            listsBox.getChildren().add(railRow(
                    summary.list().name(), summary.leadCount(),
                    Optional.of(summary.list().id())));
        }
        highlightSelection();
    }

    private HBox railRow(String name, int count, Optional<CallListId> listId) {
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("lists-rail-name");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        HBox row = new HBox(8, nameLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.getStyleClass().add("lists-rail-row");
        row.setUserData(listId.map(CallListId::value).orElse(-1L));

        if (count >= 0) {
            Label badge = new Label(Integer.toString(count));
            badge.getStyleClass().add("lists-rail-badge");
            row.getChildren().add(badge);
        }

        row.setOnMouseClicked(e -> select(listId));
        listId.ifPresent(id -> row.setOnContextMenuRequested(e ->
                listContextMenu(id, name).show(row, e.getScreenX(), e.getScreenY())));
        return row;
    }

    private ContextMenu listContextMenu(CallListId id, String currentName) {
        MenuItem rename = new MenuItem("Rename…");
        rename.setOnAction(e -> renameList(id, currentName));
        MenuItem delete = new MenuItem("Delete list");
        delete.setOnAction(e -> deleteList(id));
        return new ContextMenu(rename, delete);
    }

    private void select(Optional<CallListId> listId) {
        this.selected = listId;
        highlightSelection();
        onSelect.accept(listId);
    }

    private void highlightSelection() {
        long target = selected.map(CallListId::value).orElse(-1L);
        for (var node : listsBox.getChildren()) {
            boolean active = node.getUserData() instanceof Long value && value == target;
            node.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), active);
        }
    }

    private void createList() {
        promptForName("New List", "List name:", "").ifPresent(name ->
                CompletableFuture.supplyAsync(() -> service.create(name))
                        .thenAcceptAsync(this::afterMutation, Platform::runLater));
    }

    private void renameList(CallListId id, String currentName) {
        promptForName("Rename List", "New name:", currentName).ifPresent(name ->
                CompletableFuture.supplyAsync(() -> service.rename(id, name))
                        .thenAcceptAsync(this::afterMutation, Platform::runLater));
    }

    private void deleteList(CallListId id) {
        CompletableFuture.supplyAsync(() -> service.delete(id))
                .thenAcceptAsync(result -> {
                    if (selected.map(s -> s.equals(id)).orElse(false)) {
                        select(Optional.empty());
                    }
                    refresh();
                }, Platform::runLater);
    }

    private void afterMutation(Result<?> result) {
        refresh();
    }

    private Optional<String> promptForName(String title, String prompt, String seed) {
        TextInputDialog dialog = new TextInputDialog(seed);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(prompt);
        return dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
    }
}
