package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContactSuggestionTest {

    private static final PhoneNumber NUM = new PhoneNumber("+12025550142");

    @Test
    void ofLead_exposesNameCompanyDnc() {
        ContactSuggestion s = ContactSuggestion.ofLead(lead("Dave", "Park", "Acme", true));
        assertThat(s.isRawNumber()).isFalse();
        assertThat(s.displayName()).isEqualTo("Dave Park");
        assertThat(s.company()).contains("Acme");
        assertThat(s.subtitle()).contains("Acme").contains(NUM.value());
        assertThat(s.dnc()).isTrue();
    }

    @Test
    void ofLead_noCompany_subtitleIsNumber() {
        ContactSuggestion s = ContactSuggestion.ofLead(lead("Dave", "Park", null, false));
        assertThat(s.company()).isEmpty();
        assertThat(s.subtitle()).isEqualTo(NUM.value());
        assertThat(s.dnc()).isFalse();
    }

    @Test
    void ofNumber_isRaw_noLead() {
        ContactSuggestion s = ContactSuggestion.ofNumber(NUM);
        assertThat(s.isRawNumber()).isTrue();
        assertThat(s.displayName()).isEqualTo(NUM.value());
        assertThat(s.dnc()).isFalse();
        assertThat(s.company()).isEmpty();
    }

    private static Lead lead(String first, String last, String company, boolean dnc) {
        return new Lead(
                new LeadId(1L), Optional.of(first), Optional.of(last), NUM,
                Optional.ofNullable(company), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(), dnc, Map.of(), LeadStatus.NEW,
                Instant.now(), Instant.now());
    }
}
