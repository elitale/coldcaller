package com.elitale.coldbirds.coldcalling.storage.repository;

import java.util.Map;
import java.util.Optional;

/** Key-value settings store backed by the settings table. */
public interface SettingsRepository {

    Optional<String> get(String key);

    /** Upserts the key. */
    void set(String key, String value);

    Map<String, String> getAll();

    void delete(String key);
}
