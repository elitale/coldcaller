package com.elitale.coldbirds.coldcalling.services.imports;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class ColumnAutoDetectorTest {

    @Test
    void mapsHeadersByFuzzyName() {
        ColumnMapping mapping = ColumnAutoDetector.detect(
                List.of("First Name", "Last Name", "Email", "Company", "Job Title", "Mobile Phone"),
                List.of());

        assertThat(mapping.fieldOf("First Name")).isEqualTo(LeadField.FIRST_NAME);
        assertThat(mapping.fieldOf("Last Name")).isEqualTo(LeadField.LAST_NAME);
        assertThat(mapping.fieldOf("Email")).isEqualTo(LeadField.EMAIL);
        assertThat(mapping.fieldOf("Company")).isEqualTo(LeadField.COMPANY);
        assertThat(mapping.fieldOf("Job Title")).isEqualTo(LeadField.TITLE);
        assertThat(mapping.fieldOf("Mobile Phone")).isEqualTo(LeadField.PHONE);
    }

    @Test
    void sniffsPhoneFromContentWhenHeaderLies() {
        ColumnMapping mapping = ColumnAutoDetector.detect(
                List.of("Contact"),
                List.of(List.of("+14155551234"), List.of("(415) 555-9999"), List.of("4155550000")));

        assertThat(mapping.fieldOf("Contact")).isEqualTo(LeadField.PHONE);
    }

    @Test
    void picksMobileAsPrimaryNeverCorporate() {
        ColumnMapping mapping = ColumnAutoDetector.detect(
                List.of("Corporate Phone", "Mobile Phone", "Work Direct Phone"),
                List.of());

        assertThat(mapping.phoneHeaders())
                .containsExactly("Corporate Phone", "Mobile Phone", "Work Direct Phone");
        assertThat(mapping.primaryPhoneHeader()).contains("Mobile Phone");
    }

    @Test
    void unmappedColumnBecomesCustom() {
        ColumnMapping mapping = ColumnAutoDetector.detect(
                List.of("LinkedIn URL"),
                List.of(List.of("https://linkedin.com/in/x")));

        assertThat(mapping.fieldOf("LinkedIn URL")).isEqualTo(LeadField.CUSTOM);
    }
}
