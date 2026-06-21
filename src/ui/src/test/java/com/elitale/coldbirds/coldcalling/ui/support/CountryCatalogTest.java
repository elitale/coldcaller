package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.Country;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CountryCatalogTest {

    @Test
    void catalogLoadsManyCountries() {
        assertThat(CountryCatalog.ALL).hasSizeGreaterThan(200);
    }

    @Test
    void everyEntryHasResolvableZoneAndValidDialCode() {
        for (Country country : CountryCatalog.ALL) {
            assertThat(country.isoCode()).hasSize(2);
            assertThat(country.dialCode()).matches("\\+\\d{1,4}");
            assertThatNoException().isThrownBy(() -> ZoneId.of(country.zoneId()));
        }
    }

    @Test
    void entriesAreSortedByDisplayName() {
        List<Country> sorted = CountryCatalog.ALL.stream()
                .sorted(Comparator.comparing(Country::displayName))
                .toList();
        assertThat(CountryCatalog.ALL).containsExactlyElementsOf(sorted);
    }

    @Test
    void isoCodesAreUnique() {
        long distinct = CountryCatalog.ALL.stream()
                .map(Country::isoCode)
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(CountryCatalog.ALL.size());
    }

    @Test
    void byIsoIsCaseInsensitive() {
        assertThat(CountryCatalog.byIso("us")).isPresent()
                .get().extracting(Country::displayName).isEqualTo("United States");
        assertThat(CountryCatalog.byIso("US")).isEqualTo(CountryCatalog.byIso("us"));
    }

    @Test
    void byIsoReturnsEmptyForUnknownOrNull() {
        assertThat(CountryCatalog.byIso("ZZ")).isEmpty();
        assertThat(CountryCatalog.byIso(null)).isEmpty();
    }

    @Test
    void listIsUnmodifiable() {
        assertThatThrownBy(() -> CountryCatalog.ALL.add(new Country("ZZ", "Nowhere", "+1", "Z")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
