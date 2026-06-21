package com.elitale.coldbirds.coldcalling.providers.sms;

import com.elitale.coldbirds.coldcalling.domain.event.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import java.time.temporal.ChronoUnit;

class SmsRelayClientTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    // ── parsePayload ─────────────────────────────────────────────────────────

    @Test
    void testParsePayload_validJson_returnsDomainEvent() {
        String json = """
                {"from":"+15551234567","to":"+15559876543","body":"Hello!","sentAt":"2024-06-01T12:00:00Z"}
                """;

        DomainEvent.IncomingSms event = SmsRelayClient.parsePayload(mapper, json);

        assertThat(event.from().value()).isEqualTo("+15551234567");
        assertThat(event.to().value()).isEqualTo("+15559876543");
        assertThat(event.body()).isEqualTo("Hello!");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2024-06-01T12:00:00Z"));
    }

    @Test
    void testParsePayload_withoutSentAt_usesCurrentTime() {
        String json = """
                {"from":"+15551234567","to":"+15559876543","body":"Hi"}
                """;
        Instant before = Instant.now();

        DomainEvent.IncomingSms event = SmsRelayClient.parsePayload(mapper, json);

        assertThat(event.occurredAt()).isCloseTo(before, within(3, ChronoUnit.SECONDS));
    }

    @Test
    void testParsePayload_invalidPhoneNumber_throwsIllegalArgument() {
        String json = """
                {"from":"not-a-phone","to":"+15559876543","body":"Hi"}
                """;

        assertThatThrownBy(() -> SmsRelayClient.parsePayload(mapper, json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testParsePayload_missingFromField_throwsIllegalArgument() {
        String json = """
                {"to":"+15559876543","body":"Hi"}
                """;

        assertThatThrownBy(() -> SmsRelayClient.parsePayload(mapper, json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testParsePayload_missingToField_throwsIllegalArgument() {
        String json = """
                {"from":"+15551234567","body":"Hi"}
                """;

        assertThatThrownBy(() -> SmsRelayClient.parsePayload(mapper, json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testParsePayload_malformedJson_throwsIllegalArgument() {
        assertThatThrownBy(() -> SmsRelayClient.parsePayload(mapper, "not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot parse SMS relay payload");
    }

    @Test
    void testParsePayload_emptyBody_returnsEventWithEmptyBody() {
        String json = """
                {"from":"+15551234567","to":"+15559876543","body":""}
                """;

        DomainEvent.IncomingSms event = SmsRelayClient.parsePayload(mapper, json);

        assertThat(event.body()).isEmpty();
    }

    // ── SmsRelayConfig ───────────────────────────────────────────────────────

    @Test
    void testConfig_valid_storesFields() {
        SmsRelayConfig cfg = new SmsRelayConfig("wss://example.execute-api.us-east-1.amazonaws.com/prod", "key123");

        assertThat(cfg.websocketUrl()).isEqualTo("wss://example.execute-api.us-east-1.amazonaws.com/prod");
        assertThat(cfg.apiKey()).isEqualTo("key123");
    }

    @Test
    void testConfig_nullUrl_throws() {
        assertThatThrownBy(() -> new SmsRelayConfig(null, "key"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testConfig_blankUrl_isNotConfigured() {
        SmsRelayConfig config = new SmsRelayConfig("  ", "key");
        assertThat(config.isConfigured()).isFalse();
    }

    @Test
    void testConfig_nullApiKey_throws() {
        assertThatThrownBy(() -> new SmsRelayConfig("wss://example.com", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testConfig_blankApiKey_isNotConfigured() {
        SmsRelayConfig config = new SmsRelayConfig("wss://example.com", "");
        assertThat(config.isConfigured()).isFalse();
    }
}
