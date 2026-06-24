package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.services.LeadService;
import com.elitale.coldbirds.coldcalling.services.PhoneNormalizer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class QuickAddModelTest {

    private static final Optional<String> US = Optional.of("US");

    private QuickAddModel model() {
        return new QuickAddModel(new PhoneNormalizer());
    }

    @Test
    void cannotSubmitWhenPhoneBlank() {
        QuickAddModel m = model();
        assertThat(m.canSubmit(US)).isFalse();
        assertThat(m.draft(US)).isEmpty();
    }

    @Test
    void cannotSubmitWhenPhoneInvalid() {
        QuickAddModel m = model();
        m.setPhone("not a phone");
        assertThat(m.canSubmit(US)).isFalse();
    }

    @Test
    void normalizesLocalNumberAgainstRegion() {
        QuickAddModel m = model();
        m.setPhone("(415) 555-0199");
        m.setName("Sarah");

        assertThat(m.canSubmit(US)).isTrue();
        LeadService.NewLead draft = m.draft(US).orElseThrow();
        assertThat(draft.phone().value()).isEqualTo("+14155550199");
        assertThat(draft.firstName()).contains("Sarah");
    }

    @Test
    void nameIsOptional() {
        QuickAddModel m = model();
        m.setPhone("+14155550199");

        LeadService.NewLead draft = m.draft(US).orElseThrow();
        assertThat(draft.firstName()).isEmpty();
    }

    @Test
    void addToCurrentListDefaultsTrueAndToggles() {
        QuickAddModel m = model();
        assertThat(m.addToCurrentList()).isTrue();
        m.setAddToCurrentList(false);
        assertThat(m.addToCurrentList()).isFalse();
    }
}
