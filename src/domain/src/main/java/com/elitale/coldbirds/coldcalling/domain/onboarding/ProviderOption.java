package com.elitale.coldbirds.coldcalling.domain.onboarding;

import java.util.Objects;

/**
 * A telephony provider shown on the onboarding "select provider" step.
 * Pure display data — {@code available == false} renders as a disabled
 * "Coming soon" card.
 *
 * @param id          stable identifier (e.g. {@code "twilio"})
 * @param displayName human-readable name shown on the card
 * @param available   whether the provider can be selected yet
 */
public record ProviderOption(String id, String displayName, boolean available) {

    public ProviderOption {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (displayName.isBlank()) throw new IllegalArgumentException("displayName must not be blank");
    }
}
