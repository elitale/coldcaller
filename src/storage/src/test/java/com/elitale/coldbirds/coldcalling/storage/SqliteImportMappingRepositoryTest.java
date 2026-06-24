package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.storage.repository.ImportMappingRepository.ImportMapping;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqliteImportMappingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class SqliteImportMappingRepositoryTest {

    private DatabaseManager db;
    private SqliteImportMappingRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        db = DatabaseManager.inMemory();
        repo = new SqliteImportMappingRepository(db.connection());
    }

    @AfterEach
    void tearDown() throws java.io.IOException {
        db.close();
    }

    @Test
    void saveInsertsThenFindBySignatureReturnsIt() {
        ImportMapping saved = repo.save(mapping("Apollo", "company,email,mobile phone"));

        assertThat(saved.id()).isPresent();
        Optional<ImportMapping> found = repo.findByHeaderSignature("company,email,mobile phone");
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("Apollo");
    }

    @Test
    void saveUpsertsOnHeaderSignatureKeepingOneRow() {
        ImportMapping first = repo.save(mapping("Apollo", "company,email,mobile phone"));
        ImportMapping second = repo.save(mapping("Apollo v2", "company,email,mobile phone"));

        assertThat(second.id()).contains(first.id().orElseThrow());
        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findByHeaderSignature("company,email,mobile phone").orElseThrow().name())
                .isEqualTo("Apollo v2");
    }

    @Test
    void deleteRemovesTheTemplate() {
        ImportMapping saved = repo.save(mapping("ZoomInfo", "direct phone,email"));
        repo.delete(saved.id().orElseThrow());
        assertThat(repo.findByHeaderSignature("direct phone,email")).isEmpty();
    }

    @Test
    void unknownSignatureIsEmpty() {
        assertThat(repo.findByHeaderSignature("nope")).isEmpty();
    }

    private static ImportMapping mapping(String name, String signature) {
        Instant now = Instant.now();
        return new ImportMapping(
                Optional.empty(), name, signature, "{\"phone\":\"Mobile Phone\"}",
                Optional.of("US"), Optional.empty(), now, now);
    }
}
