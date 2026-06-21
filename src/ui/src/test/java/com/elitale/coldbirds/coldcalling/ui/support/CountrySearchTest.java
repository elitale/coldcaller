package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.Country;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CountrySearchTest {

    private static final Country US = new Country("US", "United States", "+1", "-05:00");
    private static final Country GB = new Country("GB", "United Kingdom", "+44", "Z");
    private static final Country IN = new Country("IN", "India", "+91", "+05:30");
    private static final List<Country> ALL = List.of(US, GB, IN);

    @Test
    void blankQueryMatchesEveryCountry() {
        assertThat(CountrySearch.matches(US, "")).isTrue();
        assertThat(CountrySearch.matches(US, "   ")).isTrue();
        assertThat(CountrySearch.matches(US, null)).isTrue();
    }

    @Test
    void matchesByDisplayNameCaseInsensitively() {
        assertThat(CountrySearch.matches(US, "united")).isTrue();
        assertThat(CountrySearch.matches(US, "STATES")).isTrue();
        assertThat(CountrySearch.matches(US, "india")).isFalse();
    }

    @Test
    void matchesByDialCode() {
        assertThat(CountrySearch.matches(IN, "+91")).isTrue();
        assertThat(CountrySearch.matches(IN, "91")).isTrue();
        assertThat(CountrySearch.matches(IN, "+1")).isFalse();
    }

    @Test
    void matchesByIsoCode() {
        assertThat(CountrySearch.matches(GB, "gb")).isTrue();
        assertThat(CountrySearch.matches(GB, "GB")).isTrue();
    }

    @Test
    void filterReturnsOnlyMatchingCountries() {
        assertThat(CountrySearch.filter(ALL, "united")).containsExactly(US, GB);
        assertThat(CountrySearch.filter(ALL, "india")).containsExactly(IN);
        assertThat(CountrySearch.filter(ALL, "")).containsExactlyElementsOf(ALL);
    }

    @Test
    void filterResultIsUnmodifiable() {
        assertThatThrownBy(() -> CountrySearch.filter(ALL, "").add(US))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullCountryIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> CountrySearch.matches(null, "x"));
    }
}
