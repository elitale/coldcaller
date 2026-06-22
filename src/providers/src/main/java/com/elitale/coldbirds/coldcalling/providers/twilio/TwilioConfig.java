package com.elitale.coldbirds.coldcalling.providers.twilio;

import java.util.Objects;

/**
 * Configuration for the Twilio REST API client.
 * Immutable value object. Construct via {@link #of(String, String)} for production use.
 * <p>
 * Twilio authenticates with HTTP Basic auth using the Account SID as the username
 * and the Auth Token as the password.
 */
public record TwilioConfig(String accountSid, String authToken, String baseUrl) {

    public static final String DEFAULT_BASE_URL = "https://api.twilio.com/2010-04-01";

    public TwilioConfig {
        Objects.requireNonNull(accountSid, "accountSid must not be null");
        Objects.requireNonNull(authToken,  "authToken must not be null");
        Objects.requireNonNull(baseUrl,    "baseUrl must not be null");
        if (baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
    }

    /**
     * Whether this config has non-blank credentials and can make real API calls.
     * When {@code false}, the client will return {@link com.elitale.coldbirds.coldcalling.domain.value.Result.Err}
     * for every operation rather than hitting the network.
     */
    public boolean isConfigured() {
        return !accountSid.isBlank() && !authToken.isBlank();
    }

    /** Construct a config pointing at the production Twilio API. */
    public static TwilioConfig of(String accountSid, String authToken) {
        return new TwilioConfig(accountSid, authToken, DEFAULT_BASE_URL);
    }
}
