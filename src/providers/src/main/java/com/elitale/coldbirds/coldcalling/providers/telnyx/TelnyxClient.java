package com.elitale.coldbirds.coldcalling.providers.telnyx;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.providers.telnyx.dto.TelnyxNumberData;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP client for the Telnyx REST API (v2).
 * <p>
 * All network operations return {@link Result} — never throw on HTTP or network errors.
 * Thread-safe: the underlying {@link HttpClient} is shared across calls.
 */
public final class TelnyxClient {

    private static final Logger log = LoggerFactory.getLogger(TelnyxClient.class);

    private final TelnyxConfig config;
    private final HttpSender   sender;
    private final ObjectMapper mapper;

    /** Production constructor — uses a real {@link HttpClient}. */
    public TelnyxClient(TelnyxConfig config) {
        this(config, buildDefaultSender());
    }

    /** Test constructor — accepts a custom {@link HttpSender}. */
    TelnyxClient(TelnyxConfig config, HttpSender sender) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ── SMS ──────────────────────────────────────────────────────────────────

    /**
     * Send an outbound SMS via Telnyx POST /v2/messages.
     *
     * @return {@link Result.Ok} with the Telnyx message UUID on success,
     *         {@link Result.Err} on HTTP error or network failure.
     */
    public Result<String> sendSms(PhoneNumber from, PhoneNumber to, String body) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to,   "to must not be null");
        Objects.requireNonNull(body, "body must not be null");
        if (body.isBlank()) return Result.err("SMS body must not be blank");
        if (!config.isConfigured()) {
            log.warn("Telnyx not configured — set TELNYX_API_KEY to enable SMS");
            return Result.err("Telnyx not configured: set TELNYX_API_KEY");
        }

        try {
            String requestJson = mapper.writeValueAsString(Map.of(
                    "from", from.value(),
                    "to",   to.value(),
                    "text", body
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/messages"))
                    .header("Authorization",  "Bearer " + config.apiKey())
                    .header("Content-Type",   "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = sender.send(request);

            if (isSuccess(response.statusCode())) {
                JsonNode root      = mapper.readTree(response.body());
                String   messageId = root.path("data").path("id").asText();
                if (messageId.isEmpty()) {
                    return Result.err("Missing message ID in Telnyx sendSms response");
                }
                log.debug("Telnyx sendSms OK — messageId={}", messageId);
                return Result.ok(messageId);
            } else {
                log.warn("Telnyx sendSms failed: HTTP {}", response.statusCode());
                return Result.err("Telnyx sendSms failed: HTTP " + response.statusCode()
                        + " " + response.body());
            }

        } catch (IOException e) {
            log.error("Telnyx sendSms network error", e);
            return Result.err("Telnyx sendSms network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err("Telnyx sendSms interrupted: " + e.getMessage(), e);
        }
    }

    // ── Phone numbers ─────────────────────────────────────────────────────────

    /**
     * List all phone numbers owned by this Telnyx account.
     *
     * @return {@link Result.Ok} with an unmodifiable list of {@link TelnyxNumberData}.
     */
    public Result<List<TelnyxNumberData>> listPhoneNumbers() {
        if (!config.isConfigured()) {
            log.warn("Telnyx not configured — set TELNYX_API_KEY to list phone numbers");
            return Result.err("Telnyx not configured: set TELNYX_API_KEY");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/phone_numbers"))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .GET()
                    .build();

            HttpResponse<String> response = sender.send(request);

            if (isSuccess(response.statusCode())) {
                JsonNode root      = mapper.readTree(response.body());
                JsonNode dataArray = root.path("data");

                List<TelnyxNumberData> numbers = new ArrayList<>();
                for (JsonNode node : dataArray) {
                    numbers.add(new TelnyxNumberData(
                            node.path("id").asText(),
                            node.path("phone_number").asText(),
                            node.path("status").asText()
                    ));
                }
                return Result.ok(List.copyOf(numbers));
            } else {
                return Result.err("Telnyx listPhoneNumbers failed: HTTP " + response.statusCode());
            }

        } catch (IOException e) {
            log.error("Telnyx listPhoneNumbers network error", e);
            return Result.err("Telnyx listPhoneNumbers network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err("Telnyx listPhoneNumbers interrupted: " + e.getMessage(), e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static HttpSender buildDefaultSender() {
        HttpClient httpClient = HttpClient.newHttpClient();
        return req -> httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
