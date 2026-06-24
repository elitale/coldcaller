package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.LeadId;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Tracks the set of leads selected via the table's bulk-select checkboxes.
 *
 * <p>Headless and FX-free so it can be unit-tested; the controller mirrors its state
 * onto checkbox cells. Insertion order is preserved for stable bulk operations.
 */
public final class LeadSelectionModel {

    private final LinkedHashSet<LeadId> selected = new LinkedHashSet<>();

    /** Toggle a lead's selection. Returns the new selected state. */
    public boolean toggle(LeadId id) {
        Objects.requireNonNull(id, "id must not be null");
        if (selected.add(id)) {
            return true;
        }
        selected.remove(id);
        return false;
    }

    public void select(LeadId id) {
        selected.add(Objects.requireNonNull(id, "id must not be null"));
    }

    public void deselect(LeadId id) {
        selected.remove(Objects.requireNonNull(id, "id must not be null"));
    }

    public void selectAll(Collection<LeadId> ids) {
        selected.addAll(Objects.requireNonNull(ids, "ids must not be null"));
    }

    public void clear() {
        selected.clear();
    }

    public boolean isSelected(LeadId id) {
        return selected.contains(id);
    }

    public boolean isEmpty() {
        return selected.isEmpty();
    }

    public int count() {
        return selected.size();
    }

    /** Unmodifiable snapshot of the current selection in insertion order. */
    public List<LeadId> selectedIds() {
        return List.copyOf(selected);
    }
}
