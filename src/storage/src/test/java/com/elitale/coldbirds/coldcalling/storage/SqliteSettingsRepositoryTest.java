package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.storage.repository.SettingsRepository;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqliteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteSettingsRepositoryTest {

    private DatabaseManager db;
    private SettingsRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        db = DatabaseManager.inMemory();
        repo = new SqliteSettingsRepository(db.connection());
    }

    @Test
    void setAndGet() {
        repo.set("default.number", "+14155550001");
        assertThat(repo.get("default.number")).contains("+14155550001");
    }

    @Test
    void upsertOverwrites() {
        repo.set("theme", "light");
        repo.set("theme", "dark");
        assertThat(repo.get("theme")).contains("dark");
    }

    @Test
    void getMissingKeyReturnsEmpty() {
        assertThat(repo.get("nonexistent")).isEmpty();
    }

    @Test
    void deleteRemovesKey() {
        repo.set("to.delete", "value");
        repo.delete("to.delete");
        assertThat(repo.get("to.delete")).isEmpty();
    }

    @Test
    void getAllReturnsAllPairs() {
        repo.set("k1", "v1");
        repo.set("k2", "v2");
        var all = repo.getAll();
        assertThat(all).containsEntry("k1", "v1").containsEntry("k2", "v2");
    }
}
