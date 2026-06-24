package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.services.CallListService;
import com.elitale.coldbirds.coldcalling.services.LeadImportService;
import com.elitale.coldbirds.coldcalling.services.imports.CsvSource;
import com.elitale.coldbirds.coldcalling.services.imports.ImportResult;
import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Drop step of the CSV import: pick a file, parse it (commons-csv), and hand the parsed
 * headers/rows to {@link ImportWizardDialog}. A pure view helper — no business logic.
 */
final class LeadImportFlow {

    private LeadImportFlow() {
    }

    static void run(Window owner, LeadImportService importService, CallListService callListService,
                    Optional<CallListId> preselectList, Consumer<ImportResult> onComplete) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import leads from CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }

        final CsvSource.Parsed parsed;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            parsed = CsvSource.parse(reader);
        } catch (IOException e) {
            error("Could not read the file", e.getMessage());
            return;
        }

        if (parsed.headers().isEmpty() || parsed.rows().isEmpty()) {
            error("Nothing to import", "The file has no data rows.");
            return;
        }

        new ImportWizardDialog(importService, callListService,
                parsed.headers(), parsed.rows(), file.getName(), preselectList)
                .showAndWait(owner)
                .ifPresent(onComplete);
    }

    private static void error(String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Import");
        alert.setHeaderText(header);
        alert.setContentText(detail);
        alert.showAndWait();
    }
}
