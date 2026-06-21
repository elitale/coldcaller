package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.ContactRepository;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqliteContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteContactRepositoryTest {

    private DatabaseManager db;
    private ContactRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        db = DatabaseManager.inMemory();
        repo = new SqliteContactRepository(db.connection());
    }

    @Test
    void saveAndFindById() {
        var nc = new ContactRepository.NewContact(
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
        var c = ((Result.Ok<com.elitale.coldbirds.coldcalling.domain.model.Contact>) result).value();
        assertThat(c.firstName()).contains("Alice");
        assertThat(c.tags()).containsExactly("vip", "warm");
        assertThat(repo.findById(c.id())).isPresent();
    }

    @Test
    void findByPhoneReturnsContact() {
        var phone = new PhoneNumber("+14155550002");
        var nc = new ContactRepository.NewContact(
                Optional.of("Bob"), Optional.empty(), phone,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty()
        );
        repo.save(nc);
        assertThat(repo.findByPhone(phone)).isPresent();
        assertThat(repo.findByPhone(new PhoneNumber("+14155559999"))).isEmpty();
    }

    @Test
    void softDeleteHidesContact() {
        var nc = new ContactRepository.NewContact(
                Optional.of("Del"), Optional.empty(), new PhoneNumber("+14155550003"),
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty()
        );
        var c = ((Result.Ok<com.elitale.coldbirds.coldcalling.domain.model.Contact>) repo.save(nc)).value();
        assertThat(repo.delete(c.id()).isOk()).isTrue();
        assertThat(repo.findById(c.id())).isEmpty();
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void searchMatchesNameAndPhone() {
        var nc1 = new ContactRepository.NewContact(
                Optional.of("Charlie"), Optional.of("Brown"), new PhoneNumber("+14155550004"),
                Optional.of("Brown Inc"), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty()
        );
        var nc2 = new ContactRepository.NewContact(
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
