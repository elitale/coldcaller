package com.elitale.coldbirds.coldcalling.ui.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class FlagImagesTest {

    @Test
    void resourcePathLowercasesIsoCode() {
        assertThat(FlagImages.resourcePath("IN")).isEqualTo("/flags/in.png");
        assertThat(FlagImages.resourcePath("us")).isEqualTo("/flags/us.png");
    }

    @Test
    void resourcePathRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> FlagImages.resourcePath(null));
    }

    @Test
    void everyCatalogCountryHasABundledFlagResource() {
        CountryCatalog.ALL.forEach(country ->
                assertThat(FlagImages.class.getResource(FlagImages.resourcePath(country.isoCode())))
                        .as("flag resource for %s", country.isoCode())
                        .isNotNull());
    }
}
