package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.AreaCode;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.NumberReputation;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.CallRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqliteCallRepository;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqlitePhoneNumberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SqliteCallRepositoryNullContactTest {

    private CallRepository repo;
    private PhoneNumberId phoneNumberId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager db = DatabaseManager.inMemory();
        PhoneNumberRepository phoneRepo = new SqlitePhoneNumberRepository(db.connection());
        OwnedNumber owned = ((Result.Ok<OwnedNumber>) phoneRepo.save(
                new PhoneNumberRepository.NewOwnedNumber(
                        new PhoneNumber("+14155550000"),
                        Optional.empty(),
                        new AreaCode("415"),
                        "twilio",
                        new NumberReputation.Clean()))).value();
        phoneNumberId = owned.id();
        repo = new SqliteCallRepository(db.connection());
    }

    @Test
    void findByRemoteNumber_withNullContactIdAndTimestamps_doesNotThrowAndMapsEmpties() {
        PhoneNumber remote = new PhoneNumber("+14155559999");
        repo.save(new CallRepository.NewCall(
                CallDirection.OUTBOUND,
                phoneNumberId,
                Optional.empty(), // no linked contact → contact_id is NULL
                remote,
                Optional.empty(), // no disposition
                Instant.now(),
                Optional.empty(), // answered_at NULL
                Optional.empty(), // ended_at NULL
                Optional.empty(), // duration_ms NULL
                Optional.empty(),
                Optional.empty()));

        assertThatCode(() -> repo.findByRemoteNumber(remote)).doesNotThrowAnyException();
        var calls = repo.findByRemoteNumber(remote);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).contactId()).isEmpty();
        assertThat(calls.get(0).answeredAt()).isEmpty();
        assertThat(calls.get(0).endedAt()).isEmpty();
        assertThat(calls.get(0).durationMs()).isEmpty();
    }
}
