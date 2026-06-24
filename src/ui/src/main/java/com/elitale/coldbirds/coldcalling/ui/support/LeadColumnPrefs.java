package com.elitale.coldbirds.coldcalling.ui.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * The ordered set of custom-field columns the user has chosen to show in the leads grid.
 *
 * <p>Immutable value object. Persisted as a comma-separated string in the {@code settings}
 * table (key {@code leads.columns}) so the grid layout survives restarts. The displayed
 * custom columns are exactly this list, in this order — data-discovered keys are merged in
 * on load, and "Add column" appends a new key.
 */
public final class LeadColumnPrefs {

    private final List<String> columns;

    public LeadColumnPrefs(List<String> columns) {
        Objects.requireNonNull(columns, "columns must not be null");
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String c : columns) {
            if (c != null && !c.isBlank()) {
                distinct.add(c.strip());
            }
        }
        this.columns = List.copyOf(distinct);
    }

    public static LeadColumnPrefs empty() {
        return new LeadColumnPrefs(List.of());
    }

    /** Parse the persisted comma-separated form. Blanks and duplicates are dropped. */
    public static LeadColumnPrefs parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return empty();
        }
        return new LeadColumnPrefs(List.of(csv.split(",")));
    }

    /** Comma-separated form for persistence. */
    public String serialize() {
        return String.join(",", columns);
    }

    public List<String> columns() {
        return columns;
    }

    public boolean contains(String key) {
        return columns.contains(key == null ? null : key.strip());
    }

    /** Copy with {@code key} appended if not already present. */
    public LeadColumnPrefs withColumn(String key) {
        if (key == null || key.isBlank() || contains(key)) {
            return this;
        }
        List<String> next = new ArrayList<>(columns);
        next.add(key.strip());
        return new LeadColumnPrefs(next);
    }

    /** Copy with {@code key} removed. */
    public LeadColumnPrefs without(String key) {
        if (key == null || !contains(key)) {
            return this;
        }
        List<String> next = new ArrayList<>(columns);
        next.remove(key.strip());
        return new LeadColumnPrefs(next);
    }

    /** Copy with every given key merged in (preserving existing order, appending new keys). */
    public LeadColumnPrefs mergeAll(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return this;
        }
        List<String> next = new ArrayList<>(columns);
        for (String k : keys) {
            if (k != null && !k.isBlank() && !next.contains(k.strip())) {
                next.add(k.strip());
            }
        }
        return new LeadColumnPrefs(next);
    }
}
