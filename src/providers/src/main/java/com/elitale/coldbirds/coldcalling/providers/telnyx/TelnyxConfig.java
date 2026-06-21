package com.elitale.coldbirds.coldcalling.providers.telnyx;

import java.util.Objects;

/**
 * Configuration for the Telnyx REST API client.
 * Immutable value object. Construct via {@link #of(String)} for production use.
 */
public record TelnyxConfig(String apiKey, String baseUrl) {

    public static final String DEFAULT_BASE_URL = "https://api.telnyx.com/v2";

    public TelnyxConfig {
        Objects.requireNonNull(apiKey,  "apiKey must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        if (baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
    }

    /**
     * Whether this config has a non-blank API key and can make real API calls.
     * When {@code false}, the client will return {@link com.elitale.coldbirds.coldcalling.domain.value.Result.Err}
     * for every operation rather than hitting the network.
     */
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /** Construct a config pointing at the production Telnyx API. */
    public static TelnyxConfig of(String apiKey) {
        return new TelnyxConfig(apiKey, DEFAULT_BASE_URL);
    }
}
