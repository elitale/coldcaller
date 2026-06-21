package com.elitale.coldbirds.coldcalling.providers.sms;

import com.elitale.coldbirds.coldcalling.domain.event.DomainEvent;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * WebSocket client for the AWS API Gateway SMS relay.
 * <p>
 * Connects to the relay and delivers inbound {@link DomainEvent.IncomingSms}
 * events to a registered listener.
 * <p>
 * Thread-safe: connect/disconnect may be called from any thread.
 */
public final class SmsRelayClient {

    private static final Logger log = LoggerFactory.getLogger(SmsRelayClient.class);

    private final SmsRelayConfig             config;
    private final HttpClient                 httpClient;
    private final ObjectMapper               mapper;
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();

    /** Production constructor. */
    public SmsRelayClient(SmsRelayConfig config) {
        this(config, HttpClient.newHttpClient());
    }

    /** Test constructor — accepts a custom {@link HttpClient}. */
    SmsRelayClient(SmsRelayConfig config, HttpClient httpClient) {
        this.config     = Objects.requireNonNull(config,     "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.mapper     = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Establish a WebSocket connection to the relay and register a listener.
     * Non-blocking — the connection is established asynchronously.
     *
     * @param listener callback invoked on the WebSocket receive thread for each inbound SMS.
     */
    public void connect(Consumer<DomainEvent.IncomingSms> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        if (!config.isConfigured()) {
            log.warn("SMS relay not configured — set SMS_RELAY_URL and SMS_RELAY_KEY to enable inbound SMS");
            return;
        }
        httpClient.newWebSocketBuilder()
                .header("x-api-key", config.apiKey())
                .buildAsync(URI.create(config.websocketUrl()), new RelayListener(mapper, listener))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.error("SMS relay connection failed: {}", err.getMessage());
                    } else {
                        wsRef.set(ws);
                        log.info("SMS relay connected to {}", config.websocketUrl());
                    }
                });
    }

    /**
     * Close the WebSocket connection gracefully. No-op if not connected.
     */
    public void disconnect() {
        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect")
              .exceptionally(err -> {
                  log.warn("Error closing SMS relay WebSocket: {}", err.getMessage());
                  return null;
              });
        }
    }

    // ── JSON parsing — package-private for unit testing ──────────────────────

    /**
     * Parse a JSON payload from the relay into a {@link DomainEvent.IncomingSms}.
     * <p>
     * Expected format:
     * <pre>
     * {
     *   "from":   "+15551234567",
     *   "to":     "+15559876543",
     *   "body":   "Hello!",
     *   "sentAt": "2024-06-01T12:00:00Z"   // optional — defaults to now
     * }
     * </pre>
     *
     * @throws IllegalArgumentException if the JSON is malformed or required fields are absent.
     */
    static DomainEvent.IncomingSms parsePayload(ObjectMapper mapper, String json) {
        try {
            JsonNode root = mapper.readTree(json);

            JsonNode fromNode = root.get("from");
            JsonNode toNode   = root.get("to");
            if (fromNode == null) throw new IllegalArgumentException("Missing 'from' field");
            if (toNode   == null) throw new IllegalArgumentException("Missing 'to' field");

            PhoneNumber from = new PhoneNumber(fromNode.asText());
            PhoneNumber to   = new PhoneNumber(toNode.asText());
            String      body = root.path("body").asText("");

            Instant sentAt = root.has("sentAt")
                    ? Instant.parse(root.get("sentAt").asText())
                    : Instant.now();

            return new DomainEvent.IncomingSms(from, to, body, sentAt);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse SMS relay payload: " + json, e);
        }
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private static final class RelayListener implements WebSocket.Listener {

        private final ObjectMapper                    mapper;
        private final Consumer<DomainEvent.IncomingSms> listener;
        private final StringBuilder                   buffer = new StringBuilder();

        RelayListener(ObjectMapper mapper, Consumer<DomainEvent.IncomingSms> listener) {
            this.mapper   = mapper;
            this.listener = listener;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                try {
                    listener.accept(parsePayload(mapper, json));
                } catch (Exception e) {
                    log.warn("Dropping malformed SMS relay message: {}", e.getMessage());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("SMS relay WebSocket error", error);
        }
    }
}
