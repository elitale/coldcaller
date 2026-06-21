package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.providers.telnyx.TelnyxClient;
import com.elitale.coldbirds.coldcalling.providers.telnyx.dto.TelnyxNumberData;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository.NewOwnedNumber;
import com.elitale.coldbirds.coldcalling.storage.repository.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages owned phone numbers: reads from SQLite, syncs from Telnyx API,
 * and resolves the default number from {@link SettingsRepository}.
 */
public final class PhoneNumberService {

    private static final Logger LOG = LoggerFactory.getLogger(PhoneNumberService.class);

    static final String DEFAULT_NUMBER_KEY = "default_number";

    private final PhoneNumberRepository repo;
    private final TelnyxClient          telnyx;
    private final SettingsRepository    settings;

    public PhoneNumberService(
            PhoneNumberRepository repo,
            TelnyxClient          telnyx,
            SettingsRepository    settings) {
        this.repo     = Objects.requireNonNull(repo,     "repo must not be null");
        this.telnyx   = Objects.requireNonNull(telnyx,   "telnyx must not be null");
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
     * Fetch all phone numbers from the Telnyx API and upsert any new ones into
     * local storage. Numbers already present are skipped.
     *
     * @return number of newly inserted numbers, or an error
     */
    public Result<Integer> fetchAndSync() {
        final Result<List<TelnyxNumberData>> apiResult = telnyx.listPhoneNumbers();
        return switch (apiResult) {
            case Result.Err<?> err -> {
                LOG.error("Telnyx listPhoneNumbers failed: {}", err.message());
                yield Result.err(err.message());
            }
            case Result.Ok<List<TelnyxNumberData>> ok -> {
                int inserted = 0;
                for (final TelnyxNumberData data : ok.value()) {
                    inserted += syncNumber(data);
                }
                LOG.info("fetchAndSync complete: {} new number(s) inserted", inserted);
                yield Result.ok(inserted);
            }
        };
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private int syncNumber(TelnyxNumberData data) {
        PhoneNumber number;
        try {
            number = new PhoneNumber(data.phoneNumber());
        } catch (IllegalArgumentException e) {
            LOG.warn("Telnyx returned invalid phone number '{}': {}", data.phoneNumber(), e.getMessage());
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
                "telnyx",
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
