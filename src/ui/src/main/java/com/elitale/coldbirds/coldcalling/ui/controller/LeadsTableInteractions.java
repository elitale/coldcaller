package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Installs row-level interaction behaviour on the leads {@link TableView}:
 * double-click to dial, lazy keyset paging as rows are realized, and the
 * platform paste shortcut to bulk-add from the clipboard.
 *
 * <p>Pure JavaFX wiring (no business logic) — kept out of the controller to
 * respect the 250-line controller budget.
 */
final class LeadsTableInteractions {

    private LeadsTableInteractions() {}

    static void install(
            TableView<Lead> table,
            Consumer<Lead> onActivate,
            Runnable onPaste,
            IntConsumer onRowRealized) {
        table.setRowFactory(tv -> {
            final TableRow<Lead> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    onActivate.accept(row.getItem());
                }
            });
            row.indexProperty().addListener((obs, o, idx) -> onRowRealized.accept(idx.intValue()));
            return row;
        });
        table.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN).match(e)) {
                onPaste.run();
                e.consume();
            }
        });
    }
}
