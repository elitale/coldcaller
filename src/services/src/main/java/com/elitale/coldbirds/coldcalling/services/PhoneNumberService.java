package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient;
import com.elitale.coldbirds.coldcalling.providers.twilio.dto.TwilioNumberData;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository.NewOwnedNumber;
import com.elitale.coldbirds.coldcalling.storage.repository.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages owned phone numbers: reads from SQLite, syncs from Twilio API,
 * and resolves the default number from {@link SettingsRepository}.
 */
public final class PhoneNumberService {

    private static final Logger LOG = LoggerFactory.getLogger(PhoneNumberService.class);

    static final String DEFAULT_NUMBER_KEY = "default_number";

    private final PhoneNumberRepository repo;
    private final TwilioClient          twilio;
    private final SettingsRepository    settings;

    public PhoneNumberService(
            PhoneNumberRepository repo,
            TwilioClient          twilio,
            SettingsRepository    settings) {
        this.repo     = Objects.requireNonNull(repo,     "repo must not be null");
        this.twilio   = Objects.requireNonNull(twilio,   "twilio must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    /** Return all active owned numbers from local storage. */
    public List<OwnedNumber> listOwned() {
        return repo.findAllActive();
    }

    /**
     * Return the user's configured default number, if set and present in storage.
     */
    public Optional<OwnedNumber> getDefault() {
        return settings.get(DEFAULT_NUMBER_KEY)
                .flatMap(raw -> {
                    try {
                        return repo.findByNumber(new PhoneNumber(raw));
                    } catch (IllegalArgumentException e) {
                        LOG.warn("Default number setting '{}' is not valid E.164", raw);
                        return Optional.empty();
                    }
                });
    }

    /**
     * Set the default number (persisted to settings).
     *
     * @param number owned number to use as default
     */
    public void setDefault(PhoneNumber number) {
        settings.set(DEFAULT_NUMBER_KEY, Objects.requireNonNull(number).value());
    }

    /**
     * Fetch all phone numbers from the Twilio API and upsert any new ones into
     * local storage. Numbers already present are skipped.
     *
     * @return number of newly inserted numbers, or an error
     */
    public Result<Integer> fetchAndSync() {
        final Result<List<TwilioNumberData>> apiResult = twilio.listPhoneNumbers();
        return switch (apiResult) {
            case Result.Err<?> err -> {
                LOG.error("Twilio listPhoneNumbers failed: {}", err.message());
                yield Result.err(err.message());
            }
            case Result.Ok<List<TwilioNumberData>> ok -> {
                int inserted = 0;
                for (final TwilioNumberData data : ok.value()) {
                    inserted += syncNumber(data);
                }
                LOG.info("fetchAndSync complete: {} new number(s) inserted", inserted);
                yield Result.ok(inserted);
            }
        };
    }

    /**
     * Persist a user-chosen subset of Twilio numbers into local storage.
     * Numbers already present, or with an invalid E.164 value, are skipped.
     *
     * @param numbers the selected Twilio numbers to save
     * @return number of newly inserted numbers
     */
    public Result<Integer> saveSelected(List<TwilioNumberData> numbers) {
        Objects.requireNonNull(numbers, "numbers must not be null");
        int inserted = 0;
        for (final TwilioNumberData data : numbers) {
            inserted += syncNumber(data);
        }
        LOG.info("saveSelected complete: {} new number(s) inserted", inserted);
        return Result.ok(inserted);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private int syncNumber(TwilioNumberData data) {
        PhoneNumber number;
        try {
            number = new PhoneNumber(data.phoneNumber());
        } catch (IllegalArgumentException e) {
            LOG.warn("Twilio returned invalid phone number '{}': {}", data.phoneNumber(), e.getMessage());
            return 0;
        }

        if (repo.findByNumber(number).isPresent()) {
            return 0; // already exists
        }

        final String areaCodeStr = number.value().length() >= 5
                ? number.value().substring(2, 5)
                : "000";

        final NewOwnedNumber newNumber = new NewOwnedNumber(
                number,
                Optional.empty(),
                new AreaCode(areaCodeStr),
                "twilio",
                new NumberReputation.Clean()
        );

        final var saved = repo.save(newNumber);
        if (saved instanceof Result.Err<?> err) {
            LOG.error("Failed to save synced number {}: {}", number.value(), err.message());
            return 0;
        }
        return 1;
    }
}
