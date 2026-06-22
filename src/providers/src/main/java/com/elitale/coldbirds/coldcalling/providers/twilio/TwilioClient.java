package com.elitale.coldbirds.coldcalling.providers.twilio;

import com.elitale.coldbirds.coldcalling.domain.event.DomainEvent;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.SipProvisioning;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.TwilioNumberData;
import com.twilio.exception.TwilioException;
import com.twilio.http.HttpMethod;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.IncomingPhoneNumber;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.sip.CredentialList;
import com.twilio.rest.api.v2010.account.sip.Domain;
import com.twilio.rest.api.v2010.account.sip.credentiallist.Credential;
import com.twilio.rest.api.v2010.account.sip.domain.authtypes.authtypecalls.AuthCallsCredentialListMapping;
import com.twilio.rest.api.v2010.account.sip.domain.authtypes.authtyperegistrations.AuthRegistrationsCredentialListMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    /**
     * Fetch inbound SMS messages received since {@code since} (exclusive).
     * <p>
     * Replaces the former WebSocket relay: instead of receiving Twilio webhooks, the desktop
     * polls the Messages REST resource. Twilio's {@code DateSent} filter has day granularity,
     * so results are additionally filtered in-memory to be strictly after {@code since}; messages
     * with a non-E.164 sender (e.g. alphanumeric sender IDs) are skipped.
     *
     * @return {@link Result.Ok} with inbound messages newer than {@code since}, oldest-first as
     *         returned by Twilio; {@link Result.Err} on API error or network failure.
     */
    public Result<List<DomainEvent.IncomingSms>> fetchInboundSince(Instant since) {
        Objects.requireNonNull(since, "since must not be null");
        if (!config.isConfigured()) {
            log.warn("Twilio not configured — set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN to fetch inbound SMS");
            return Result.err("Twilio not configured: set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN");
        }
        try {
            final ZonedDateTime after = since.atZone(ZoneOffset.UTC);
            final List<DomainEvent.IncomingSms> inbound = new ArrayList<>();
            for (final Message message : Message.reader().setDateSentAfter(after).read(restClient)) {
                if (message.getDirection() != Message.Direction.INBOUND) continue;

                final Instant sentAt = message.getDateSent() == null
                        ? Instant.now()
                        : message.getDateSent().toInstant();
                if (!sentAt.isAfter(since)) continue;  // day-granularity filter — enforce strict watermark

                try {
                    final PhoneNumber from = new PhoneNumber(addressOf(message.getFrom()));
                    final PhoneNumber to   = new PhoneNumber(message.getTo() == null ? "" : message.getTo());
                    final String body = message.getBody() == null ? "" : message.getBody();
                    inbound.add(new DomainEvent.IncomingSms(from, to, body, sentAt));
                } catch (IllegalArgumentException badAddress) {
                    log.debug("Skipping inbound SMS with non-E.164 address: {}", badAddress.getMessage());
                }
            }
            return Result.ok(List.copyOf(inbound));

        } catch (TwilioException e) {
            log.warn("Twilio fetchInboundSince failed: {}", e.getMessage());
            return Result.err("Twilio fetchInboundSince failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("Twilio fetchInboundSince error", e);
            return Result.err("Twilio fetchInboundSince error: " + e.getMessage(), e);
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

    // ── SIP provisioning ──────────────────────────────────────────────────────

    private static final String CREDENTIAL_LIST_NAME = "coldCalling";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Auto-provision a working SIP registration on this Twilio account.
     *
     * <p>Reuses the first registration-enabled SIP domain when one exists,
     * otherwise creates {@code coldcalling-XXXXXXXX.sip.twilio.com}. A fresh
     * credential (generated username + strong password) is created on the
     * {@code coldCalling} credential list, which is then mapped to the domain for
     * both registration and call auth. Existing mappings are tolerated.
     *
     * @return {@link Result.Ok} with the domain, username and generated password,
     *         or {@link Result.Err} on API/network failure.
     */
    public Result<SipProvisioning> autoProvisionSip() {
        if (!config.isConfigured()) {
            log.warn("Twilio not configured — set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN to provision SIP");
            return Result.err("Twilio not configured: set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN");
        }
        try {
            String domainName = null;
            String domainSid  = null;
            for (final Domain domain : Domain.reader().read(restClient)) {
                if (Boolean.TRUE.equals(domain.getSipRegistration())) {
                    domainName = domain.getDomainName();
                    domainSid  = domain.getSid();
                    break;
                }
            }
            if (domainSid == null) {
                final String newName = "coldcalling-" + randomHex(8) + ".sip.twilio.com";
                final Domain created = Domain.creator(newName)
                        .setFriendlyName(CREDENTIAL_LIST_NAME)
                        .setSipRegistration(true)
                        .create(restClient);
                domainName = created.getDomainName();
                domainSid  = created.getSid();
                log.info("Created Twilio SIP domain {}", domainName);
            }

            String credentialListSid = null;
            for (final CredentialList list : CredentialList.reader().read(restClient)) {
                if (CREDENTIAL_LIST_NAME.equals(list.getFriendlyName())) {
                    credentialListSid = list.getSid();
                    break;
                }
            }
            if (credentialListSid == null) {
                credentialListSid = CredentialList.creator(CREDENTIAL_LIST_NAME).create(restClient).getSid();
            }

            final String username = "coldcalling" + randomHex(4);
            final String password = randomPassword();
            Credential.creator(credentialListSid, username, password).create(restClient);

            mapCredentialList(domainSid, credentialListSid);

            log.info("Provisioned SIP credential {} on domain {}", username, domainName);
            return Result.ok(new SipProvisioning(domainName, username, password));

        } catch (TwilioException e) {
            log.warn("Twilio autoProvisionSip failed: {}", e.getMessage());
            return Result.err("Twilio SIP setup failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("Twilio autoProvisionSip error", e);
            return Result.err("Twilio SIP setup error: " + e.getMessage(), e);
        }
    }

    /** Map a credential list to a domain for registration and call auth; existing mappings are ignored. */
    private void mapCredentialList(final String domainSid, final String credentialListSid) {
        try {
            AuthRegistrationsCredentialListMapping.creator(domainSid, credentialListSid).create(restClient);
        } catch (TwilioException e) {
            log.debug("Registration credential-list mapping skipped: {}", e.getMessage());
        }
        try {
            AuthCallsCredentialListMapping.creator(domainSid, credentialListSid).create(restClient);
        } catch (TwilioException e) {
            log.debug("Calls credential-list mapping skipped: {}", e.getMessage());
        }
    }

    private static String randomHex(final int chars) {
        final byte[] bytes = new byte[(chars + 1) / 2];
        RANDOM.nextBytes(bytes);
        final StringBuilder sb = new StringBuilder();
        for (final byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.substring(0, chars);
    }

    /** Generate a Twilio-compliant SIP password: 16 chars with upper, lower and digit. */
    private static String randomPassword() {
        final String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        final String lower = "abcdefghijkmnpqrstuvwxyz";
        final String digit = "23456789";
        final String all   = upper + lower + digit;
        final StringBuilder sb = new StringBuilder();
        sb.append(upper.charAt(RANDOM.nextInt(upper.length())));
        sb.append(lower.charAt(RANDOM.nextInt(lower.length())));
        sb.append(digit.charAt(RANDOM.nextInt(digit.length())));
        for (int i = 3; i < 16; i++) {
            sb.append(all.charAt(RANDOM.nextInt(all.length())));
        }
        return sb.toString();
    }

    // ── SIP voice routing (PSTN bridge) ───────────────────────────────────────

    /**
     * Point a SIP domain's Voice webhook (the "A call comes in" URL) at {@code voiceUrl}
     * using HTTP POST. This is what lets a registered SIP client place outbound calls that
     * bridge to the PSTN: Twilio invokes this URL per outbound call to fetch the TwiML that
     * dials the destination number.
     *
     * @param domainName the SIP domain (e.g. {@code coldcalling-1234.sip.twilio.com})
     * @param voiceUrl   the https webhook URL Twilio invokes per outbound call
     * @return {@link Result.Ok} on success, {@link Result.Err} if the domain is unknown or the API fails.
     */
    public Result<Void> setSipDomainVoiceUrl(String domainName, String voiceUrl) {
        Objects.requireNonNull(domainName, "domainName must not be null");
        Objects.requireNonNull(voiceUrl,   "voiceUrl must not be null");
        if (!config.isConfigured()) {
            return Result.err("Twilio not configured: set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN");
        }
        try {
            final String domainSid = findDomainSid(domainName);
            if (domainSid == null) {
                return Result.err("SIP domain not found: " + domainName);
            }
            Domain.updater(domainSid)
                    .setVoiceUrl(URI.create(voiceUrl))
                    .setVoiceMethod(HttpMethod.POST)
                    .update(restClient);
            log.info("Set SIP domain {} voice URL", domainName);
            return Result.ok(null);

        } catch (TwilioException e) {
            log.warn("Twilio setSipDomainVoiceUrl failed: {}", e.getMessage());
            return Result.err("Twilio setSipDomainVoiceUrl failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("Twilio setSipDomainVoiceUrl error", e);
            return Result.err("Twilio setSipDomainVoiceUrl error: " + e.getMessage(), e);
        }
    }

    /**
     * Read the Voice webhook URL currently configured on a SIP domain.
     *
     * @return {@link Result.Ok} holding the URL when set (empty when the domain has none),
     *         or {@link Result.Err} if the domain is unknown or the API fails.
     */
    public Result<Optional<String>> readSipDomainVoiceUrl(String domainName) {
        Objects.requireNonNull(domainName, "domainName must not be null");
        if (!config.isConfigured()) {
            return Result.err("Twilio not configured: set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN");
        }
        try {
            for (final Domain domain : Domain.reader().read(restClient)) {
                if (domainName.equals(domain.getDomainName())) {
                    final URI url = domain.getVoiceUrl();
                    return Result.ok(url == null || url.toString().isBlank()
                            ? Optional.empty()
                            : Optional.of(url.toString()));
                }
            }
            return Result.err("SIP domain not found: " + domainName);

        } catch (TwilioException e) {
            log.warn("Twilio readSipDomainVoiceUrl failed: {}", e.getMessage());
            return Result.err("Twilio readSipDomainVoiceUrl failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("Twilio readSipDomainVoiceUrl error", e);
            return Result.err("Twilio readSipDomainVoiceUrl error: " + e.getMessage(), e);
        }
    }

    private String findDomainSid(final String domainName) {
        for (final Domain domain : Domain.reader().read(restClient)) {
            if (domainName.equals(domain.getDomainName())) {
                return domain.getSid();
            }
        }
        return null;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String addressOf(com.twilio.type.PhoneNumber address) {
        return address == null ? "" : address.toString();
    }

    private static TwilioRestClient buildRestClient(TwilioConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return new TwilioRestClient.Builder(config.accountSid(), config.authToken()).build();
    }
}
