package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadImportRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadImportRepository.UpsertResult;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadImportRepository.UpsertRow;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqliteLeadImportRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

final class SqliteLeadImportRepositoryTest {

    private DatabaseManager db;
    private SqliteLeadImportRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        db = DatabaseManager.inMemory();
        repo = new SqliteLeadImportRepository(db.connection());
    }

    @AfterEach
    void tearDown() throws java.io.IOException {
        db.close();
    }

    @Test
    void bulkUpsertCreatesNewLeadsAndCountsThem() {
        UpsertResult result = repo.bulkUpsert(
                List.of(row("+14155550101"), row("+14155550102")), "batch-1");

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.updated()).isZero();
        assertThat(liveCount()).isEqualTo(2);
    }

    @Test
    void bulkUpsertUpdatesExistingLeadNonDestructively() {
        repo.bulkUpsert(List.of(rowWith("+14155550200", "Ann", "Acme")), "batch-1");

        // Re-import the same phone: blank company must NOT wipe the existing one.
        UpsertResult result = repo.bulkUpsert(
                List.of(rowWith("+14155550200", "Annabel", null)), "batch-2");

        assertThat(result.created()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(liveCount()).isEqualTo(1);
        assertThat(companyOf("+14155550200")).isEqualTo("Acme");
        assertThat(firstNameOf("+14155550200")).isEqualTo("Annabel");
    }

    @Test
    void bulkUpsertEmptyIsNoOp() {
        assertThat(repo.bulkUpsert(List.of(), "batch-1")).isEqualTo(new UpsertResult(0, 0));
    }

    @Test
    void findDncPhonesReturnsOnlyFlaggedLivePhones() throws SQLException {
        repo.bulkUpsert(List.of(row("+14155550301"), row("+14155550302")), "b");
        flagDnc("+14155550301");

        Set<String> dnc = repo.findDncPhones(Set.of("+14155550301", "+14155550302", "+14155559999"));

        assertThat(dnc).containsExactly("+14155550301");
    }

    @Test
    void findDncPhonesEmptyInputIsEmpty() {
        assertThat(repo.findDncPhones(Set.of())).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static UpsertRow row(String phone) {
        return rowWith(phone, null, null);
    }

    private static UpsertRow rowWith(String phone, String first, String company) {
        return new UpsertRow(
                Optional.ofNullable(first),
                Optional.empty(),
                new PhoneNumber(phone),
                Optional.ofNullable(company),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Map.of(),
                LeadStatus.NEW);
    }

    private int liveCount() {
        return scalar("SELECT COUNT(*) FROM leads WHERE deleted_at IS NULL");
    }

    private int scalar(String sql) {
        try (Statement stmt = db.connection().createStatement();
             var rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String companyOf(String phone) {
        return stringCol("company", phone);
    }

    private String firstNameOf(String phone) {
        return stringCol("first_name", phone);
    }

    private String stringCol(String col, String phone) {
        try (var stmt = db.connection().prepareStatement(
                "SELECT " + col + " FROM leads WHERE phone=? AND deleted_at IS NULL")) {
            stmt.setString(1, phone);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void flagDnc(String phone) throws SQLException {
        try (var stmt = db.connection().prepareStatement("UPDATE leads SET dnc=1 WHERE phone=?")) {
            stmt.setString(1, phone);
            stmt.executeUpdate();
        }
    }
}
