package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.event.DomainEvent;
import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.SmsRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.SmsRepository.NewSmsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Handles outbound SMS via {@link TwilioClient} and inbound SMS by polling the Twilio
 * REST API on a background scheduler. Persists all messages to {@link SmsRepository}.
 */
public final class SmsService {

    private static final Logger LOG = LoggerFactory.getLogger(SmsService.class);

    /** Default cadence for inbound polling. */
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(15);

    private final TwilioClient          twilio;
    private final SmsRepository         smsRepo;
    private final PhoneNumberRepository phoneNumberRepo;
    private final SettingsService       settings;

    /** Active inbound poller, or null when not polling. Guarded by {@code this}. */
    private ScheduledExecutorService poller;

    public SmsService(
            TwilioClient          twilio,
            SmsRepository         smsRepo,
            PhoneNumberRepository phoneNumberRepo,
            SettingsService       settings) {
        this.twilio          = Objects.requireNonNull(twilio,          "twilio must not be null");
        this.smsRepo         = Objects.requireNonNull(smsRepo,         "smsRepo must not be null");
        this.phoneNumberRepo = Objects.requireNonNull(phoneNumberRepo, "phoneNumberRepo must not be null");
        this.settings        = Objects.requireNonNull(settings,        "settings must not be null");
    }

    /**
     * Send an outbound SMS. Persists the message only on Twilio API success.
     *
     * @param from local owned number
     * @param to   recipient E.164 number
     * @param body message text (must not be blank)
     * @return {@link Result#ok} with the {@link SmsId}, or {@link Result#err}
     */
    public Result<SmsId> send(PhoneNumber from, PhoneNumber to, String body) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to,   "to must not be null");
        Objects.requireNonNull(body, "body must not be null");

        final Optional<OwnedNumber> owned = phoneNumberRepo.findByNumber(from);
        if (owned.isEmpty()) {
            LOG.error("Cannot send SMS: from number {} is not an owned number", from.value());
            return Result.err("from number not owned: " + from.value());
        }

        final Result<String> apiResult = twilio.sendSms(from, to, body);
        return switch (apiResult) {
            case Result.Err<?> err -> {
                LOG.error("Twilio sendSms failed: {}", err.message());
                yield Result.err(err.message());
            }
            case Result.Ok<?> ignored -> {
                final NewSmsMessage record = new NewSmsMessage(
                        CallDirection.OUTBOUND,
                        owned.get().id(),
                        Optional.empty(),  // leadId resolved at UI layer
                        to,
                        body,
                        new SmsStatus.Delivered(),
                        java.time.Instant.now()
                );
                final var saved = smsRepo.save(record);
                yield switch (saved) {
                    case Result.Ok<com.elitale.coldbirds.coldcalling.domain.model.SmsMessage> ok ->
                            Result.ok(ok.value().id());
                    case Result.Err<?> err -> {
                        LOG.error("Failed to persist outbound SMS: {}", err.message());
                        yield Result.err(err.message());
                    }
                };
            }
        };
    }

    /**
     * Begin polling Twilio for inbound SMS at {@link #DEFAULT_POLL_INTERVAL}. Each newly
     * fetched message is persisted and passed to {@code handler}. Idempotent: a second call
     * while already polling is ignored.
     *
     * @param handler called for each new inbound message (on a polling thread)
     */
    public void startReceiving(Consumer<DomainEvent.IncomingSms> handler) {
        startReceiving(handler, DEFAULT_POLL_INTERVAL);
    }

    /** Begin polling at a custom {@code interval}. See {@link #startReceiving(Consumer)}. */
    public synchronized void startReceiving(Consumer<DomainEvent.IncomingSms> handler, Duration interval) {
        Objects.requireNonNull(handler,  "handler must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        if (poller != null) return;  // already polling
        poller = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("sms-poller").factory());
        poller.scheduleAtFixedRate(
                () -> pollInbound().forEach(handler),
                0, interval.toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("SMS inbound polling started ({}s interval)", interval.toSeconds());
    }

    /** Stop inbound polling. No-op if not polling. */
    public synchronized void stopReceiving() {
        if (poller == null) return;
        poller.shutdownNow();
        poller = null;
        LOG.info("SMS inbound polling stopped");
    }

    /**
     * Fetch any new inbound SMS from Twilio once — the same work a single background
     * poll tick performs — for the manual "Refresh" action now that automatic polling
     * is disabled. Persists new messages, advances the watermark, and returns the count
     * of new inbound messages. Blocking I/O — never call on the FX thread.
     */
    public int refreshInbound() {
        return pollInbound().size();
    }

    /**
     * Fetch inbound SMS newer than the persisted watermark, persist each new message, and
     * advance the watermark. Returns the newly persisted inbound events (may be empty).
     * <p>
     * Package-visible for the scheduler and tests; performs blocking I/O — never call on the
     * FX thread.
     */
    List<DomainEvent.IncomingSms> pollInbound() {
        final Instant since = settings.getSmsLastPolledAt();
        final Result<List<DomainEvent.IncomingSms>> fetched = twilio.fetchInboundSince(since);
        if (fetched instanceof Result.Err<?> err) {
            LOG.warn("Inbound SMS poll failed: {}", err.message());
            return List.of();
        }

        final List<DomainEvent.IncomingSms> events = ((Result.Ok<List<DomainEvent.IncomingSms>>) fetched).value();
        Instant watermark = since;
        for (final DomainEvent.IncomingSms event : events) {
            persistInbound(event);
            if (event.occurredAt().isAfter(watermark)) watermark = event.occurredAt();
        }
        if (watermark.isAfter(since)) settings.setSmsLastPolledAt(watermark);
        return events;
    }

    /**
     * Returns the most recent message per unique remote number (conversation previews),
     * ordered newest-conversation-first.
     */
    public List<SmsMessage> findConversations() {
        final LinkedHashMap<String, SmsMessage> byNumber = new LinkedHashMap<>();
        for (final SmsMessage msg : smsRepo.findRecent(500)) {
            byNumber.putIfAbsent(msg.remoteNumber().value(), msg);
        }
        return List.copyOf(byNumber.values());
    }

    /**
     * Returns all messages exchanged with {@code remoteNumber}, oldest-first
     * (ascending by sentAt) for chat-thread display.
     */
    public List<SmsMessage> findThread(PhoneNumber remoteNumber) {
        Objects.requireNonNull(remoteNumber, "remoteNumber must not be null");
        return smsRepo.findByRemoteNumber(remoteNumber).stream()
                .sorted(Comparator.comparing(SmsMessage::sentAt))
                .toList();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void persistInbound(DomainEvent.IncomingSms event) {
        final Optional<OwnedNumber> owned = phoneNumberRepo.findByNumber(event.to());
        if (owned.isEmpty()) {
            LOG.warn("Inbound SMS to unrecognised number {}", event.to().value());
            return;
        }
        final NewSmsMessage record = new NewSmsMessage(
                CallDirection.INBOUND,
                owned.get().id(),
                Optional.empty(),
                event.from(),
                event.body(),
                new SmsStatus.Delivered(),
                event.occurredAt()
        );
        final var result = smsRepo.save(record);
        if (result instanceof Result.Err<?> err) {
            LOG.error("Failed to persist inbound SMS: {}", err.message());
        }
    }
}
