package com.elitale.coldbirds.coldcalling.domain.model;

import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class LeadTest {

    private static final PhoneNumber PHONE = new PhoneNumber("+14155550100");

    private static Lead lead(Optional<String> first, Optional<String> last,
                             Map<String, String> custom, LeadStatus status) {
        return new Lead(new LeadId(1L), first, last, PHONE,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(), false, custom, status,
                Instant.now(), Instant.now());
    }

    @Test
    void displayNameUsesFullName() {
        assertThat(lead(Optional.of("Ada"), Optional.of("Lovelace"), Map.of(), LeadStatus.NEW)
                .displayName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void displayNameFallsBackToPhone() {
        assertThat(lead(Optional.empty(), Optional.empty(), Map.of(), LeadStatus.NEW)
                .displayName()).isEqualTo(PHONE.value());
    }

    @Test
    void customFieldsAreUnmodifiable() {
        Lead saved = lead(Optional.of("A"), Optional.empty(),
                Map.of("Timezone", "PST"), LeadStatus.CONTACTED);
        assertThat(saved.customFields()).containsEntry("Timezone", "PST");
        assertThat(saved.leadStatus()).isEqualTo(LeadStatus.CONTACTED);
        assertThatThrownBy(() -> saved.customFields().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullCustomFieldsOrStatusRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                lead(Optional.of("A"), Optional.empty(), null, LeadStatus.NEW));
        assertThatNullPointerException().isThrownBy(() ->
                lead(Optional.of("A"), Optional.empty(), Map.of(), null));
    }

    @Test
    void withCustomFieldAddsAndReplacesWithoutMutatingOriginal() {
        Lead original = lead(Optional.of("A"), Optional.empty(), Map.of("Timezone", "PST"), LeadStatus.NEW);
        Lead updated = original.withCustomField("Source", "Apollo");
        assertThat(original.customFields()).doesNotContainKey("Source");
        assertThat(updated.customFields())
                .containsEntry("Timezone", "PST")
                .containsEntry("Source", "Apollo");
        assertThat(updated.withCustomField("Timezone", "EST").customFields())
                .containsEntry("Timezone", "EST");
    }

    @Test
    void withCustomFieldBlankOrNullRemovesKey() {
        Lead original = lead(Optional.of("A"), Optional.empty(),
                Map.of("Timezone", "PST", "Source", "Apollo"), LeadStatus.NEW);
        assertThat(original.withCustomField("Timezone", "  ").customFields())
                .doesNotContainKey("Timezone")
                .containsEntry("Source", "Apollo");
        assertThat(original.withCustomField("Source", null).customFields())
                .doesNotContainKey("Source");
    }
}
