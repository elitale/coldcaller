package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.Country;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CallParticipantTest {

    private static final PhoneNumber PHONE = new PhoneNumber("+12025551234");
    private static final Country US =
            new Country("US", "United States", "+1", "America/New_York");

    @Test
    void of_withoutLead_headlineIsNumber() {
        CallParticipant party = CallParticipant.of(PHONE.value(), Optional.empty(), Optional.empty());

        assertThat(party.name()).isEmpty();
        assertThat(party.headline()).isEqualTo(PHONE.value());
        assertThat(party.subtitle()).isEmpty();
    }

    @Test
    void of_withNamedLead_headlineIsName() {
        CallParticipant party = CallParticipant.of(PHONE.value(), Optional.of(lead("Jane", "Doe",
                Optional.of("CTO"), Optional.of("Acme"))), Optional.of(US));

        assertThat(party.name()).contains("Jane Doe");
        assertThat(party.headline()).isEqualTo("Jane Doe");
        assertThat(party.subtitle()).contains("CTO · Acme");
        assertThat(party.country()).contains(US);
    }

    @Test
    void of_leadWithoutName_fallsBackToNumber() {
        CallParticipant party = CallParticipant.of(PHONE.value(),
                Optional.of(lead(null, null, Optional.empty(), Optional.empty())), Optional.empty());

        assertThat(party.name()).isEmpty();
        assertThat(party.headline()).isEqualTo(PHONE.value());
    }

    @Test
    void initials_fromName_usesTwoLetters() {
        CallParticipant party = CallParticipant.of(PHONE.value(),
                Optional.of(lead("Jane", "Doe", Optional.empty(), Optional.empty())), Optional.empty());

        assertThat(party.initials()).isEqualTo("JD");
    }

    @Test
    void initials_fromNumber_isHash() {
        CallParticipant party = CallParticipant.of(PHONE.value(), Optional.empty(), Optional.empty());

        assertThat(party.initials()).isEqualTo("#");
    }

    @Test
    void subtitle_titleOnly_hasNoSeparator() {
        CallParticipant party = CallParticipant.of(PHONE.value(),
                Optional.of(lead("Jane", "Doe", Optional.of("CTO"), Optional.empty())), Optional.empty());

        assertThat(party.subtitle()).contains("CTO");
    }

    private static Lead lead(String first, String last,
                             Optional<String> title, Optional<String> company) {
        return new Lead(
                new LeadId(1L),
                Optional.ofNullable(first), Optional.ofNullable(last),
                PHONE, company, title, Optional.empty(),
                List.of(), Optional.empty(), false,
                Map.of(), LeadStatus.NEW,
                Instant.now(), Instant.now());
    }
}
