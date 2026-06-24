package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.AreaCode;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
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

class SqliteCallRepositoryCallbacksTest {

    private CallRepository repo;
    private PhoneNumberId phoneNumberId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager db = DatabaseManager.inMemory();
        PhoneNumberRepository phoneRepo = new SqlitePhoneNumberRepository(db.connection());
        OwnedNumber owned = ((Result.Ok<OwnedNumber>) phoneRepo.save(
                new PhoneNumberRepository.NewOwnedNumber(
                        new PhoneNumber("+14155550000"), Optional.empty(),
                        new AreaCode("415"), "twilio", new NumberReputation.Clean()))).value();
        phoneNumberId = owned.id();
        repo = new SqliteCallRepository(db.connection());
    }

    private void saveCall(String remote, Optional<CallDisposition> disposition) {
        repo.save(new CallRepository.NewCall(
                CallDirection.OUTBOUND, phoneNumberId, Optional.empty(), new PhoneNumber(remote),
                disposition, Instant.now(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void findCallbacks_returnsOnlyCallbacksWithParsedDueDate() {
        Instant due = Instant.parse("2026-06-25T09:00:00Z");
        saveCall("+14155559001", Optional.of(new CallDisposition.Callback(due)));
        saveCall("+14155559002", Optional.of(new CallDisposition.Interested()));
        saveCall("+14155559003", Optional.empty());

        var callbacks = repo.findCallbacks();

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks.get(0).remoteNumber().value()).isEqualTo("+14155559001");
        assertThat(callbacks.get(0).disposition()).get().isInstanceOf(CallDisposition.Callback.class);
        assertThat(((CallDisposition.Callback) callbacks.get(0).disposition().orElseThrow()).scheduledAt())
                .isEqualTo(due);
    }

    @Test
    void findCallbacks_emptyWhenNone() {
        saveCall("+14155559002", Optional.of(new CallDisposition.Interested()));
        assertThat(repo.findCallbacks()).isEmpty();
    }
}
