package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.services.CallListService;
import com.elitale.coldbirds.coldcalling.services.LeadImportService;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.services.SettingsService;
import com.elitale.coldbirds.coldcalling.ui.support.LeadFilterState;
import com.elitale.coldbirds.coldcalling.ui.support.LeadPhoneParser;
import com.elitale.coldbirds.coldcalling.ui.support.LeadSelectionModel;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Controller for the Leads workbench (leads-view.fxml).
 *
 * <p>Wires the lists rail ({@link LeadsListsRail}), faceted search/filter
 * ({@link LeadFiltersPopover}), keyset infinite-scroll paging ({@link LeadsPageLoader}),
 * bulk select/actions ({@link LeadBulkBar} + {@link LeadSelectionModel}), inline add-row
 * ({@link LeadQuickAddBar}), clipboard paste, and editable custom-field columns whose
 * layout persists via {@link SettingsService}.
 *
 * <p>Threading: all methods run on the FX Application Thread except where a
 * {@link CompletableFuture} dispatches work off-thread and re-enters via
 * {@link Platform#runLater}.
 */
public final class LeadsController {

    private static final Duration SEARCH_DEBOUNCE = Duration.millis(250);

    // ── FXML-injected fields ──────────────────────────────────────────────────

    @FXML private TextField                    searchField;
    @FXML private Button                       filtersBtn;
    @FXML private Button                       addColumnBtn;
    @FXML private Label                        countLabel;
    @FXML private VBox                         railHost;
    @FXML private VBox                         topExtras;
    @FXML private TableView<Lead>              table;
    @FXML private TableColumn<Lead, Boolean>   selectCol;
    @FXML private TableColumn<Lead, String>    nameCol;
    @FXML private TableColumn<Lead, String>    phoneCol;
    @FXML private TableColumn<Lead, String>    companyCol;
    @FXML private TableColumn<Lead, String>    statusCol;
    @FXML private TableColumn<Lead, String>    tagsCol;
    @FXML private TableColumn<Lead, String>    dncCol;
    @FXML private Button                       callBtn;
    @FXML private Button                       deleteBtn;

    // ── State ─────────────────────────────────────────────────────────────────

    private LeadService      leadService;
    private CallListService  callListService;
    private LeadImportService importService;
    private SettingsService  settingsService;
    private Consumer<String> onDial = ignored -> {};

    private final ObservableList<Lead> leads = FXCollections.observableArrayList();
    private final LeadFilterState filterState = new LeadFilterState();
    private final LeadSelectionModel selection = new LeadSelectionModel();
    private final PauseTransition searchDebounce = new PauseTransition(SEARCH_DEBOUNCE);
    private LeadFiltersPopover filtersPopover;
    private LeadColumnManager columnManager;
    private LeadsPageLoader pageLoader;
    private LeadsListsRail rail;
    private LeadBulkBar bulkBar;
    private LeadQuickAddBar quickAddBar;

    /** Default no-arg constructor — required by FXMLLoader. */
    public LeadsController() {}

    // ── Configuration (called before FXMLLoader.load()) ───────────────────────

    public void setLeadService(LeadService service) {
        this.leadService = Objects.requireNonNull(service, "service must not be null");
    }

    public void setCallListService(CallListService service) {
        this.callListService = Objects.requireNonNull(service, "service must not be null");
    }

    public void setLeadImportService(LeadImportService service) {
        this.importService = Objects.requireNonNull(service, "service must not be null");
    }

    public void setSettingsService(SettingsService service) {
        this.settingsService = Objects.requireNonNull(service, "service must not be null");
    }

    /** Register a callback invoked with the lead's E.164 number when the user dials. */
    public void setOnDial(Consumer<String> callback) {
        this.onDial = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Reload the grid + facets — used after an external add (e.g. mid-call quick-add). */
    public void refresh() {
        afterDataChanged();
    }

    // ── FXMLLoader lifecycle ──────────────────────────────────────────────────

    @FXML
    private void initialize() {
        LeadsTableColumns.configureStatic(nameCol, phoneCol, companyCol, statusCol, tagsCol, dncCol);
        LeadsTableColumns.configureSelect(selectCol, selection, this::onSelectionChanged);
        table.setEditable(true);

        final Label placeholder = new Label("No leads yet. Use the quick-add bar above to get started.");
        placeholder.getStyleClass().add("caption");
        table.setPlaceholder(placeholder);
        table.setItems(leads);

        callBtn.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        deleteBtn.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());

        LeadsTableInteractions.install(
                table,
                lead -> onDial.accept(lead.phone().value()),
                this::pasteClipboard,
                idx -> { if (pageLoader != null) { pageLoader.onRowRealized(idx, leads.size()); } });

        filtersPopover = new LeadFiltersPopover(filterState);
        filtersPopover.setOnApply(() -> { updateFiltersButton(); applyFilter(); });

        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            searchDebounce.setOnFinished(e -> { filterState.setSearch(newVal.strip()); applyFilter(); });
            searchDebounce.playFromStart();
        });

        if (leadService == null) {
            return;
        }
        quickAddBar = new LeadQuickAddBar(leadService, LeadPhoneParser::parse);
        quickAddBar.setOnAdded(this::afterDataChanged);
        bulkBar = new LeadBulkBar(leadService, selection);
        bulkBar.setOnChanged(this::afterBulkChanged);
        bulkBar.setOnAddToList(this::onAddSelectedToList);
        topExtras.getChildren().addAll(quickAddBar.node(), bulkBar.node());

        if (callListService != null) {
            rail = new LeadsListsRail(railHost, callListService);
            rail.setOnSelect(this::onListSelected);
        }
        columnManager = new LeadColumnManager(
                leadService, settingsService, table, filtersPopover, this::applyFilter);

        pageLoader = new LeadsPageLoader(
                leadService,
                leads::addAll,
                () -> { leads.clear(); countLabel.setText(""); },
                () -> { updateCountLabel(); updatePlaceholder(); },
                () -> ((Label) table.getPlaceholder()).setText("Failed to load leads."));
        applyFilter();
        columnManager.refresh();
    }

    // ── FXML event handlers ───────────────────────────────────────────────────

    @FXML
    private void onFilters() {
        filtersPopover.toggle(filtersBtn);
    }

    @FXML
    private void onCall() {
        final Lead selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) {
            onDial.accept(selected.phone().value());
        }
    }

    @FXML
    private void onAdd() {
        if (leadService == null) {
            return;
        }
        AddLeadDialog.show().ifPresent(newLead -> CompletableFuture
                .runAsync(() -> leadService.save(newLead))
                .thenRunAsync(this::afterDataChanged, Platform::runLater));
    }

    @FXML
    private void onAddColumn() {
        if (columnManager != null) {
            columnManager.promptAndAddColumn();
        }
    }

    @FXML
    private void onImport() {
        if (importService == null || callListService == null) {
            return;
        }
        LeadImportFlow.run(
                table.getScene().getWindow(), importService, callListService,
                filterState.listId(), result -> afterDataChanged());
    }

    @FXML
    private void onDelete() {
        final Lead selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        final Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Lead");
        confirm.setHeaderText("Delete " + selected.displayName() + "?");
        confirm.setContentText("This lead will be soft-deleted and hidden from all lists.");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                CompletableFuture
                        .runAsync(() -> leadService.delete(selected.id()))
                        .thenRunAsync(this::afterDataChanged, Platform::runLater);
            }
        });
    }

    // ── Rail / selection / bulk ────────────────────────────────────────────────

    private void onListSelected(Optional<CallListId> listId) {
        filterState.setListId(listId);
        applyFilter();
    }

    private void onSelectionChanged() {
        bulkBar.syncVisibility();
    }

    private void afterBulkChanged() {
        applyFilter();
        if (rail != null) {
            rail.refresh();
        }
    }

    private void onAddSelectedToList() {
        if (callListService == null) {
            return;
        }
        LeadAddToListFlow.run(callListService, selection.selectedIds(), () -> {
            selection.clear();
            afterBulkChanged();
        });
    }

    private void pasteClipboard() {
        if (leadService == null) {
            return;
        }
        final String clip = Clipboard.getSystemClipboard().getString();
        LeadClipboardImport.paste(clip, leadService, LeadPhoneParser::parse, this::afterDataChanged);
    }

    private void afterDataChanged() {
        applyFilter();
        if (columnManager != null) {
            columnManager.refresh();
        }
        if (rail != null) {
            rail.refresh();
        }
    }

    // ── Paging + facet sources ────────────────────────────────────────────────

    private void applyFilter() {
        selection.clear();
        if (pageLoader != null) {
            pageLoader.apply(filterState.toFilter());
        }
        if (bulkBar != null) {
            bulkBar.syncVisibility();
        }
    }

    private void updateFiltersButton() {
        filtersBtn.setText(filterState.hasActiveFacets() ? "Filters \u2022" : "Filters");
    }

    private void updateCountLabel() {
        final int total = pageLoader.total();
        countLabel.setText(total == 0 ? "" : "Showing " + leads.size() + " of " + total);
    }

    private void updatePlaceholder() {
        final boolean filtered = filterState.hasActiveFacets() || !filterState.search().isBlank()
                || filterState.listId().isPresent();
        ((Label) table.getPlaceholder()).setText(filtered
                ? "No leads match your filters."
                : "No leads yet. Use the quick-add bar above to get started.");
    }
}
