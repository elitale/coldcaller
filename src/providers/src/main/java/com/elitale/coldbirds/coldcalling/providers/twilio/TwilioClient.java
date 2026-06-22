package com.elitale.coldbirds.coldcalling.providers.twilio;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.TwilioNumberData;
import com.twilio.exception.TwilioException;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.IncomingPhoneNumber;
import com.twilio.rest.api.v2010.account.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Client for the Twilio REST API, backed by the official Twilio Java SDK.
 * <p>
 * All operations return {@link Result} — never throw on API or network errors.
 * Thread-safe: the underlying {@link TwilioRestClient} is shared across calls.
 * <p>
 * Authentication is handled by the SDK using the Account SID and Auth Token
 * supplied via {@link TwilioConfig}.
 */
public final class TwilioClient {

    private static final Logger log = LoggerFactory.getLogger(TwilioClient.class);

    private final TwilioConfig     config;
    private final TwilioRestClient restClient;

    /** Production constructor — builds a real {@link TwilioRestClient} from {@code config}. */
    public TwilioClient(TwilioConfig config) {
        this(config, buildRestClient(config));
    }

    /** Test/advanced constructor — accepts a pre-built {@link TwilioRestClient}. */
    TwilioClient(TwilioConfig config, TwilioRestClient restClient) {
        this.config     = Objects.requireNonNull(config,     "config must not be null");
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    }

    // ── SMS ──────────────────────────────────────────────────────────────────

    /**
     * Send an outbound SMS via the Twilio Messages resource.
     *
     * @return {@link Result.Ok} with the Twilio message SID on success,
     *         {@link Result.Err} on API error or network failure.
     */
    public Result<String> sendSms(PhoneNumber from, PhoneNumber to, String body) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to,   "to must not be null");
        Objects.requireNonNull(body, "body must not be null");
        if (body.isBlank()) return Result.err("SMS body must not be blank");
        if (!config.isConfigured()) {
            log.warn("Twilio not configured — set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN to enable SMS");
            return Result.err("Twilio not configured: set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN");
        }

        try {
            Message message = Message.creator(
                            new com.twilio.type.PhoneNumber(to.value()),
                            new com.twilio.type.PhoneNumber(from.value()),
                            body)
                    .create(restClient);

            String sid = message.getSid();
            if (sid == null || sid.isBlank()) {
                return Result.err("Missing message SID in Twilio sendSms response");
            }
            log.debug("Twilio sendSms OK — sid={}", sid);
            return Result.ok(sid);

        } catch (TwilioException e) {
            log.warn("Twilio sendSms failed: {}", e.getMessage());
            return Result.err("Twilio sendSms failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("Twilio sendSms error", e);
            return Result.err("Twilio sendSms error: " + e.getMessage(), e);
        }
    }

    // ── Phone numbers ─────────────────────────────────────────────────────────

    /**
     * List all phone numbers owned by this Twilio account.
     *
     * @return {@link Result.Ok} with an unmodifiable list of {@link TwilioNumberData}.
     */
    public Result<List<TwilioNumberData>> listPhoneNumbers() {
        if (!config.isConfigured()) {
            log.warn("Twilio not configured — set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN to list phone numbers");
            return Result.err("Twilio not configured: set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN");
        }
        try {
            List<TwilioNumberData> numbers = new ArrayList<>();
            for (IncomingPhoneNumber number : IncomingPhoneNumber.reader().read(restClient)) {
                numbers.add(new TwilioNumberData(
                        number.getSid(),
                        number.getPhoneNumber() == null ? "" : number.getPhoneNumber().toString(),
                        number.getStatus() == null ? "active" : number.getStatus()
                ));
            }
            return Result.ok(List.copyOf(numbers));

        } catch (TwilioException e) {
            log.warn("Twilio listPhoneNumbers failed: {}", e.getMessage());
            return Result.err("Twilio listPhoneNumbers failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("Twilio listPhoneNumbers error", e);
            return Result.err("Twilio listPhoneNumbers error: " + e.getMessage(), e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static TwilioRestClient buildRestClient(TwilioConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return new TwilioRestClient.Builder(config.accountSid(), config.authToken()).build();
    }
}
