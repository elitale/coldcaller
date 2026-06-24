package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.services.CallListService;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.services.PhoneNormalizer;
import com.elitale.coldbirds.coldcalling.ui.support.QuickAddModel;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Mid-call quick-add ("call my colleague"): a tiny overlay that captures a phone (required,
 * live-normalized) + name (optional) and drops the lead — optionally into the list being
 * dialed — without leaving the call or touching the power-dialer position. Not unit-tested
 * (JavaFX view); the validation/draft logic lives in {@link QuickAddModel}.
 */
public final class QuickAddPopover {

    /** The list currently being dialed, if any — pre-checked as the attach target. */
    public record CurrentList(CallListId id, String name) { }

    private final LeadService leadService;
    private final CallListService callListService;
    private final QuickAddModel model;

    public QuickAddPopover(PhoneNormalizer normalizer, LeadService leadService, CallListService callListService) {
        this.leadService = Objects.requireNonNull(leadService, "leadService");
        this.callListService = Objects.requireNonNull(callListService, "callListService");
        this.model = new QuickAddModel(Objects.requireNonNull(normalizer, "normalizer"));
    }

    public void show(Window owner, Optional<String> region, Optional<CurrentList> currentList, Runnable onAdded) {
        Stage stage = new Stage();

        TextField phone = new TextField();
        phone.setPromptText("Phone (e.g. 415-555-0199)");
        TextField name = new TextField();
        name.setPromptText("Name (optional)");

        CheckBox attach = new CheckBox(currentList
                .map(c -> "Add to \u201C" + c.name() + "\u201D")
                .orElse("Add to current list"));
        attach.setSelected(true);
        attach.setVisible(currentList.isPresent());
        attach.setManaged(currentList.isPresent());

        Button add = new Button("Add");
        add.getStyleClass().add("accent");
        add.setDisable(true);

        Label hint = new Label();
        hint.getStyleClass().add("caption");

        Runnable revalidate = () -> {
            model.setPhone(phone.getText());
            model.setName(name.getText());
            model.setAddToCurrentList(attach.isSelected());
            boolean ok = model.canSubmit(region);
            add.setDisable(!ok);
            hint.setText(ok || phone.getText().isBlank() ? "" : "Enter a full or local phone number");
        };
        phone.textProperty().addListener((o, a, b) -> revalidate.run());
        name.textProperty().addListener((o, a, b) -> revalidate.run());
        attach.selectedProperty().addListener((o, a, b) -> revalidate.run());

        Runnable commit = () -> {
            if (model.canSubmit(region)) {
                submit(region, currentList, stage, onAdded, add);
            }
        };
        add.setOnAction(e -> commit.run());
        phone.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) commit.run(); });
        name.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) commit.run(); });

        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, hint, spacer, cancel, add);

        VBox root = new VBox(10, new Label("Quick add"), phone, name, attach, buttons);
        root.setPadding(new Insets(16));
        Scene scene = new Scene(root, 360, 220);
        owner.getScene().getStylesheets().forEach(scene.getStylesheets()::add);
        stage.setScene(scene);
        stage.setTitle("Quick Add");
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        Platform.runLater(phone::requestFocus);
        stage.show();
    }

    private void submit(Optional<String> region, Optional<CurrentList> currentList,
                        Stage stage, Runnable onAdded, Button add) {
        add.setDisable(true);
        final LeadService.NewLead draft = model.draft(region).orElseThrow();
        final boolean attach = model.addToCurrentList() && currentList.isPresent();
        CompletableFuture
                .supplyAsync(() -> {
                    Result<Lead> saved = leadService.save(draft);
                    if (attach && saved instanceof Result.Ok<Lead> ok) {
                        callListService.addLeads(currentList.get().id(), List.of(ok.value().id()));
                    }
                    return saved;
                })
                .thenAcceptAsync(saved -> {
                    stage.close();
                    onAdded.run();
                }, Platform::runLater);
    }
}
