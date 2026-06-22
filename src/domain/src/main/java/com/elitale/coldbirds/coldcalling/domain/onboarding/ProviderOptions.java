package com.elitale.coldbirds.coldcalling.domain.onboarding;

import java.util.List;

/**
 * Static catalog of telephony providers offered during onboarding.
 * Twilio is available today; the rest are placeholders rendered as
 * "Coming soon" until implemented.
 */
public final class ProviderOptions {

    /** Stable id of the only currently-available provider. */
    public static final String TWILIO_ID = "twilio";

    /** All providers, available first. Unmodifiable. */
    public static final List<ProviderOption> ALL = List.of(
            new ProviderOption(TWILIO_ID, "Twilio", true),
            new ProviderOption("vonage",    "Vonage",    false),
            new ProviderOption("telnyx",    "Telnyx",    false),
            new ProviderOption("plivo",     "Plivo",     false),
            new ProviderOption("bandwidth", "Bandwidth", false)
    );

    private ProviderOptions() {
    }
}
