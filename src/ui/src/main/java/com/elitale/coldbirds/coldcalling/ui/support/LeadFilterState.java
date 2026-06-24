package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadFilter;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Mutable holder of the Leads screen's filter facets — free-text search, lead
 * statuses, tags, the DNC tri-state, and one custom-field "contains" pair.
 *
 * <p>The popover toggles the live {@link #statuses()} / {@link #tags()} sets and the
 * scalar setters; the controller then calls {@link #toFilter()} to produce an
 * immutable, cursor-free {@link LeadFilter} for the first page of a query.
 *
 * <p>Headless — no JavaFX — so filter construction is unit-testable.
 */
public final class LeadFilterState {

    private String search = "";
    private final Set<LeadStatus> statuses = EnumSet.noneOf(LeadStatus.class);
    private final Set<String> tags = new LinkedHashSet<>();
    private LeadFilter.DncFilter dnc = LeadFilter.DncFilter.ANY;
    private String customFieldKey = "";
    private String customFieldValue = "";
    private Optional<CallListId> listId = Optional.empty();
    private int limit = LeadFilter.DEFAULT_LIMIT;

    public void setSearch(String value) {
        this.search = value == null ? "" : value.strip();
    }

    public String search() {
        return search;
    }

    /** Live, mutable set the popover toggles directly. */
    public Set<LeadStatus> statuses() {
        return statuses;
    }

    /** Live, mutable set the popover toggles directly. */
    public Set<String> tags() {
        return tags;
    }

    public void setDnc(LeadFilter.DncFilter value) {
        this.dnc = Objects.requireNonNull(value, "dnc must not be null");
    }

    public LeadFilter.DncFilter dnc() {
        return dnc;
    }

    public void setCustomField(String key, String value) {
        this.customFieldKey = key == null ? "" : key.strip();
        this.customFieldValue = value == null ? "" : value.strip();
    }

    public String customFieldKey() {
        return customFieldKey;
    }

    public String customFieldValue() {
        return customFieldValue;
    }

    /** Scope the grid to a single list, or {@code Optional.empty()} for all leads. */
    public void setListId(Optional<CallListId> value) {
        this.listId = Objects.requireNonNull(value, "listId must not be null");
    }

    public Optional<CallListId> listId() {
        return listId;
    }

    public void setLimit(int value) {
        this.limit = value;
    }

    /** True when any facet beyond free-text search is active — drives the Filters badge. */
    public boolean hasActiveFacets() {
        return !statuses.isEmpty()
                || !tags.isEmpty()
                || dnc != LeadFilter.DncFilter.ANY
                || !customFieldKey.isBlank();
    }

    /** Reset every facet to its default. Leaves the search text untouched. */
    public void clearFacets() {
        statuses.clear();
        tags.clear();
        dnc = LeadFilter.DncFilter.ANY;
        customFieldKey = "";
        customFieldValue = "";
    }

    /** Build the immutable, cursor-free filter for the first page of a query. */
    public LeadFilter toFilter() {
        final LeadFilter.Builder builder = LeadFilter.builder().limit(limit).dnc(dnc);
        if (!search.isBlank()) {
            builder.search(search);
        }
        if (!statuses.isEmpty()) {
            builder.statuses(EnumSet.copyOf(statuses));
        }
        if (!tags.isEmpty()) {
            builder.tags(new LinkedHashSet<>(tags));
        }
        if (!customFieldKey.isBlank()) {
            builder.customField(customFieldKey, customFieldValue);
        }
        listId.ifPresent(builder::listId);
        return builder.build();
    }
}
