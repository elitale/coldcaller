package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.services.imports.ColumnMapping;
import com.elitale.coldbirds.coldcalling.services.imports.ImportPreview;
import com.elitale.coldbirds.coldcalling.services.imports.ImportResult;
import com.elitale.coldbirds.coldcalling.services.imports.LeadField;

import java.util.Objects;
import java.util.Optional;

/**
 * Headless state machine for the CSV import wizard (Drop → Map → Review → Summary).
 *
 * <p>Holds the working column mapping, per-import default country, optional target list,
 * and the preview/result once produced. Owns the navigation guards so the JavaFX view
 * stays a thin renderer. Unit-tested; no JavaFX dependency.
 */
public final class ImportWizardModel {

    /** The four wizard stages, in order. */
    public enum Step { DROP, MAP, REVIEW, SUMMARY }

    private Step step = Step.DROP;
    private ColumnMapping mapping;
    private Optional<String> defaultCountry = Optional.empty();
    private Optional<CallListId> targetList = Optional.empty();
    private ImportPreview preview;
    private ImportResult result;

    public Step step() {
        return step;
    }

    public boolean hasFile() {
        return mapping != null;
    }

    public Optional<ColumnMapping> mapping() {
        return Optional.ofNullable(mapping);
    }

    public Optional<ImportPreview> preview() {
        return Optional.ofNullable(preview);
    }

    public Optional<ImportResult> result() {
        return Optional.ofNullable(result);
    }

    public Optional<String> defaultCountry() {
        return defaultCountry;
    }

    public Optional<CallListId> targetList() {
        return targetList;
    }

    /** Load a freshly parsed/auto-detected mapping; positions the wizard at the Map step. */
    public void loadFile(ColumnMapping detected) {
        this.mapping = Objects.requireNonNull(detected, "detected must not be null");
        this.preview = null;
        this.result = null;
        this.step = Step.MAP;
    }

    public void setDefaultCountry(Optional<String> country) {
        this.defaultCountry = Objects.requireNonNull(country, "country must not be null");
    }

    public void setTargetList(Optional<CallListId> listId) {
        this.targetList = Objects.requireNonNull(listId, "listId must not be null");
    }

    public void setField(String header, LeadField field) {
        requireMapping();
        this.mapping = mapping.withField(header, field);
    }

    public void setPrimaryPhone(String header) {
        requireMapping();
        this.mapping = mapping.withPrimaryPhone(header);
    }

    /** Record the preview and move to the Review step. */
    public void setPreview(ImportPreview produced) {
        this.preview = Objects.requireNonNull(produced, "produced must not be null");
        this.step = Step.REVIEW;
    }

    /** Record the commit result and move to the Summary step. */
    public void setResult(ImportResult produced) {
        this.result = Objects.requireNonNull(produced, "produced must not be null");
        this.step = Step.SUMMARY;
    }

    /** Whether the primary action ("Next" / "Import") is enabled for the current step. */
    public boolean canAdvance() {
        return switch (step) {
            case DROP -> hasFile();
            case MAP -> mapping != null
                    && !mapping.phoneHeaders().isEmpty()
                    && mapping.primaryPhoneHeader().isPresent();
            case REVIEW -> preview != null && !preview.dialableRows().isEmpty();
            case SUMMARY -> false;
        };
    }

    public boolean canGoBack() {
        return step == Step.MAP || step == Step.REVIEW;
    }

    public void back() {
        this.step = switch (step) {
            case REVIEW -> Step.MAP;
            case MAP -> Step.DROP;
            case DROP, SUMMARY -> step;
        };
    }

    private void requireMapping() {
        if (mapping == null) {
            throw new IllegalStateException("no file loaded");
        }
    }
}
