package com.elitale.coldbirds.coldcalling.domain.value;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CountryLookupTest {

    private static final Country US = new Country("US", "United States", "+1", "-05:00");
    private static final Country GB = new Country("GB", "United Kingdom", "+44", "Z");
    private static final Country IN = new Country("IN", "India", "+91", "+05:30");
    private static final List<Country> ALL = List.of(US, GB, IN);

    @Test
    void resolvesByDialCodePrefix() {
        assertThat(CountryLookup.byE164(ALL, "+917597365803")).contains(IN);
        assertThat(CountryLookup.byE164(ALL, "+442071838750")).contains(GB);
        assertThat(CountryLookup.byE164(ALL, "+14155552671")).contains(US);
    }

    @Test
    void picksLongestMatchingDialCode() {
        final Country usLong = new Country("CA", "Canada", "+1", "-05:00");
        final Country special = new Country("DO", "Dominican", "+1809", "-04:00");
        final List<Country> catalog = List.of(usLong, special);

        assertThat(CountryLookup.byE164(catalog, "+18095551234")).contains(special);
    }

    @Test
    void toleratesNumbersWithoutLeadingPlus() {
        assertThat(CountryLookup.byE164(ALL, "917597365803")).contains(IN);
    }

    @Test
    void returnsEmptyForBlankOrUnknown() {
        assertThat(CountryLookup.byE164(ALL, "")).isEmpty();
        assertThat(CountryLookup.byE164(ALL, "   ")).isEmpty();
        assertThat(CountryLookup.byE164(ALL, null)).isEmpty();
        assertThat(CountryLookup.byE164(ALL, "+9999999")).isEmpty();
    }
}
