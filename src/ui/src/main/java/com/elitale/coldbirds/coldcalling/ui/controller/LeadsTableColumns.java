package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.ui.support.LeadSelectionModel;
import com.elitale.coldbirds.coldcalling.ui.support.LeadStatusLabel;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;

import java.util.List;

/** Configures the static, select, and dynamic (custom-field) columns of the Leads table. */
final class LeadsTableColumns {

    /** Callback fired when a custom-field cell is edited inline. */
    @FunctionalInterface
    interface CellEdit {
        void apply(Lead lead, String key, String newValue);
    }

    private LeadsTableColumns() {
    }

    static void configureStatic(
            TableColumn<Lead, String> name,
            TableColumn<Lead, String> phone,
            TableColumn<Lead, String> company,
            TableColumn<Lead, String> status,
            TableColumn<Lead, String> tags,
            TableColumn<Lead, String> dnc) {
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().displayName()));
        phone.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().phone().value()));
        company.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().company().orElse("")));
        status.setCellValueFactory(c -> new SimpleStringProperty(LeadStatusLabel.of(c.getValue().leadStatus())));
        tags.setCellValueFactory(c -> new SimpleStringProperty(String.join(", ", c.getValue().tags())));
        dnc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dnc() ? "DNC" : ""));
        dnc.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setStyle("-fx-text-fill: #FF3B30; -fx-font-weight: bold;");
                }
            }
        });
    }

    /** A per-row checkbox column mirroring the bulk {@link LeadSelectionModel}. */
    static void configureSelect(
            TableColumn<Lead, Boolean> selectCol,
            LeadSelectionModel selection,
            Runnable onToggle) {
        selectCol.setSortable(false);
        selectCol.setReorderable(false);
        selectCol.setCellValueFactory(c -> null);
        selectCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox box = new CheckBox();
            {
                box.setOnAction(e -> {
                    Lead lead = currentLead();
                    if (lead != null) {
                        if (box.isSelected()) {
                            selection.select(lead.id());
                        } else {
                            selection.deselect(lead.id());
                        }
                        onToggle.run();
                    }
                });
            }

            private Lead currentLead() {
                return getTableRow() == null ? null : getTableRow().getItem();
            }

            @Override
            protected void updateItem(Boolean ignored, boolean empty) {
                super.updateItem(ignored, empty);
                Lead lead = currentLead();
                if (empty || lead == null) {
                    setGraphic(null);
                } else {
                    box.setSelected(selection.isSelected(lead.id()));
                    setGraphic(box);
                }
            }
        });
    }

    /**
     * Replace the dynamic custom-field columns with one editable column per key.
     * Editing a cell fires {@code onEdit}; the table must be {@code editable}.
     */
    static void rebuildCustom(
            TableView<Lead> table,
            List<TableColumn<Lead, String>> dynamicColumns,
            List<String> keys,
            CellEdit onEdit) {
        table.getColumns().removeAll(dynamicColumns);
        dynamicColumns.clear();
        for (String key : keys) {
            final TableColumn<Lead, String> col = new TableColumn<>(key);
            col.setPrefWidth(140);
            col.setEditable(true);
            col.setCellValueFactory(c ->
                    new SimpleStringProperty(c.getValue().customFields().getOrDefault(key, "")));
            col.setCellFactory(TextFieldTableCell.forTableColumn());
            col.setOnEditCommit(ev -> onEdit.apply(ev.getRowValue(), key, ev.getNewValue()));
            dynamicColumns.add(col);
        }
        table.getColumns().addAll(dynamicColumns);
    }
}
