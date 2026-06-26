package com.elitale.coldbirds.coldcalling.ui.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;

/**
 * Headless tier-assignment logic for the dialing lead-context panel. Verifies which
 * lead fields land in the at-a-glance tiers vs. the collapsed "all fields" tier, and
 * that the detail tier is deterministically ordered and free of blanks.
 */
class LeadFieldDigestTest {

    private static final PhoneNumber PHONE = new PhoneNumber("+14155550101");

    @Test
    void nameUsesDisplayNameWhenNamed() {
        assertThat(LeadFieldDigest.of(lead(b -> {
            b.firstName = Optional.of("Jane");
            b.lastName = Optional.of("Doe");
        })).name()).isEqualTo("Jane Doe");
    }

    @Test
    void nameFallsBackToPhoneWhenUnnamed() {
        assertThat(LeadFieldDigest.of(lead(b -> {})).name()).isEqualTo(PHONE.value());
    }

    @Test
    void companyTitleJoinsBothWithMiddot() {
        assertThat(LeadFieldDigest.of(lead(b -> {
            b.company = Optional.of("Acme");
            b.title = Optional.of("VP Sales");
        })).companyTitle()).hasValue("Acme · VP Sales");
    }

    @Test
    void companyTitleUsesWhicheverSideIsPresent() {
        assertThat(LeadFieldDigest.of(lead(b -> b.company = Optional.of("Acme"))).companyTitle())
                .hasValue("Acme");
        assertThat(LeadFieldDigest.of(lead(b -> b.title = Optional.of("VP"))).companyTitle())
                .hasValue("VP");
    }

    @Test
    void companyTitleEmptyWhenNeitherPresent() {
        assertThat(LeadFieldDigest.of(lead(b -> {})).companyTitle()).isEmpty();
    }

    @Test
    void dncAndStatusPassThrough() {
        final LeadFieldDigest digest = LeadFieldDigest.of(lead(b -> {
            b.dnc = true;
            b.status = LeadStatus.NOT_INTERESTED;
        }));
        assertThat(digest.dnc()).isTrue();
        assertThat(digest.statusLabel()).isEqualTo("Not interested");
    }

    @Test
    void tagsPassThrough() {
        assertThat(LeadFieldDigest.of(lead(b -> b.tags = List.of("warm", "q3"))).tags())
                .containsExactly("warm", "q3");
    }

    @Test
    void detailFieldsOrderEmailThenCustomThenNotesThenAdded() {
        final LeadFieldDigest digest = LeadFieldDigest.of(lead(b -> {
            b.email = Optional.of("jane@acme.com");
            b.custom = orderedMap("Industry", "SaaS");
            b.notes = Optional.of("Spoke last week");
        }));
        assertThat(digest.detailFields())
                .extracting(LeadFieldDigest.Field::label)
                .containsExactly("Email", "Industry", "Notes", "Added");
    }

    @Test
    void customFieldsSortedCaseInsensitively() {
        final LeadFieldDigest digest = LeadFieldDigest.of(lead(b ->
                b.custom = orderedMap("Zeta", "1", "alpha", "2", "Mango", "3")));
        assertThat(digest.detailFields())
                .filteredOn(f -> !f.label().equals("Added"))
                .extracting(LeadFieldDigest.Field::label)
                .containsExactly("alpha", "Mango", "Zeta");
    }

    @Test
    void blankEmailAndBlankCustomValuesAreOmitted() {
        final LeadFieldDigest digest = LeadFieldDigest.of(lead(b -> {
            b.email = Optional.of("   ");
            b.custom = orderedMap("Empty", "  ", "Real", "value");
        }));
        assertThat(digest.detailFields())
                .extracting(LeadFieldDigest.Field::label)
                .containsExactly("Real", "Added");
    }

    @Test
    void addedFieldIsAlwaysPresentAndNonBlank() {
        final LeadFieldDigest.Field added = LeadFieldDigest.of(lead(b -> {})).detailFields().stream()
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(added.label()).isEqualTo("Added");
        assertThat(added.value()).isNotBlank();
    }

    // ── field-kind classification ─────────────────────────────────────────────

    @Test
    void emailFieldIsClassifiedAsEmailKind() {
        final LeadFieldDigest.Field email = fieldNamed(
                LeadFieldDigest.of(lead(b -> b.email = Optional.of("jane@acme.com"))), "Email");
        assertThat(email.kind()).isEqualTo(LeadFieldDigest.FieldKind.EMAIL);
        assertThat(email.value()).isEqualTo("jane@acme.com");
    }

