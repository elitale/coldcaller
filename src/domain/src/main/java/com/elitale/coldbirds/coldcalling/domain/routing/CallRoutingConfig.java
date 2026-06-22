package com.elitale.coldbirds.coldcalling.domain.routing;

import java.util.Objects;

/**
 * Outbound call-routing (PSTN bridge) configuration for a telephony provider.
 *
 * <p>Describes <em>where</em> a provider should bridge a registered SIP client's
 * outbound call to the phone network: the Voice webhook URL the provider invokes
 * per call. Provider-agnostic value object — the only provider-specific knowledge
 * lives in the service that applies it.
 *
 * @param providerId       stable provider id (e.g. {@code "twilio"})
 * @param mode             how the {@code voiceUrl} was obtained
 * @param voiceUrl         the Voice webhook URL ({@code https://…}); blank only when
 *                         {@code mode == }{@link CallRoutingMode#NONE}
 * @param callerIdFallback optional E.164 caller ID used when the caller is unknown;
 *                         may be blank
 */
public record CallRoutingConfig(
        String providerId,
        CallRoutingMode mode,
        String voiceUrl,
        String callerIdFallback) {

    public CallRoutingConfig {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(voiceUrl, "voiceUrl must not be null");
        Objects.requireNonNull(callerIdFallback, "callerIdFallback must not be null");
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (mode != CallRoutingMode.NONE && voiceUrl.isBlank()) {
            throw new IllegalArgumentException("voiceUrl is required when mode is " + mode);
        }
        if (!voiceUrl.isBlank() && !voiceUrl.startsWith("https://")) {
            throw new IllegalArgumentException("voiceUrl must be an https URL: " + voiceUrl);
        }
    }

    /** Whether this config has a usable bridge URL. */
    public boolean isConfigured() {
        return mode != CallRoutingMode.NONE && !voiceUrl.isBlank();
    }

    /** An unconfigured config for {@code providerId}. */
    public static CallRoutingConfig none(String providerId) {
        return new CallRoutingConfig(providerId, CallRoutingMode.NONE, "", "");
    }
}
