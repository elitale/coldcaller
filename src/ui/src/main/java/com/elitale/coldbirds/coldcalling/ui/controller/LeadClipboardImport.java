package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.ui.support.ClipboardRowParser;
import javafx.application.Platform;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Bulk-creates leads from clipboard text pasted onto the grid. Cells map left-to-right:
 * {@code phone, first, last, company}. Rows with an unparseable phone are skipped. Runs
 * off the FX thread and re-enters via {@code onDone}.
 */
final class LeadClipboardImport {

    private LeadClipboardImport() {
    }

    static void paste(String clipboard, LeadService leadService,
                      Function<String, Optional<PhoneNumber>> phoneParser, Runnable onDone) {
        List<List<String>> rows = ClipboardRowParser.parse(clipboard);
        if (rows.isEmpty()) {
            return;
        }
        CompletableFuture
                .runAsync(() -> {
                    for (List<String> cells : rows) {
                        if (cells.isEmpty()) {
                            continue;
                        }
                        phoneParser.apply(cells.get(0)).ifPresent(phone ->
                                leadService.save(new LeadService.NewLead(
                                        cell(cells, 1), cell(cells, 2), phone, cell(cells, 3),
                                        Optional.empty(), Optional.empty(), List.of(), Optional.empty())));
                    }
                })
                .thenRunAsync(onDone, Platform::runLater);
    }

    private static Optional<String> cell(List<String> cells, int index) {
        if (index >= cells.size() || cells.get(index).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(cells.get(index).strip());
    }
}