    @Test
    void httpUrlCustomFieldIsClassifiedAsLink() {
        final LeadFieldDigest.Field link = fieldNamed(
                LeadFieldDigest.of(lead(b -> b.custom =
                        orderedMap("linkedin", "http://www.linkedin.com/in/jane"))), "linkedin");
        assertThat(link.kind()).isEqualTo(LeadFieldDigest.FieldKind.LINK);
        assertThat(link.value()).isEqualTo("http://www.linkedin.com/in/jane");
    }

    @Test
    void imageUrlWithoutExtensionIsClassifiedByLabelHint() {
        final LeadFieldDigest.Field img = fieldNamed(
                LeadFieldDigest.of(lead(b -> b.custom = orderedMap(
                        "person_photo_url", "https://static.licdn.com/aero-v1/sc/h/abc123"))),
                "person_photo_url");
        assertThat(img.kind()).isEqualTo(LeadFieldDigest.FieldKind.IMAGE);
    }

    @Test
    void imageUrlWithExtensionIsClassifiedAsImage() {
        final LeadFieldDigest.Field img = fieldNamed(
                LeadFieldDigest.of(lead(b -> b.custom =
                        orderedMap("avatar", "https://cdn.example.com/p/jane.JPG?v=2"))), "avatar");
        assertThat(img.kind()).isEqualTo(LeadFieldDigest.FieldKind.IMAGE);
    }

    @Test
    void isoDatetimeCustomValueIsHumanized() {
        final LeadFieldDigest.Field created = fieldNamed(
                LeadFieldDigest.of(lead(b -> b.custom =
                        orderedMap("createdAt", "2026-05-16T17:00:22.712Z"))), "createdAt");
        assertThat(created.value()).matches("\\d{1,2} \\w{3} \\d{4}");
        assertThat(created.kind()).isEqualTo(LeadFieldDigest.FieldKind.TEXT);
    }

    @Test
    void isoDateCustomValueIsHumanized() {
        final LeadFieldDigest.Field d = fieldNamed(
                LeadFieldDigest.of(lead(b -> b.custom = orderedMap("signup_date", "2026-05-16"))),
                "signup_date");
        assertThat(d.value()).isEqualTo("16 May 2026");
        assertThat(d.kind()).isEqualTo(LeadFieldDigest.FieldKind.TEXT);
    }

    @Test
    void plainTextAndNumericValuesStayText() {
        final LeadFieldDigest digest = LeadFieldDigest.of(lead(b ->
                b.custom = orderedMap("employees", "2", "city", "Los Angeles")));
        assertThat(fieldNamed(digest, "employees").kind()).isEqualTo(LeadFieldDigest.FieldKind.TEXT);
        assertThat(fieldNamed(digest, "city").kind()).isEqualTo(LeadFieldDigest.FieldKind.TEXT);
    }

    @Test
    void addedFieldIsTextKind() {
        assertThat(fieldNamed(LeadFieldDigest.of(lead(b -> {})), "Added").kind())
                .isEqualTo(LeadFieldDigest.FieldKind.TEXT);
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private static LeadFieldDigest.Field fieldNamed(final LeadFieldDigest digest, final String label) {
        return digest.detailFields().stream()
                .filter(f -> f.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field labelled " + label));
    }

    private static Map<String, String> orderedMap(String... kv) {
        final LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private static final class Builder {
        Optional<String> firstName = Optional.empty();
        Optional<String> lastName = Optional.empty();
        Optional<String> company = Optional.empty();
        Optional<String> title = Optional.empty();
        Optional<String> email = Optional.empty();
        Optional<String> notes = Optional.empty();
        List<String> tags = List.of();
        boolean dnc = false;
        Map<String, String> custom = Map.of();
        LeadStatus status = LeadStatus.NEW;
    }

    private static Lead lead(java.util.function.Consumer<Builder> mutate) {
        final Builder b = new Builder();
        mutate.accept(b);
        return new Lead(
                new LeadId(1L),
                b.firstName, b.lastName, PHONE, b.company, b.title, b.email,
                b.tags, b.notes, b.dnc, b.custom, b.status,
                Instant.now(), Instant.now());
    }
}
