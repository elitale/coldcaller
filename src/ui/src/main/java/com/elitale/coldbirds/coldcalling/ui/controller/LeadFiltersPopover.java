package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.LeadFilter;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.ui.support.LeadFilterState;
import com.elitale.coldbirds.coldcalling.ui.support.LeadStatusLabel;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.util.StringConverter;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lightweight filter popover for the Leads screen: status multiselect, tag
 * multiselect, DNC tri-state, and a single custom-field "contains" pair.
 *
 * <p>Binds to a {@link LeadFilterState}. On <b>Apply</b> it commits the controls into
 * the state and fires {@code onApply}; on <b>Clear</b> it resets the facets. The
 * controller rebuilds its query from the state. This is a view helper, so it is not
 * unit-tested — the testable filter logic lives in {@link LeadFilterState}.
 */
final class LeadFiltersPopover {

    private final LeadFilterState state;
    private final Popup popup = new Popup();

    private final VBox statusBox = new VBox(4);
    private final FlowPane tagPane = new FlowPane(8, 6);
    private final ComboBox<LeadFilter.DncFilter> dncCombo = new ComboBox<>();
    private final ComboBox<String> customKeyCombo = new ComboBox<>();
    private final TextField customValueField = new TextField();
    private final Map<LeadStatus, CheckBox> statusChecks = new EnumMap<>(LeadStatus.class);

    private Runnable onApply = () -> {
    };

    LeadFiltersPopover(LeadFilterState state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        popup.setAutoHide(true);
        popup.getContent().add(buildContent());
    }

    void setOnApply(Runnable callback) {
        this.onApply = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** Toggle the popover anchored below {@code anchor}. */
    void toggle(Node anchor) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        final Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 4);
    }

    /** Populate the tag checkboxes (async source); preserves any current selection. */
    void setTags(List<String> tags) {
        tagPane.getChildren().clear();
        if (tags.isEmpty()) {
            final Label empty = new Label("No tags yet");
            empty.getStyleClass().add("caption");
            tagPane.getChildren().add(empty);
            return;
        }
        for (String tag : tags) {
            final CheckBox cb = new CheckBox(tag);
            cb.setSelected(state.tags().contains(tag));
            cb.setUserData(tag);
            tagPane.getChildren().add(cb);
        }
    }

    /** Populate the custom-field key choices (async source). */
    void setCustomFieldKeys(List<String> keys) {
        final String current = customKeyCombo.getValue();
        customKeyCombo.getItems().setAll(keys);
        if (current != null && keys.contains(current)) {
            customKeyCombo.setValue(current);
        }
    }

    private Region buildContent() {
        final VBox root = new VBox(12);
        root.getStyleClass().add("bg-elevated");
        root.setPadding(new Insets(16));
        root.setPrefWidth(300);

        for (LeadStatus status : LeadStatus.values()) {
            final CheckBox cb = new CheckBox(LeadStatusLabel.of(status));
            cb.setSelected(state.statuses().contains(status));
            statusChecks.put(status, cb);
            statusBox.getChildren().add(cb);
        }

        dncCombo.getItems().setAll(LeadFilter.DncFilter.values());
        dncCombo.setValue(state.dnc());
        dncCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(LeadFilter.DncFilter value) {
                if (value == null) {
                    return "Any";
                }
                return switch (value) {
                    case ANY -> "Any";
                    case ONLY -> "Only DNC";
                    case EXCLUDE -> "Exclude DNC";
                };
            }

            @Override
            public LeadFilter.DncFilter fromString(String text) {
                return LeadFilter.DncFilter.ANY;
            }
        });
        dncCombo.setMaxWidth(Double.MAX_VALUE);

        customKeyCombo.setPromptText("Field");
        if (!state.customFieldKey().isBlank()) {
            customKeyCombo.setValue(state.customFieldKey());
        }
        customValueField.setPromptText("contains…");
        customValueField.setText(state.customFieldValue());
        final HBox customRow = new HBox(8, customKeyCombo, customValueField);
        HBox.setHgrow(customValueField, Priority.ALWAYS);

        final Button apply = new Button("Apply");
        apply.getStyleClass().add("accent");
        apply.setOnAction(e -> {
            commit();
            popup.hide();
            onApply.run();
        });
        final Button clear = new Button("Clear");
        clear.getStyleClass().add("flat");
        clear.setOnAction(e -> {
            clearControls();
            popup.hide();
            onApply.run();
        });
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final HBox actions = new HBox(8, clear, spacer, apply);

        root.getChildren().addAll(
                sectionLabel("Status"), statusBox,
                sectionLabel("Tags"), tagPane,
                sectionLabel("Do-Not-Call"), dncCombo,
                sectionLabel("Custom field"), customRow,
                new Separator(), actions);
        return root;
    }

    private void commit() {
        final Set<LeadStatus> selected = EnumSet.noneOf(LeadStatus.class);
        statusChecks.forEach((status, cb) -> {
            if (cb.isSelected()) {
                selected.add(status);
            }
        });
        state.statuses().clear();
        state.statuses().addAll(selected);

        state.tags().clear();
        for (Node node : tagPane.getChildren()) {
            if (node instanceof CheckBox cb && cb.isSelected()
                    && cb.getUserData() instanceof String tag) {
                state.tags().add(tag);
            }
        }

        state.setDnc(dncCombo.getValue() == null ? LeadFilter.DncFilter.ANY : dncCombo.getValue());
        state.setCustomField(customKeyCombo.getValue(), customValueField.getText());
    }

    private void clearControls() {
        statusChecks.values().forEach(cb -> cb.setSelected(false));
        tagPane.getChildren().forEach(node -> {
            if (node instanceof CheckBox cb) {
                cb.setSelected(false);
            }
        });
        dncCombo.setValue(LeadFilter.DncFilter.ANY);
        customKeyCombo.setValue(null);
        customValueField.clear();
        state.clearFacets();
    }

    private static Label sectionLabel(String text) {
        final Label label = new Label(text);
        label.getStyleClass().add("caption");
        return label;
    }
}
