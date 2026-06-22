package com.elitale.coldbirds.coldcalling.domain.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallRoutingConfigTest {

    private static final String URL = "https://example.twil.io/pstn-bridge";

    @Test
    void none_isUnconfigured() {
        CallRoutingConfig config = CallRoutingConfig.none("twilio");
        assertThat(config.mode()).isEqualTo(CallRoutingMode.NONE);
        assertThat(config.voiceUrl()).isEmpty();
        assertThat(config.callerIdFallback()).isEmpty();
        assertThat(config.isConfigured()).isFalse();
    }

    @Test
    void manualWithHttpsUrl_isConfigured() {
        CallRoutingConfig config =
                new CallRoutingConfig("twilio", CallRoutingMode.MANUAL, URL, "+12025550100");
        assertThat(config.isConfigured()).isTrue();
        assertThat(config.voiceUrl()).isEqualTo(URL);
    }

    @Test
    void autoWithHttpsUrl_isConfigured() {
        CallRoutingConfig config =
                new CallRoutingConfig("twilio", CallRoutingMode.AUTO, URL, "");
        assertThat(config.isConfigured()).isTrue();
    }

    @Test
    void nonNoneMode_requiresVoiceUrl() {
        assertThatThrownBy(() ->
                new CallRoutingConfig("twilio", CallRoutingMode.MANUAL, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("voiceUrl is required");
        assertThatThrownBy(() ->
                new CallRoutingConfig("twilio", CallRoutingMode.AUTO, "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonHttpsUrl_rejected() {
        assertThatThrownBy(() ->
                new CallRoutingConfig("twilio", CallRoutingMode.MANUAL, "http://insecure/x", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void blankProviderId_rejected() {
        assertThatThrownBy(() ->
                new CallRoutingConfig(" ", CallRoutingMode.NONE, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerId");
    }

    @Test
    void nullFields_rejected() {
        assertThatThrownBy(() ->
                new CallRoutingConfig(null, CallRoutingMode.NONE, "", ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new CallRoutingConfig("twilio", null, "", ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new CallRoutingConfig("twilio", CallRoutingMode.NONE, null, ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new CallRoutingConfig("twilio", CallRoutingMode.NONE, "", null))
                .isInstanceOf(NullPointerException.class);
    }
}
