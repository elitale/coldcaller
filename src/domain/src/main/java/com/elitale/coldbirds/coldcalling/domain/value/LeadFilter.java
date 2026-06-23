package com.elitale.coldbirds.coldcalling.domain.value;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable specification for a lead query: search, per-column substrings, status,
 * tag, DNC and list facets (AND-ed together) plus keyset paging.
 *
 * <p>Wide aggregate built from many independent UI controls — use {@link #builder()}.
 * The repository translates this into a single parameterised SQL query.
 */
public record LeadFilter(
        Optional<String> search,
        Map<LeadColumn, String> columnFilters,
        Set<LeadStatus> statuses,
        Set<String> tags,
        DncFilter dnc,
        Optional<CallListId> listId,
        Map<String, String> customFields,
        int limit,
        Optional<Cursor> cursor
) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    /** Do-Not-Call facet: no constraint, only DNC, or exclude DNC. */
    public enum DncFilter { ANY, ONLY, EXCLUDE }

    public LeadFilter {
        Objects.requireNonNull(search, "search must not be null");
        Objects.requireNonNull(columnFilters, "columnFilters must not be null");
        Objects.requireNonNull(statuses, "statuses must not be null");
        Objects.requireNonNull(tags, "tags must not be null");
        Objects.requireNonNull(dnc, "dnc must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(customFields, "customFields must not be null");
        Objects.requireNonNull(cursor, "cursor must not be null");
        columnFilters = Map.copyOf(columnFilters);
        statuses = Set.copyOf(statuses);
        tags = Set.copyOf(tags);
        customFields = Map.copyOf(customFields);
        limit = Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    /** A filter with no constraints and the default page size. */
    public static LeadFilter all() {
        return builder().build();
    }

    /** Copy of this filter advanced to the next page. */
    public LeadFilter withCursor(Cursor next) {
        Objects.requireNonNull(next, "next must not be null");
        return new LeadFilter(search, columnFilters, statuses, tags, dnc, listId,
                customFields, limit, Optional.of(next));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; blank search/column/custom values are dropped. */
    public static final class Builder {
        private Optional<String> search = Optional.empty();
        private final Map<LeadColumn, String> columnFilters = new EnumMap<>(LeadColumn.class);
        private Set<LeadStatus> statuses = Set.of();
        private Set<String> tags = Set.of();
        private DncFilter dnc = DncFilter.ANY;
        private Optional<CallListId> listId = Optional.empty();
        private final Map<String, String> customFields = new LinkedHashMap<>();
        private int limit = DEFAULT_LIMIT;
        private Optional<Cursor> cursor = Optional.empty();

        public Builder search(String value) {
            this.search = Optional.ofNullable(value).map(String::strip).filter(s -> !s.isEmpty());
            return this;
        }

        public Builder column(LeadColumn column, String substring) {
            Objects.requireNonNull(column, "column must not be null");
            if (substring != null && !substring.isBlank()) {
                columnFilters.put(column, substring.strip());
            }
            return this;
        }

        public Builder statuses(Set<LeadStatus> value) {
            this.statuses = Objects.requireNonNull(value, "statuses must not be null");
            return this;
        }

        public Builder tags(Set<String> value) {
            this.tags = Objects.requireNonNull(value, "tags must not be null");
            return this;
        }

        public Builder dnc(DncFilter value) {
            this.dnc = Objects.requireNonNull(value, "dnc must not be null");
            return this;
        }

        public Builder listId(CallListId value) {
            this.listId = Optional.ofNullable(value);
            return this;
        }

        public Builder customField(String key, String substring) {
            if (key != null && !key.isBlank() && substring != null && !substring.isBlank()) {
                customFields.put(key, substring.strip());
            }
            return this;
        }

        public Builder limit(int value) {
            this.limit = value;
            return this;
        }

        public Builder cursor(Cursor value) {
            this.cursor = Optional.ofNullable(value);
            return this;
        }

        public LeadFilter build() {
            return new LeadFilter(search, columnFilters, statuses, tags, dnc, listId,
                    customFields, limit, cursor);
        }
    }
}
