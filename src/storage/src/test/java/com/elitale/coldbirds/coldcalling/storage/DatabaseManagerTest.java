package com.elitale.coldbirds.coldcalling.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class DatabaseManagerTest {

    @Test
    void inMemoryCreatesAndMigrates() throws Exception {
        try (var db = DatabaseManager.inMemory()) {
            assertThat(db.connection()).isNotNull();
            assertThat(db.connection().isClosed()).isFalse();
            // Verify migrations ran — contacts table should exist
            try (var stmt = db.connection().createStatement();
                 var rs = stmt.executeQuery("SELECT count(*) FROM contacts")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isZero();
            }
        }
    }

    @Test
    void fileBasedCreatesDb(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        try (var db = new DatabaseManager(dbPath)) {
            assertThat(db.connection().isClosed()).isFalse();
            try (var stmt = db.connection().createStatement();
                 var rs = stmt.executeQuery("PRAGMA journal_mode")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("wal");
            }
        }
        assertThat(dbPath).exists();
    }

    @Test
    void defaultPathIsResolved() {
        Path p = DatabaseManager.defaultPath();
        assertThat(p.toString()).contains("coldcalling");
        assertThat(p.toString()).endsWith("data.db");
    }
}
