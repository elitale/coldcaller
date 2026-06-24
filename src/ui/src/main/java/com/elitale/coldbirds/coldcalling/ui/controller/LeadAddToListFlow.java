package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.services.CallListService;
import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates "add selected leads to a list": load lists off-thread, show
 * {@link AddToListDialog}, then create (if a new name) and attach — all off the FX thread.
 * Keeps the controller thin.
 */
final class LeadAddToListFlow {

    private LeadAddToListFlow() {
    }

    static void run(CallListService listService, List<LeadId> leadIds, Runnable onDone) {
        if (leadIds.isEmpty()) {
            return;
        }
        CompletableFuture
                .supplyAsync(listService::listsWithCounts)
                .thenAcceptAsync(lists -> AddToListDialog.show(lists)
                        .ifPresent(choice -> attach(listService, choice, leadIds, onDone)),
                        Platform::runLater);
    }

    private static void attach(CallListService service, AddToListDialog.Choice choice,
                               List<LeadId> ids, Runnable onDone) {
        CompletableFuture
                .runAsync(() -> {
                    CallListId target = resolve(service, choice);
                    if (target != null) {
                        service.addLeads(target, ids);
                    }
                })
                .thenRunAsync(onDone, Platform::runLater);
    }

    private static CallListId resolve(CallListService service, AddToListDialog.Choice choice) {
        return switch (choice) {
            case AddToListDialog.Choice.Existing existing -> existing.id();
            case AddToListDialog.Choice.New created -> {
                Result<CallList> result = service.create(created.name());
                yield result instanceof Result.Ok<CallList> ok ? ok.value().id() : null;
            }
        };
    }
}
