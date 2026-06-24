package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.services.CallListService;
import com.elitale.coldbirds.coldcalling.services.LeadImportService;
import com.elitale.coldbirds.coldcalling.services.imports.ColumnAutoDetector;
import com.elitale.coldbirds.coldcalling.services.imports.ImportPreview;
import com.elitale.coldbirds.coldcalling.services.imports.ImportResult;
import com.elitale.coldbirds.coldcalling.services.imports.LeadField;
import com.elitale.coldbirds.coldcalling.services.imports.PreviewRow;
import com.elitale.coldbirds.coldcalling.ui.support.CountryCatalog;
import com.elitale.coldbirds.coldcalling.ui.support.ImportWizardModel;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Modal CSV import wizard (Map → Review → Summary). The Drop step is performed by
 * {@link LeadImportFlow} before this opens. Owns only presentation + thread hand-off:
 * navigation guards live in {@link ImportWizardModel}; parsing / classification / commit
 * live in {@link LeadImportService}. Not unit-tested (JavaFX view).
 */
final class ImportWizardDialog {

    private final LeadImportService importService;
    private final CallListService callListService;
    private final List<String> headers;
    private final List<List<String>> rows;
    private final String fileName;
    private final ImportWizardModel model = new ImportWizardModel();

    private final Stage stage = new Stage();
    private final VBox center = new VBox(12);
    private final HBox footer = new HBox(8);
    private final Label stepTitle = new Label();
    private Optional<ImportResult> outcome = Optional.empty();

    ImportWizardDialog(LeadImportService importService, CallListService callListService,
                       List<String> headers, List<List<String>> rows, String fileName,
                       Optional<CallListId> preselectList) {
        this.importService = Objects.requireNonNull(importService, "importService");
        this.callListService = Objects.requireNonNull(callListService, "callListService");
        this.headers = List.copyOf(headers);
        this.rows = List.copyOf(rows);
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        model.loadFile(ColumnAutoDetector.detect(this.headers, this.rows));
        model.setTargetList(Objects.requireNonNull(preselectList, "preselectList"));
    }

    /** Show modally; returns the commit result if the user imported, else empty. */
    Optional<ImportResult> showAndWait(Window owner) {
        stepTitle.getStyleClass().add("title-2");
        center.setPadding(new Insets(16));
        footer.setPadding(new Insets(12, 16, 16, 16));
        ScrollPane scroll = new ScrollPane(center);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        VBox root = new VBox(headerBar(), scroll, footer);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        render();
        Scene scene = new Scene(root, 640, 520);
        owner.getScene().getStylesheets().forEach(scene.getStylesheets()::add);
        stage.setScene(scene);
        stage.setTitle("Import CSV — " + fileName);
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.showAndWait();
        return outcome;
    }

    private VBox headerBar() {
        VBox box = new VBox(stepTitle);
        box.setPadding(new Insets(16, 16, 0, 16));
        return box;
    }

    private void render() {
        center.getChildren().clear();
        footer.getChildren().clear();
        switch (model.step()) {
            case DROP, MAP -> renderMap();
            case REVIEW -> renderReview();
            case SUMMARY -> renderSummary();
        }
    }

    // ── Map step ──────────────────────────────────────────────────────────────

