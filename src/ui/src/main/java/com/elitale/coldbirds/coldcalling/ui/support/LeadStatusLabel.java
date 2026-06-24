package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;

/** Human-readable labels for {@link LeadStatus} shown in the Leads UI. */
public final class LeadStatusLabel {

    private LeadStatusLabel() {
    }

    public static String of(LeadStatus status) {
        return switch (status) {
            case NEW -> "New";
            case CONTACTED -> "Contacted";
            case INTERESTED -> "Interested";
            case CALLBACK -> "Callback";
            case NOT_INTERESTED -> "Not interested";
            case BAD_NUMBER -> "Bad number";
            case DNC -> "Do not call";
        };
    }
}
