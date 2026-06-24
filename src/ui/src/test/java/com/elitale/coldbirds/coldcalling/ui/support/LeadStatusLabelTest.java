package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeadStatusLabelTest {

    @Test
    void everyStatusHasANonBlankHumanLabel() {
        for (LeadStatus status : LeadStatus.values()) {
            assertThat(LeadStatusLabel.of(status))
                    .as("label for %s", status)
                    .isNotBlank();
        }
    }

    @Test
    void labelsAreTitleCasedAndReadable() {
        assertThat(LeadStatusLabel.of(LeadStatus.NEW)).isEqualTo("New");
        assertThat(LeadStatusLabel.of(LeadStatus.NOT_INTERESTED)).isEqualTo("Not interested");
        assertThat(LeadStatusLabel.of(LeadStatus.BAD_NUMBER)).isEqualTo("Bad number");
        assertThat(LeadStatusLabel.of(LeadStatus.DNC)).isEqualTo("Do not call");
    }
}
