package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.event.DomainEvent;
import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.providers.sms.SmsRelayClient;
import com.elitale.coldbirds.coldcalling.providers.telnyx.TelnyxClient;
import com.elitale.coldbirds.coldcalling.storage.repository.ContactRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.SmsRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.SmsRepository.NewSmsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Handles outbound SMS via {@link TelnyxClient} and inbound SMS via the
 * AWS WebSocket relay ({@link SmsRelayClient}). Persists all messages to
 * {@link SmsRepository}.
 */
public final class SmsService {

    private static final Logger LOG = LoggerFactory.getLogger(SmsService.class);

    private final TelnyxClient          telnyx;
    private final SmsRelayClient        relay;
    private final SmsRepository         smsRepo;
    private final PhoneNumberRepository phoneNumberRepo;

    public SmsService(
            TelnyxClient          telnyx,
            SmsRelayClient        relay,
            SmsRepository         smsRepo,
            PhoneNumberRepository phoneNumberRepo) {
        this.telnyx          = Objects.requireNonNull(telnyx,          "telnyx must not be null");
        this.relay           = Objects.requireNonNull(relay,           "relay must not be null");
        this.smsRepo         = Objects.requireNonNull(smsRepo,         "smsRepo must not be null");
        this.phoneNumberRepo = Objects.requireNonNull(phoneNumberRepo, "phoneNumberRepo must not be null");
    }

    /**
     * Send an outbound SMS. Persists the message only on Telnyx API success.
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

        final Result<String> apiResult = telnyx.sendSms(from, to, body);
        return switch (apiResult) {
            case Result.Err<?> err -> {
                LOG.error("Telnyx sendSms failed: {}", err.message());
                yield Result.err(err.message());
            }
            case Result.Ok<?> ignored -> {
                final NewSmsMessage record = new NewSmsMessage(
                        CallDirection.OUTBOUND,
                        owned.get().id(),
                        Optional.empty(),  // contactId resolved at UI layer
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
     * Connect the WebSocket relay to receive inbound SMS.
     *
     * @param handler called for each inbound message (on the relay thread)
     */
    public void startReceiving(Consumer<DomainEvent.IncomingSms> handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        relay.connect(event -> {
            persistInbound(event);
            handler.accept(event);
        });
        LOG.info("SMS relay connected");
    }

    /** Disconnect the WebSocket relay. */
    public void stopReceiving() {
        relay.disconnect();
        LOG.info("SMS relay disconnected");
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
