package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadRepository;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqliteLeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
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
}
