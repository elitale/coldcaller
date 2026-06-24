package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadColumn;
import com.elitale.coldbirds.coldcalling.domain.value.LeadFilter;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.Page;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadRepository;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqliteLeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteLeadRepositoryTest {

    private DatabaseManager db;
    private LeadRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        db = DatabaseManager.inMemory();
        repo = new SqliteLeadRepository(db.connection());
    }

    @Test
    void saveAndFindById() {
        var nc = new LeadRepository.NewLead(
                Optional.of("Alice"),
                Optional.of("Smith"),
                new PhoneNumber("+14155550001"),
                Optional.of("Acme Corp"),
                Optional.empty(),
                Optional.of("alice@acme.com"),
                List.of("vip", "warm"),
                Optional.empty()
        );
        var result = repo.save(nc);
        assertThat(result.isOk()).isTrue();
        var c = ((Result.Ok<com.elitale.coldbirds.coldcalling.domain.model.Lead>) result).value();
        assertThat(c.firstName()).contains("Alice");
        assertThat(c.tags()).containsExactly("vip", "warm");
        assertThat(repo.findById(c.id())).isPresent();
    }

    @Test
    void findByPhoneReturnsLead() {
        var phone = new PhoneNumber("+14155550002");
        var nc = new LeadRepository.NewLead(
                Optional.of("Bob"), Optional.empty(), phone,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty()
        );
        repo.save(nc);
        assertThat(repo.findByPhone(phone)).isPresent();
        assertThat(repo.findByPhone(new PhoneNumber("+14155559999"))).isEmpty();
    }

    @Test
    void softDeleteHidesLead() {
        var nc = new LeadRepository.NewLead(
                Optional.of("Del"), Optional.empty(), new PhoneNumber("+14155550003"),
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty()
        );
        var c = ((Result.Ok<com.elitale.coldbirds.coldcalling.domain.model.Lead>) repo.save(nc)).value();
        assertThat(repo.delete(c.id()).isOk()).isTrue();
        assertThat(repo.findById(c.id())).isEmpty();
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void searchMatchesNameAndPhone() {
        var nc1 = new LeadRepository.NewLead(
                Optional.of("Charlie"), Optional.of("Brown"), new PhoneNumber("+14155550004"),
                Optional.of("Brown Inc"), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty()
        );
        var nc2 = new LeadRepository.NewLead(
                Optional.of("Dana"), Optional.empty(), new PhoneNumber("+14155550005"),
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty()
        );
        repo.save(nc1);
        repo.save(nc2);
        assertThat(repo.search("Charlie")).hasSize(1);
        assertThat(repo.search("Brown")).hasSize(1);
        assertThat(repo.search("415")).hasSize(2);
    }

    // ── findPage: keyset pagination ─────────────────────────────────────

    @Test
    void findPageKeysetPagingWithSameTimestampTiebreak() {
        for (int i = 0; i < 5; i++) rawInsert("+1415555010" + i, 1000L, 0, "new", null, null);
        LeadFilter filter = LeadFilter.builder().limit(2).build();

        Page<Lead> p1 = repo.findPage(filter);
        assertThat(p1.total()).isEqualTo(5);
        assertThat(p1.hasNext()).isTrue();
        assertThat(p1.rows()).extracting(l -> l.id().value()).containsExactly(5L, 4L);

        Page<Lead> p2 = repo.findPage(filter.withCursor(p1.nextCursor().orElseThrow()));
        assertThat(p2.rows()).extracting(l -> l.id().value()).containsExactly(3L, 2L);
        assertThat(p2.hasNext()).isTrue();

        Page<Lead> p3 = repo.findPage(filter.withCursor(p2.nextCursor().orElseThrow()));
        assertThat(p3.rows()).extracting(l -> l.id().value()).containsExactly(1L);
        assertThat(p3.hasNext()).isFalse();
    }

    @Test
    void duplicateLivePhoneIsRejectedByPartialUniqueIndex() {
        LeadRepository.NewLead nc = newLead("+14155550200");
        assertThat(repo.save(nc).isOk()).isTrue();
        assertThat(repo.save(nc).isErr()).isTrue();
    }

    // ── findPage: filter facets ───────────────────────────────────────

    @Test
    void findPageSearchMatchesAcrossColumns() {
        repo.save(newLead("+14155550301", "Alice", "Smith", "Acme", "alice@acme.com"));
        repo.save(newLead("+14155550302", "Bob", "Jones", "Globex", "bob@globex.com"));
        assertThat(repo.findPage(LeadFilter.builder().search("acme").build()).total()).isEqualTo(1);
        assertThat(repo.findPage(LeadFilter.builder().search("jones").build()).total()).isEqualTo(1);
        assertThat(repo.findPage(LeadFilter.builder().search("415").build()).total()).isEqualTo(2);
    }

    @Test
    void findPageColumnFilter() {
        repo.save(newLead("+14155550401", "Alice", "Smith", "Acme", null));
        repo.save(newLead("+14155550402", "Alicia", "Jones", "Globex", null));
        Page<Lead> page = repo.findPage(
                LeadFilter.builder().column(LeadColumn.LAST_NAME, "smith").build());
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.rows().get(0).firstName()).contains("Alice");
    }

    @Test
    void findPageDncTriState() {
        rawInsert("+14155550601", 1000L, 0, "new", null, null);
        rawInsert("+14155550602", 1001L, 1, "dnc", null, null);
        assertThat(repo.findPage(filter(LeadFilter.DncFilter.ANY)).total()).isEqualTo(2);
        assertThat(repo.findPage(filter(LeadFilter.DncFilter.ONLY)).total()).isEqualTo(1);
        assertThat(repo.findPage(filter(LeadFilter.DncFilter.EXCLUDE)).total()).isEqualTo(1);
        assertThat(repo.findPage(filter(LeadFilter.DncFilter.ONLY)).rows().get(0).dnc()).isTrue();
    }

    @Test
    void findPageStatusFilter() {
        rawInsert("+14155550701", 1000L, 0, "new", null, null);
        rawInsert("+14155550702", 1001L, 0, "interested", null, null);
        rawInsert("+14155550703", 1002L, 0, "callback", null, null);
        Page<Lead> page = repo.findPage(LeadFilter.builder()
                .statuses(Set.of(LeadStatus.INTERESTED, LeadStatus.CALLBACK)).build());
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.rows()).allSatisfy(l ->
                assertThat(l.leadStatus()).isIn(LeadStatus.INTERESTED, LeadStatus.CALLBACK));
    }

    @Test
    void findPageTagFilter() {
        rawInsert("+14155550801", 1000L, 0, "new", "[\"vip\",\"warm\"]", null);
        rawInsert("+14155550802", 1001L, 0, "new", "[\"cold\"]", null);
        assertThat(repo.findPage(LeadFilter.builder().tags(Set.of("vip")).build()).total()).isEqualTo(1);
        assertThat(repo.findPage(LeadFilter.builder().tags(Set.of("cold")).build()).total()).isEqualTo(1);
        assertThat(repo.findPage(LeadFilter.builder().tags(Set.of("nope")).build()).total()).isZero();
    }

    @Test
    void findPageCustomFieldFilter() {
        rawInsert("+14155550901", 1000L, 0, "new", null, "{\"Source\":\"Apollo\",\"Timezone\":\"PST\"}");
        rawInsert("+14155550902", 1001L, 0, "new", null, "{\"Source\":\"ZoomInfo\"}");
        assertThat(repo.findPage(LeadFilter.builder().customField("Source", "apollo").build()).total()).isEqualTo(1);
        assertThat(repo.findPage(LeadFilter.builder().customField("Timezone", "pst").build()).total()).isEqualTo(1);
    }

    @Test
    void findPageListMembershipFilter() {
        long a = rawInsert("+14155551201", 1000L, 0, "new", null, null);
        rawInsert("+14155551202", 1001L, 0, "new", null, null);
        long listId = rawInsertList("VIPs");
        addToList(listId, a, 0);
        Page<Lead> page = repo.findPage(
                LeadFilter.builder().listId(new CallListId(listId)).build());
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.rows().get(0).id().value()).isEqualTo(a);
    }

    // ── Dynamic columns + tag facet sources ──────────────────────────────

    @Test
    void customFieldKeysReturnsDistinctSortedKeys() {
        rawInsert("+14155551001", 1000L, 0, "new", null, "{\"Source\":\"Apollo\",\"Timezone\":\"PST\"}");
        rawInsert("+14155551002", 1001L, 0, "new", null, "{\"Source\":\"ZoomInfo\",\"Priority\":\"hot\"}");
        assertThat(repo.customFieldKeys(Optional.empty()))
                .containsExactly("Priority", "Source", "Timezone");
    }

    @Test
    void distinctTagsReturnsSortedUniqueTags() {
        rawInsert("+14155551101", 1000L, 0, "new", "[\"vip\",\"warm\"]", null);
        rawInsert("+14155551102", 1001L, 0, "new", "[\"warm\",\"cold\"]", null);
        assertThat(repo.distinctTags()).containsExactly("cold", "vip", "warm");
    }

    // ── bulk soft delete ─────────────────────────────────────────────────

    @Test
    void bulkSoftDeleteHidesOnlyTheGivenLiveLeads() {
        long a = rawInsert("+14155551301", 1000L, 0, "new", null, null);
        long b = rawInsert("+14155551302", 1001L, 0, "new", null, null);
        long c = rawInsert("+14155551303", 1002L, 0, "new", null, null);

        int deleted = repo.bulkSoftDelete(List.of(new LeadId(a), new LeadId(b)));

        assertThat(deleted).isEqualTo(2);
        assertThat(repo.findById(new LeadId(a))).isEmpty();
        assertThat(repo.findById(new LeadId(b))).isEmpty();
        assertThat(repo.findById(new LeadId(c))).isPresent();
    }

    @Test
    void bulkSoftDeleteIgnoresAlreadyDeletedAndEmptyInput() {
        long a = rawInsert("+14155551401", 1000L, 0, "new", null, null);
        repo.delete(new LeadId(a));
        assertThat(repo.bulkSoftDelete(List.of(new LeadId(a)))).isZero();
        assertThat(repo.bulkSoftDelete(List.of())).isZero();
    }

    @Test
    void bulkSetStatusUpdatesOnlyGivenLeads() {
        long a = rawInsert("+14155551501", 1000L, 0, "new", null, null);
        long b = rawInsert("+14155551502", 1001L, 0, "new", null, null);
        long c = rawInsert("+14155551503", 1002L, 0, "new", null, null);

        int updated = repo.bulkSetStatus(List.of(new LeadId(a), new LeadId(b)), LeadStatus.INTERESTED);

        assertThat(updated).isEqualTo(2);
        assertThat(repo.findById(new LeadId(a)).orElseThrow().leadStatus()).isEqualTo(LeadStatus.INTERESTED);
        assertThat(repo.findById(new LeadId(b)).orElseThrow().leadStatus()).isEqualTo(LeadStatus.INTERESTED);
        assertThat(repo.findById(new LeadId(c)).orElseThrow().leadStatus()).isEqualTo(LeadStatus.NEW);
    }

    @Test
    void bulkSetDncTogglesFlag() {
        long a = rawInsert("+14155551601", 1000L, 0, "new", null, null);
        long b = rawInsert("+14155551602", 1001L, 0, "new", null, null);

        assertThat(repo.bulkSetDnc(List.of(new LeadId(a), new LeadId(b)), true)).isEqualTo(2);
        assertThat(repo.findById(new LeadId(a)).orElseThrow().dnc()).isTrue();
        assertThat(repo.bulkSetDnc(List.of(new LeadId(a)), false)).isEqualTo(1);
        assertThat(repo.findById(new LeadId(a)).orElseThrow().dnc()).isFalse();
    }

    // ── helpers ─────────────────────────────────────────────────

    private static LeadFilter filter(LeadFilter.DncFilter dnc) {
        return LeadFilter.builder().dnc(dnc).build();
    }

    private static LeadRepository.NewLead newLead(String phone) {
        return new LeadRepository.NewLead(
                Optional.empty(), Optional.empty(), new PhoneNumber(phone),
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty());
    }

    private static LeadRepository.NewLead newLead(String phone, String first, String last,
                                                  String company, String email) {
        return new LeadRepository.NewLead(
                Optional.ofNullable(first), Optional.ofNullable(last), new PhoneNumber(phone),
                Optional.ofNullable(company), Optional.empty(), Optional.ofNullable(email),
                List.of(), Optional.empty());
    }

    private long rawInsert(String phone, long createdAt, int dnc, String status,
                           String tagsJson, String customJson) {
        String sql = """
                INSERT INTO leads (phone, dnc, lead_status, tags, custom_fields, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?)""";
        try (var st = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            st.setString(1, phone);
            st.setInt(2, dnc);
            st.setString(3, status);
            st.setString(4, tagsJson);
            st.setString(5, customJson);
            st.setLong(6, createdAt);
            st.setLong(7, createdAt);
            st.executeUpdate();
            try (var keys = st.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long rawInsertList(String name) {
        String sql = "INSERT INTO call_lists (name, created_at, updated_at) VALUES (?,?,?)";
        try (var st = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            st.setString(1, name);
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

    private void addToList(long listId, long leadId, int position) {
        String sql = """
                INSERT INTO call_list_leads (list_id, lead_id, position, created_at, updated_at)
                VALUES (?,?,?,?,?)""";
        try (var st = db.connection().prepareStatement(sql)) {
            st.setLong(1, listId);
            st.setLong(2, leadId);
            st.setInt(3, position);
            st.setLong(4, 1000L);
            st.setLong(5, 1000L);
            st.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
