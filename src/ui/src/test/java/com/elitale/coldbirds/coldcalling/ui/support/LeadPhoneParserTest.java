package com.elitale.coldbirds.coldcalling.ui.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeadPhoneParserTest {

    @Test
    void stripsFormattingAndAcceptsE164() {
        assertThat(LeadPhoneParser.parse("+1 (415) 555-0100"))
                .hasValueSatisfying(p -> assertThat(p.value()).isEqualTo("+14155550100"));
        assertThat(LeadPhoneParser.parse("+1.415.555.0100"))
                .hasValueSatisfying(p -> assertThat(p.value()).isEqualTo("+14155550100"));
    }

    @Test
    void rejectsBlankNullAndNonE164() {
        assertThat(LeadPhoneParser.parse(null)).isEmpty();
        assertThat(LeadPhoneParser.parse("   ")).isEmpty();
        assertThat(LeadPhoneParser.parse("4155550100")).isEmpty();   // no country code
        assertThat(LeadPhoneParser.parse("not a phone")).isEmpty();
    }
}
