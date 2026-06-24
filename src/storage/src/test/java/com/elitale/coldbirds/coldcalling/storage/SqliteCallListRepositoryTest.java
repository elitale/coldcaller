package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqliteCallListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteCallListRepositoryTest {

    private DatabaseManager db;
    private CallListRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        db = DatabaseManager.inMemory();
        repo = new SqliteCallListRepository(db.connection());
    }

    @Test
    void renameChangesNameAndRejectsBlank() {
        CallListId id = savedList("Cold Leads");
        Result<CallList> renamed = repo.rename(id, "Warm Leads");
        assertThat(renamed.isOk()).isTrue();
        assertThat(repo.findById(id).orElseThrow().name()).isEqualTo("Warm Leads");
        assertThat(repo.rename(id, "  ").isErr()).isTrue();
        assertThat(repo.rename(new CallListId(9999), "X").isErr()).isTrue();
    }

    @Test
    void countLeadsCountsOnlyLiveMembers() {
        CallListId list = savedList("VIPs");
        long a = rawLead("+14155558001");
        long b = rawLead("+14155558002");
        repo.addLeads(list, List.of(new LeadId(a), new LeadId(b)));
        assertThat(repo.countLeads(list)).isEqualTo(2);

        softDeleteLead(a);
        assertThat(repo.countLeads(list)).isEqualTo(1);
    }

    @Test
    void addLeadsAppendsAndSkipsDuplicates() {
        CallListId list = savedList("List A");
        long a = rawLead("+14155558101");
        long b = rawLead("+14155558102");

        assertThat(repo.addLeads(list, List.of(new LeadId(a), new LeadId(b)))).isEqualTo(2);
        // re-adding a is ignored; only b's sibling c is new
        long c = rawLead("+14155558103");
        assertThat(repo.addLeads(list, List.of(new LeadId(a), new LeadId(c)))).isEqualTo(1);
        assertThat(repo.countLeads(list)).isEqualTo(3);
    }

    @Test
    void addLeadsEmptyIsNoOp() {
        CallListId list = savedList("Empty");
        assertThat(repo.addLeads(list, List.of())).isZero();
        assertThat(repo.countLeads(list)).isZero();
    }

    @Test
    void removeLeadDeletesMembership() {
        CallListId list = savedList("List B");
        long a = rawLead("+14155558201");
        repo.addLeads(list, List.of(new LeadId(a)));
        assertThat(repo.removeLead(list, new LeadId(a))).isTrue();
        assertThat(repo.countLeads(list)).isZero();
        assertThat(repo.removeLead(list, new LeadId(a))).isFalse();
    }

    // ── helpers ─────────────────────────────────────────────────

    private CallListId savedList(String name) {
        Result<CallList> r = repo.save(new CallListRepository.NewCallList(name, Optional.empty()));
        return ((Result.Ok<CallList>) r).value().id();
    }

    private long rawLead(String phone) {
        String sql = """
                INSERT INTO leads (phone, dnc, lead_status, created_at, updated_at)
                VALUES (?,0,'new',?,?)""";
        try (var st = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            st.setString(1, phone);
            st.setLong(2, 1000L);
            st.setLong(3, 1000L);
            st.executeUpdate();
            try (var keys = st.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void softDeleteLead(long id) {
        try (var st = db.connection().prepareStatement(
                "UPDATE leads SET deleted_at=? WHERE id=?")) {
            st.setLong(1, 2000L);
            st.setLong(2, id);
            st.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
