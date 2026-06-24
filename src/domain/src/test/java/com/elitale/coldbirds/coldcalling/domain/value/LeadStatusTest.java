package com.elitale.coldbirds.coldcalling.domain.value;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LeadStatusTest {

    @Test
    void dbValueRoundTrips() {
        for (LeadStatus status : LeadStatus.values()) {
            assertThat(LeadStatus.fromDb(status.dbValue())).isEqualTo(status);
        }
    }

    @Test
    void knownDbValues() {
        assertThat(LeadStatus.NEW.dbValue()).isEqualTo("new");
        assertThat(LeadStatus.NOT_INTERESTED.dbValue()).isEqualTo("not_interested");
        assertThat(LeadStatus.BAD_NUMBER.dbValue()).isEqualTo("bad_number");
    }

    @Test
    void unknownDbValueIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LeadStatus.fromDb("nope"))
                .withMessageContaining("nope");
    }

    @Test
    void nullDbValueIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> LeadStatus.fromDb(null));
    }
}
