package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.services.imports.ColumnAutoDetector;
import com.elitale.coldbirds.coldcalling.services.imports.ColumnMapping;
import com.elitale.coldbirds.coldcalling.services.imports.ImportPreview;
import com.elitale.coldbirds.coldcalling.services.imports.ImportResult;
import com.elitale.coldbirds.coldcalling.services.imports.LeadField;
import com.elitale.coldbirds.coldcalling.services.imports.PreviewRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ImportWizardModelTest {

    private static ColumnMapping detected() {
        return ColumnAutoDetector.detect(List.of("First Name", "Mobile Phone", "Company"), List.of());
    }

    @Test
    void startsAtDropWithNoFile() {
        ImportWizardModel m = new ImportWizardModel();
        assertThat(m.step()).isEqualTo(ImportWizardModel.Step.DROP);
        assertThat(m.hasFile()).isFalse();
        assertThat(m.canAdvance()).isFalse();
        assertThat(m.canGoBack()).isFalse();
    }

    @Test
    void loadingFileMovesToMapAndEnablesAdvanceWhenPrimaryPicked() {
        ImportWizardModel m = new ImportWizardModel();
        m.loadFile(detected());

        assertThat(m.step()).isEqualTo(ImportWizardModel.Step.MAP);
        assertThat(m.canGoBack()).isTrue();
        // auto-detect already chose Mobile Phone as primary
        assertThat(m.canAdvance()).isTrue();
    }

    @Test
    void mapStepBlocksAdvanceWithoutAPrimaryPhone() {
        ImportWizardModel m = new ImportWizardModel();
        m.loadFile(new ColumnMapping(
                List.of("Name", "Notes"),
                Map.of("Name", LeadField.FIRST_NAME, "Notes", LeadField.CUSTOM),
                Optional.empty()));

        assertThat(m.canAdvance()).isFalse();
    }

    @Test
    void setPrimaryPhoneEnablesAdvance() {
        ImportWizardModel m = new ImportWizardModel();
        m.loadFile(new ColumnMapping(
                List.of("Phone"),
                Map.of("Phone", LeadField.PHONE),
                Optional.empty()));
        assertThat(m.canAdvance()).isFalse();

        m.setPrimaryPhone("Phone");
        assertThat(m.canAdvance()).isTrue();
    }

    @Test
    void backNavigatesReviewToMapToDropThenStops() {
        ImportWizardModel m = new ImportWizardModel();
        m.loadFile(detected());
        m.setPreview(ImportPreview.of(0, List.of()));
        assertThat(m.step()).isEqualTo(ImportWizardModel.Step.REVIEW);

        m.back();
        assertThat(m.step()).isEqualTo(ImportWizardModel.Step.MAP);
        m.back();
        assertThat(m.step()).isEqualTo(ImportWizardModel.Step.DROP);
        m.back();
        assertThat(m.step()).isEqualTo(ImportWizardModel.Step.DROP);
    }

    @Test
    void reviewBlocksAdvanceWhenNoDialableRows() {
        ImportWizardModel m = new ImportWizardModel();
        m.loadFile(detected());
        m.setPreview(ImportPreview.of(0, List.of()));

        assertThat(m.canAdvance()).isFalse();
    }

    @Test
    void summaryIsTerminal() {
        ImportWizardModel m = new ImportWizardModel();
        m.loadFile(detected());
        m.setResult(new ImportResult("b1", 1, 1, 0, 0, 0, 0, 0, 0, List.of()));

        assertThat(m.step()).isEqualTo(ImportWizardModel.Step.SUMMARY);
        assertThat(m.canAdvance()).isFalse();
        assertThat(m.canGoBack()).isFalse();
    }

    @Test
    void targetListAndCountryRoundTrip() {
        ImportWizardModel m = new ImportWizardModel();
        m.setDefaultCountry(Optional.of("US"));
        m.setTargetList(Optional.of(new CallListId(3)));

        assertThat(m.defaultCountry()).contains("US");
        assertThat(m.targetList()).contains(new CallListId(3));
    }

    @Test
    void editingFieldsBeforeFileLoadedThrows() {
        ImportWizardModel m = new ImportWizardModel();
        assertThatThrownBy(() -> m.setField("X", LeadField.IGNORE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reviewWithDialableRowAllowsAdvance() {
        ImportWizardModel m = new ImportWizardModel();
        m.loadFile(detected());
        PreviewRow valid = new PreviewRow(
                2, List.of("Ann", "+14155551234", "Acme"),
                com.elitale.coldbirds.coldcalling.services.imports.ImportRowStatus.VALID,
                Optional.empty(),
                Optional.of(new com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber("+14155551234")),
                false, Optional.of("Ann"), Optional.empty(),
                Optional.of("Acme"), Optional.empty(), Optional.empty(), Map.of());
        m.setPreview(ImportPreview.of(1, List.of(valid)));

        assertThat(m.canAdvance()).isTrue();
    }
}
