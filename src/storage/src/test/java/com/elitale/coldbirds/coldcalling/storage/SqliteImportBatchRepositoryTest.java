package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.storage.repository.ImportBatchRepository.ImportBatch;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadImportRepository.UpsertRow;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqliteImportBatchRepository;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqliteLeadImportRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class SqliteImportBatchRepositoryTest {

    private DatabaseManager db;
    private SqliteImportBatchRepository repo;
    private SqliteLeadImportRepository leads;

    @BeforeEach
    void setUp() throws SQLException {
        db = DatabaseManager.inMemory();
        repo = new SqliteImportBatchRepository(db.connection());
        leads = new SqliteLeadImportRepository(db.connection());
    }

    @AfterEach
    void tearDown() throws java.io.IOException {
        db.close();
    }

    @Test
    void recordPersistsAndRecentBatchesReturnsNewestFirst() {
        repo.record(batch("b1", 1700000000000L, 10));
        repo.record(batch("b2", 1700000100000L, 20));

        List<ImportBatch> recent = repo.recentBatches(10);

        assertThat(recent).extracting(ImportBatch::id).containsExactly("b2", "b1");
        assertThat(recent.get(0).created()).isEqualTo(20);
    }

    @Test
    void undoSoftDeletesOnlyTheBatchCreatedLeads() {
        leads.bulkUpsert(List.of(row("+14155550501")), "keep");
        leads.bulkUpsert(List.of(row("+14155550502"), row("+14155550503")), "undo-me");

        int removed = repo.undo("undo-me");

        assertThat(removed).isEqualTo(2);
        assertThat(liveCount()).isEqualTo(1);
    }

    @Test
    void undoUnknownBatchRemovesNothing() {
        leads.bulkUpsert(List.of(row("+14155550600")), "real");
        assertThat(repo.undo("ghost")).isZero();
        assertThat(liveCount()).isEqualTo(1);
    }

    private static ImportBatch batch(String id, long at, int created) {
        return new ImportBatch(id, "leads.csv", Optional.of("US"),
                created, 0, 0, 0, Instant.ofEpochMilli(at));
    }

    private static UpsertRow row(String phone) {
        return new UpsertRow(
                Optional.empty(), Optional.empty(), new PhoneNumber(phone),
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Map.of(), LeadStatus.NEW);
    }

    private int liveCount() {
        try (Statement stmt = db.connection().createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM leads WHERE deleted_at IS NULL")) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
