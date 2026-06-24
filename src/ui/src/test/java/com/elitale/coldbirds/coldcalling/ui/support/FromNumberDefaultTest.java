package com.elitale.coldbirds.coldcalling.ui.support;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.AreaCode;
import com.elitale.coldbirds.coldcalling.domain.value.NumberReputation;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;

class FromNumberDefaultTest {

    private static final OwnedNumber N1 = owned(1L, true);
    private static final OwnedNumber N2 = owned(2L, true);
    private static final OwnedNumber N3 = owned(3L, true);

    @Test
    void continuity_winsWhenInOwned() {
        assertThat(FromNumberDefault.resolve(List.of(N1, N2, N3),
                Optional.of(new PhoneNumberId(2L)), Optional.of(N3)))
                .contains(N2);
    }

    @Test
    void pinned_usedWhenNoContinuity() {
        assertThat(FromNumberDefault.resolve(List.of(N1, N2, N3),
                Optional.empty(), Optional.of(N3)))
                .contains(N3);
    }

    @Test
    void firstActive_whenNoContinuityOrPinned() {
        assertThat(FromNumberDefault.resolve(List.of(owned(1L, false), N2, N3),
                Optional.empty(), Optional.empty()))
                .contains(N2);
    }

    @Test
    void continuityNotInOwned_fallsThrough() {
        assertThat(FromNumberDefault.resolve(List.of(N1, N2),
                Optional.of(new PhoneNumberId(99L)), Optional.of(N2)))
                .contains(N2);
    }

    @Test
    void empty_whenNoOwnedNumbers() {
        assertThat(FromNumberDefault.resolve(List.of(), Optional.empty(), Optional.empty()))
                .isEmpty();
    }

    private static OwnedNumber owned(long id, boolean active) {
        return new OwnedNumber(
                new PhoneNumberId(id), new PhoneNumber("+1202555000" + id), Optional.empty(),
                new AreaCode("202"), "twilio", new NumberReputation.Clean(),
                0, active, Instant.now(), Instant.now());
    }
}
