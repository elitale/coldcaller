package com.elitale.coldbirds.coldcalling.ui.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LeadColumnPrefsTest {

    @Test
    void parseRoundTripsAndDropsBlanksAndDuplicates() {
        LeadColumnPrefs prefs = LeadColumnPrefs.parse("Source, Timezone , Source ,, Priority");
        assertThat(prefs.columns()).containsExactly("Source", "Timezone", "Priority");
        assertThat(prefs.serialize()).isEqualTo("Source,Timezone,Priority");
    }

    @Test
    void emptyParsesToNoColumns() {
        assertThat(LeadColumnPrefs.parse(null).columns()).isEmpty();
        assertThat(LeadColumnPrefs.parse("  ").columns()).isEmpty();
    }

    @Test
    void withColumnAppendsOnlyWhenAbsent() {
        LeadColumnPrefs prefs = LeadColumnPrefs.empty().withColumn("Source");
        assertThat(prefs.columns()).containsExactly("Source");
        assertThat(prefs.withColumn("Source").columns()).containsExactly("Source");
        assertThat(prefs.withColumn("Timezone").columns()).containsExactly("Source", "Timezone");
    }

    @Test
    void withoutRemovesKey() {
        LeadColumnPrefs prefs = LeadColumnPrefs.parse("Source,Timezone");
        assertThat(prefs.without("Source").columns()).containsExactly("Timezone");
    }

    @Test
    void mergeAllPreservesOrderAndAppendsNewKeys() {
        LeadColumnPrefs prefs = LeadColumnPrefs.parse("Source");
        LeadColumnPrefs merged = prefs.mergeAll(List.of("Source", "Timezone", "Priority"));
        assertThat(merged.columns()).containsExactly("Source", "Timezone", "Priority");
    }
}