    private void renderMap() {
        stepTitle.setText("Map columns");
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        ToggleGroup primaryGroup = new ToggleGroup();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            grid.add(new Label(header), 0, i);
            grid.add(fieldCombo(header, primaryGroup, grid), 1, i);
            grid.add(primaryRadio(header, primaryGroup), 2, i);
        }
        center.getChildren().addAll(grid, new Label("Default country for local numbers"),
                countryCombo(), new Label("Add imported leads to list (optional)"), listCombo());
        footer.getChildren().addAll(spacer(), cancelButton(), nextButton());
    }

    private ComboBox<LeadField> fieldCombo(String header, ToggleGroup group, GridPane grid) {
        ComboBox<LeadField> combo = new ComboBox<>();
        combo.getItems().setAll(LeadField.values());
        combo.setValue(model.mapping().orElseThrow().fieldOf(header));
        combo.valueProperty().addListener((obs, o, n) -> {
            if (n != null) {
                model.setField(header, n);
                render();
            }
        });
        return combo;
    }

    private RadioButton primaryRadio(String header, ToggleGroup group) {
        RadioButton radio = new RadioButton("primary");
        radio.setToggleGroup(group);
        boolean isPhone = model.mapping().orElseThrow().fieldOf(header) == LeadField.PHONE;
        radio.setDisable(!isPhone);
        radio.setSelected(model.mapping().orElseThrow().isPrimaryPhone(header));
        radio.setOnAction(e -> model.setPrimaryPhone(header));
        return radio;
    }

    private ComboBox<Country> countryCombo() {
        ComboBox<Country> combo = new ComboBox<>();
        combo.getItems().add(null);
        combo.getItems().addAll(CountryCatalog.ALL);
        combo.setConverter(new StringConverter<>() {
            @Override public String toString(Country c) {
                return c == null ? "None — require full + numbers" : c.displayName() + " (" + c.dialCode() + ")";
            }
            @Override public Country fromString(String s) { return null; }
        });
        model.defaultCountry().ifPresent(iso -> CountryCatalog.ALL.stream()
                .filter(c -> c.isoCode().equals(iso)).findFirst().ifPresent(combo::setValue));
        combo.valueProperty().addListener((obs, o, n) ->
                model.setDefaultCountry(n == null ? Optional.empty() : Optional.of(n.isoCode())));
        return combo;
    }

    private ComboBox<CallListService.ListSummary> listCombo() {
        ComboBox<CallListService.ListSummary> combo = new ComboBox<>();
        combo.getItems().add(null);
        combo.getItems().addAll(callListService.listsWithCounts());
        combo.setConverter(new StringConverter<>() {
            @Override public String toString(CallListService.ListSummary s) {
                return s == null ? "None" : s.list().name() + "  (" + s.leadCount() + ")";
            }
            @Override public CallListService.ListSummary fromString(String s) { return null; }
        });
        model.targetList().ifPresent(id -> callListService.listsWithCounts().stream()
                .filter(s -> s.list().id().equals(id)).findFirst().ifPresent(combo::setValue));
        combo.valueProperty().addListener((obs, o, n) ->
                model.setTargetList(n == null ? Optional.empty() : Optional.of(n.list().id())));
        return combo;
    }

    // ── Review step ─────────────────────────────────────────────────────────────

    private void renderReview() {
        stepTitle.setText("Review");
        ImportPreview preview = model.preview().orElseThrow();
        Label tally = new Label(String.format(
                "%d ready · %d need review · %d duplicate · %d on DNC · %d empty (of %d rows)",
                preview.validCount(), preview.needsReviewCount(), preview.duplicateCount(),
                preview.dncCount(), preview.emptyCount(), preview.totalRows()));
        ListView<String> tray = new ListView<>();
        preview.rows().stream()
                .filter(r -> r.status() != com.elitale.coldbirds.coldcalling.services.imports.ImportRowStatus.VALID)
                .map(this::describe)
                .forEach(tray.getItems()::add);
        VBox.setVgrow(tray, Priority.ALWAYS);
        if (tray.getItems().isEmpty()) {
            tray.setPlaceholder(new Label("Every row is ready to import."));
        }
        center.getChildren().addAll(tally, tray);
        footer.getChildren().addAll(backButton(), spacer(), cancelButton(), importButton());
    }

    private String describe(PreviewRow r) {
        return "Line " + r.sourceLine() + " — " + r.status() + ": " + r.reason().orElse("");
    }

    // ── Summary step ────────────────────────────────────────────────────────────

    private void renderSummary() {
        stepTitle.setText("Done");
        ImportResult result = model.result().orElseThrow();
        Label summary = new Label(String.format(
                "Created %d · Updated %d · Skipped %d duplicate, %d invalid, %d DNC, %d empty · %d errors",
                result.created(), result.updated(), result.skippedDuplicate(), result.skippedInvalid(),
                result.skippedDnc(), result.skippedEmpty(), result.errors()));
        Label reconcile = new Label(result.totalRows() + " rows reconciled. "
                + "Updates to existing leads are not reverted by Undo.");
        reconcile.getStyleClass().add("caption");
        Button undo = new Button("Undo import");
        undo.setOnAction(e -> doUndo(result.batchId(), undo));
        center.getChildren().addAll(summary, reconcile, undo);
        footer.getChildren().addAll(spacer(), closeButton());
    }

    private void doUndo(String batchId, Button undo) {
        undo.setDisable(true);
        CompletableFuture.runAsync(() -> importService.undo(batchId))
                .thenRunAsync(() -> {
                    outcome = Optional.empty();
                    stage.close();
                }, Platform::runLater);
    }

    // ── Actions ─────────────────────────────────────────────────────────────────

    private Button nextButton() {
        Button next = new Button("Next");
        next.getStyleClass().add("accent");
        next.setDisable(!model.canAdvance());
        next.setOnAction(e -> runPreview(next));
        return next;
    }

    private void runPreview(Button trigger) {
        trigger.setDisable(true);
        CompletableFuture
                .supplyAsync(() -> importService.preview(rows, model.mapping().orElseThrow(),
                        model.defaultCountry()))
                .thenAcceptAsync(preview -> { model.setPreview(preview); render(); }, Platform::runLater);
    }

    private Button importButton() {
        Button go = new Button("Import");
        go.getStyleClass().add("accent");
        go.setDisable(!model.canAdvance());
        go.setOnAction(e -> runCommit(go));
        return go;
    }

    private void runCommit(Button trigger) {
        trigger.setDisable(true);
        ImportPreview preview = model.preview().orElseThrow();
        CompletableFuture
                .supplyAsync(() -> importService.commit(preview, fileName,
                        model.defaultCountry(), model.targetList()))
                .thenAcceptAsync(result -> {
                    model.setResult(result);
                    outcome = Optional.of(result);
                    render();
                }, Platform::runLater);
    }

    private Button backButton() {
        Button back = new Button("Back");
        back.setOnAction(e -> { model.back(); render(); });
        return back;
    }

    private Button cancelButton() {
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());
        return cancel;
    }

    private Button closeButton() {
        Button close = new Button("Close");
        close.getStyleClass().add("accent");
        close.setOnAction(e -> stage.close());
        return close;
    }

    private Region spacer() {
        Region s = new Region();
        HBox.setHgrow(s, Priority.ALWAYS);
        return s;
    }
}
