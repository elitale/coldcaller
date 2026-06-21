package com.elitale.coldbirds.coldcalling.domain.value;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.*;

class CountryTest {

    private static Country usa() {
        return new Country("US", "United States", "+1", "-05:00");
    }

    @Test
    void validCountryIsAccepted() {
        assertThatNoException().isThrownBy(() -> new Country("IN", "India", "+91", "+05:30"));
        assertThatNoException().isThrownBy(() -> new Country("GB", "United Kingdom", "+44", "Z"));
    }

    @Test
    void flagIsDerivedFromIsoCode() {
        assertThat(usa().flag()).isEqualTo("\uD83C\uDDFA\uD83C\uDDF8"); // 🇺🇸
        assertThat(new Country("IN", "India", "+91", "+05:30").flag())
                .isEqualTo("\uD83C\uDDEE\uD83C\uDDF3"); // 🇮🇳
        assertThat(new Country("gb", "United Kingdom", "+44", "Z").flag())
                .isEqualTo("\uD83C\uDDEC\uD83C\uDDE7"); // 🇬🇧 — lowercase iso still works
    }

    @Test
    void zoneResolvesFromZoneId() {
        assertThat(usa().zone()).isEqualTo(ZoneId.of("-05:00"));
        assertThat(new Country("IN", "India", "+91", "+05:30").zone()).isEqualTo(ZoneId.of("+05:30"));
        assertThat(new Country("GB", "United Kingdom", "+44", "Z").zone()).isEqualTo(ZoneId.of("Z"));
    }

    @Test
    void nonTwoLetterIsoCodeIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Country("USA", "United States", "+1", "-05:00"))
                .withMessageContaining("alpha-2");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Country("U", "United States", "+1", "-05:00"));
    }

    @Test
    void invalidDialCodeIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Country("US", "United States", "1", "-05:00"))
                .withMessageContaining("dial code");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Country("US", "United States", "+12345", "-05:00"));
    }

    @Test
    void nullFieldsAreRejected() {
        assertThatNullPointerException().isThrownBy(() -> new Country(null, "x", "+1", "Z"));
        assertThatNullPointerException().isThrownBy(() -> new Country("US", null, "+1", "Z"));
        assertThatNullPointerException().isThrownBy(() -> new Country("US", "x", null, "Z"));
        assertThatNullPointerException().isThrownBy(() -> new Country("US", "x", "+1", null));
    }

    @Test
    void equalityIsValueBased() {
        assertThat(usa()).isEqualTo(new Country("US", "United States", "+1", "-05:00"));
        assertThat(usa()).isNotEqualTo(new Country("CA", "Canada", "+1", "-05:00"));
    }
}
