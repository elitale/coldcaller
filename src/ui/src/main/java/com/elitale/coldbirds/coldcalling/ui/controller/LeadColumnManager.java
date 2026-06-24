package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.services.SettingsService;
import com.elitale.coldbirds.coldcalling.ui.support.LeadColumnPrefs;
import javafx.application.Platform;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Owns the leads grid's dynamic custom-field columns and tag facet source.
 *
 * <p>Persists the visible custom columns (order + membership) via {@link SettingsService}
 * so the layout survives restarts; merges data-discovered keys on each {@link #refresh()};
 * adds new columns inline; and commits in-cell edits through {@link LeadService} off the
 * FX thread. A view helper, not unit-tested — the prefs logic lives in {@link LeadColumnPrefs}.
 */
final class LeadColumnManager {

    private static final String PREF_KEY = "leads.columns";

    private final LeadService leadService;
    private final SettingsService settingsService;
    private final TableView<Lead> table;
    private final LeadFiltersPopover popover;
    private final Runnable onAfterCellEdit;
    private final List<TableColumn<Lead, String>> dynamicColumns = new ArrayList<>();
    private LeadColumnPrefs prefs;

    LeadColumnManager(LeadService leadService,
                      SettingsService settingsService,
                      TableView<Lead> table,
                      LeadFiltersPopover popover,
                      Runnable onAfterCellEdit) {
        this.leadService = Objects.requireNonNull(leadService, "leadService must not be null");
        this.settingsService = settingsService;
        this.table = Objects.requireNonNull(table, "table must not be null");
        this.popover = Objects.requireNonNull(popover, "popover must not be null");
        this.onAfterCellEdit = Objects.requireNonNull(onAfterCellEdit, "onAfterCellEdit must not be null");
        this.prefs = settingsService == null
                ? LeadColumnPrefs.empty()
                : LeadColumnPrefs.parse(settingsService.get(PREF_KEY, ""));
    }

    /** Reload facet sources: merge discovered custom keys, rebuild columns, refresh tag/key lists. */
    void refresh() {
        CompletableFuture
                .supplyAsync(() -> leadService.customFieldKeys(Optional.empty()))
                .thenAcceptAsync(keys -> {
                    prefs = prefs.mergeAll(keys);
                    persist();
                    rebuild();
                    popover.setCustomFieldKeys(prefs.columns());
                }, Platform::runLater);
        CompletableFuture
                .supplyAsync(leadService::distinctTags)
                .thenAcceptAsync(popover::setTags, Platform::runLater);
    }

    /** Prompt for a name and add a new (initially empty) custom-field column. */
    void promptAndAddColumn() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Add Column");
        dialog.setHeaderText(null);
        dialog.setContentText("Custom field name:");
        dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty()).ifPresent(key -> {
            prefs = prefs.withColumn(key);
            persist();
            rebuild();
        });
    }

    private void rebuild() {
        LeadsTableColumns.rebuildCustom(table, dynamicColumns, prefs.columns(), this::onCellEdit);
    }

    private void onCellEdit(Lead lead, String key, String value) {
        CompletableFuture
                .runAsync(() -> leadService.setCustomField(lead.id(), key, value))
                .thenRunAsync(onAfterCellEdit, Platform::runLater);
    }

    private void persist() {
        if (settingsService != null) {
            settingsService.set(PREF_KEY, prefs.serialize());
        }
    }
}
