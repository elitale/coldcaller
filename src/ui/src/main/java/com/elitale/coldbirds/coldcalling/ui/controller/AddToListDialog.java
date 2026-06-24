package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.services.CallListService;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Optional;

/**
 * Modal that asks which list the selected leads should join: an existing one, or a new
 * one typed inline. Returns the user's {@link Choice}; the controller performs the
 * (off-thread) create/attach so this stays a pure view helper.
 */
final class AddToListDialog {

    /** Either an existing list or a new list name to create. */
    sealed interface Choice permits Choice.Existing, Choice.New {
        record Existing(CallListId id) implements Choice { }
        record New(String name) implements Choice { }
    }

    private AddToListDialog() {
    }

    static Optional<Choice> show(List<CallListService.ListSummary> existing) {
        Dialog<Choice> dialog = new Dialog<>();
        dialog.setTitle("Add to List");
        dialog.setHeaderText(null);

        ButtonType addType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);

        ComboBox<CallListService.ListSummary> combo = new ComboBox<>();
        combo.getItems().setAll(existing);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(CallListService.ListSummary s) {
                return s == null ? "" : s.list().name() + "  (" + s.leadCount() + ")";
            }

            @Override
            public CallListService.ListSummary fromString(String string) {
                return null;
            }
        });

        TextField newName = new TextField();
        newName.setPromptText("…or type a new list name");

        VBox content = new VBox(8,
                new Label("Existing list"), combo,
                new Label("New list"), newName);
        content.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(content);

        // Picking an existing list clears the new-name field and vice-versa.
        combo.valueProperty().addListener((obs, o, n) -> {
            if (n != null) newName.clear();
        });
        newName.textProperty().addListener((obs, o, n) -> {
            if (n != null && !n.isBlank()) combo.getSelectionModel().clearSelection();
        });

        dialog.setResultConverter(button -> {
            if (button != addType) {
                return null;
            }
            String typed = newName.getText() == null ? "" : newName.getText().strip();
            if (!typed.isEmpty()) {
                return new Choice.New(typed);
            }
            CallListService.ListSummary chosen = combo.getValue();
            return chosen == null ? null : new Choice.Existing(chosen.list().id());
        });

        return dialog.showAndWait();
    }
}
