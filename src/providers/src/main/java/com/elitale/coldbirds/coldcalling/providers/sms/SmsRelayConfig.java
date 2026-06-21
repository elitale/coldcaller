package com.elitale.coldbirds.coldcalling.providers.sms;

import java.util.Objects;

/**
 * Configuration for the AWS API Gateway WebSocket SMS relay.
 * Immutable value object.
 */
public record SmsRelayConfig(String websocketUrl, String apiKey) {

    public SmsRelayConfig {
        Objects.requireNonNull(websocketUrl, "websocketUrl must not be null");
        Objects.requireNonNull(apiKey,       "apiKey must not be null");
    }

    /**
     * Whether this config has non-blank credentials and can connect to the relay.
     * When {@code false}, {@link SmsRelayClient#connect} is a no-op.
     */
    public boolean isConfigured() {
        return !websocketUrl.isBlank() && !apiKey.isBlank();
    }
}
