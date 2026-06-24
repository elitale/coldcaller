package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Search + outcome-preset filter state for Call History (direction is handled separately at the
 * per-call level). Pure and unit-tested; mirrors {@code LeadFilterState}.
 */
public final class CallHistoryFilterState {

    /** Workflow-real outcome presets the operator filters by. */
    public enum Preset { CALLBACKS, NO_ANSWER, INTERESTED, VOICEMAIL, DNC }

    private String search = "";
    private final EnumSet<Preset> presets = EnumSet.noneOf(Preset.class);

    public void setSearch(String value) {
        this.search = value == null ? "" : value.strip();
    }

    public String search() {
        return search;
    }

    public void togglePreset(Preset preset, boolean on) {
        Objects.requireNonNull(preset, "preset must not be null");
        if (on) {
            presets.add(preset);
        } else {
            presets.remove(preset);
        }
    }

    public Set<Preset> presets() {
        return Set.copyOf(presets);
    }

    public boolean isActive() {
        return !search.isBlank() || !presets.isEmpty();
    }

    /**
     * Whether a rolled-up row passes the current search + presets. Search matches the number, lead
     * name or company; presets OR together (any selected outcome matches).
     */
    public boolean matches(String number, Optional<String> name, Optional<String> company,
                           CallOutcome lastOutcome, Optional<CallDisposition> badge,
                           boolean containsDnc, boolean hasCallback) {
        if (!search.isBlank()) {
            final String q = search.toLowerCase(Locale.ROOT);
            final boolean textHit = number.toLowerCase(Locale.ROOT).contains(q)
                    || name.map(s -> s.toLowerCase(Locale.ROOT).contains(q)).orElse(false)
                    || company.map(s -> s.toLowerCase(Locale.ROOT).contains(q)).orElse(false);
            if (!textHit) {
                return false;
            }
        }
        if (presets.isEmpty()) {
            return true;
        }
        for (Preset preset : presets) {
            if (matchesPreset(preset, lastOutcome, badge, containsDnc, hasCallback)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPreset(Preset preset, CallOutcome lastOutcome,
                                         Optional<CallDisposition> badge, boolean containsDnc,
                                         boolean hasCallback) {
        return switch (preset) {
            case CALLBACKS  -> hasCallback;
            case NO_ANSWER  -> lastOutcome == CallOutcome.NO_ANSWER;
            case VOICEMAIL  -> lastOutcome == CallOutcome.VOICEMAIL;
            case DNC        -> containsDnc;
            case INTERESTED -> badge.map(d -> d instanceof CallDisposition.Interested).orElse(false);
        };
    }
}
