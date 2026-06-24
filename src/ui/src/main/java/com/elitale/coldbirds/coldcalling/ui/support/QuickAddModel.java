package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.services.PhoneNormalizer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Headless state + validation for the mid-call quick-add popover ("call my colleague").
 *
 * <p>Holds the two fields (phone required, name optional) and the "add to current list"
 * flag, validates the phone live via {@link PhoneNormalizer}, and produces the
 * {@link LeadService.NewLead} draft. No JavaFX dependency — unit-tested.
 */
public final class QuickAddModel {

    private final PhoneNormalizer normalizer;
    private String phone = "";
    private String name = "";
    private boolean addToCurrentList = true;

    public QuickAddModel(PhoneNormalizer normalizer) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
    }

    public void setPhone(String value) {
        this.phone = value == null ? "" : value;
    }

    public void setName(String value) {
        this.name = value == null ? "" : value;
    }

    public void setAddToCurrentList(boolean value) {
        this.addToCurrentList = value;
    }

    public String phone() {
        return phone;
    }

    public String name() {
        return name;
    }

    public boolean addToCurrentList() {
        return addToCurrentList;
    }

    /** Live normalization outcome for the current phone text against an optional region. */
    public PhoneNormalizer.Outcome normalize(Optional<String> region) {
        return normalizer.normalize(phone, region);
    }

    /** Whether the entered phone resolves to a dialable E.164 number. */
    public boolean canSubmit(Optional<String> region) {
        return normalize(region) instanceof PhoneNormalizer.Normalized;
    }

    /** Build the new-lead draft, or empty when the phone is not yet valid. */
    public Optional<LeadService.NewLead> draft(Optional<String> region) {
        if (!(normalize(region) instanceof PhoneNormalizer.Normalized n)) {
            return Optional.empty();
        }
        final PhoneNumber e164 = n.e164();
        final Optional<String> firstName = name.isBlank() ? Optional.empty() : Optional.of(name.strip());
        return Optional.of(new LeadService.NewLead(
                firstName, Optional.empty(), e164,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty()));
    }
}
