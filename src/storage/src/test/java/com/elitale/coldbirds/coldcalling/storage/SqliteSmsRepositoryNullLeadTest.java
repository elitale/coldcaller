package com.elitale.coldbirds.coldcalling.storage;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.AreaCode;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.NumberReputation;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.SmsRepository;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqlitePhoneNumberRepository;
import com.elitale.coldbirds.coldcalling.storage.sqlite.SqliteSmsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SqliteSmsRepositoryNullLeadTest {

    private SmsRepository repo;
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
        repo = new SqliteSmsRepository(db.connection());
    }

    @Test
    void findByRemoteNumber_withNullLeadId_doesNotThrowAndReturnsMessage() {
        PhoneNumber remote = new PhoneNumber("+14155559999");
        repo.save(new SmsRepository.NewSmsMessage(
                CallDirection.INBOUND,
                phoneNumberId,
                Optional.empty(), // no linked lead → lead_id is NULL
                remote,
                "hello there",
                new SmsStatus.Delivered(),
                Instant.now()));

        assertThatCode(() -> repo.findByRemoteNumber(remote)).doesNotThrowAnyException();
        var thread = repo.findByRemoteNumber(remote);
        assertThat(thread).hasSize(1);
        assertThat(thread.get(0).leadId()).isEmpty();
        assertThat(thread.get(0).body()).isEqualTo("hello there");
    }
}
