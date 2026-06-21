package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.AreaCode;
import com.elitale.coldbirds.coldcalling.domain.value.NumberReputation;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqlitePhoneNumberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class SqlitePhoneNumberRepositoryTest {

    private DatabaseManager db;
    private PhoneNumberRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        db = DatabaseManager.inMemory();
        repo = new SqlitePhoneNumberRepository(db.connection());
    }

    @Test
    void saveAndFindById() {
        var n = new PhoneNumberRepository.NewOwnedNumber(
                new PhoneNumber("+14155551234"),
                Optional.of("Sales Line"),
                new AreaCode("415"),
                "telnyx",
                new NumberReputation.Clean()
        );
        Result<com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber> result = repo.save(n);
        assertThat(result.isOk()).isTrue();
        var saved = ((Result.Ok<com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber>) result).value();
        assertThat(saved.number().value()).isEqualTo("+14155551234");
        assertThat(saved.friendlyName()).contains("Sales Line");
        assertThat(saved.active()).isTrue();
        assertThat(repo.findById(saved.id())).isPresent();
    }

    @Test
    void findAllActiveFiltersInactive() {
        var n1 = new PhoneNumberRepository.NewOwnedNumber(
                new PhoneNumber("+14155551111"),
                Optional.empty(),
                new AreaCode("415"),
                "telnyx",
                new NumberReputation.Clean()
        );
        var n2 = new PhoneNumberRepository.NewOwnedNumber(
                new PhoneNumber("+14155552222"),
                Optional.empty(),
                new AreaCode("415"),
                "telnyx",
                new NumberReputation.Warning("spam")
        );
        var saved1 = ((Result.Ok<com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber>) repo.save(n1)).value();
        repo.save(n2);
        // Deactivate saved1
        var deactivated = new com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber(
                saved1.id(), saved1.number(), saved1.friendlyName(), saved1.areaCode(),
                saved1.provider(), saved1.reputation(), saved1.dailyCalls(), false,
                saved1.createdAt(), saved1.updatedAt()
        );
        repo.update(deactivated);
        assertThat(repo.findAllActive()).hasSize(1);
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void deleteRemovesNumber() {
        var n = new PhoneNumberRepository.NewOwnedNumber(
                new PhoneNumber("+14155553333"),
                Optional.empty(),
                new AreaCode("415"),
                "telnyx",
                new NumberReputation.Clean()
        );
        var saved = ((Result.Ok<com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber>) repo.save(n)).value();
        assertThat(repo.delete(saved.id()).isOk()).isTrue();
        assertThat(repo.findById(saved.id())).isEmpty();
    }
}
