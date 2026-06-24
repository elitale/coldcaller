package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.services.imports.ColumnAutoDetector;
import com.elitale.coldbirds.coldcalling.services.imports.ColumnMapping;
import com.elitale.coldbirds.coldcalling.services.imports.LeadField;
import com.elitale.coldbirds.coldcalling.storage.repository.ImportMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class ImportMappingServiceTest {

    private FakeRepo repo;
    private ImportMappingService service;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        service = new ImportMappingService(repo);
    }

    @Test
    void signatureIsOrderIndependentAndCaseInsensitive() {
        String a = service.signatureOf(List.of("First Name", "Phone", "Company"));
        String b = service.signatureOf(List.of("company", "PHONE", "first name"));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void savedTemplateRoundTripsThroughToColumnMapping() {
        List<String> headers = List.of("First Name", "Mobile Phone", "Company", "LinkedIn URL");
        ColumnMapping original = ColumnAutoDetector.detect(headers, List.of());

        service.save("Apollo", original, Optional.of("US"), Optional.empty());

        Optional<ImportMappingRepository.ImportMapping> found = service.findFor(headers);
        assertThat(found).isPresent();

        ColumnMapping restored = service.toColumnMapping(found.get(), headers);
        assertThat(restored.fieldOf("First Name")).isEqualTo(LeadField.FIRST_NAME);
        assertThat(restored.fieldOf("Mobile Phone")).isEqualTo(LeadField.PHONE);
        assertThat(restored.fieldOf("Company")).isEqualTo(LeadField.COMPANY);
        assertThat(restored.fieldOf("LinkedIn URL")).isEqualTo(LeadField.CUSTOM);
        assertThat(restored.primaryPhoneHeader()).contains("Mobile Phone");
    }

    @Test
    void findForReturnsEmptyWhenNoTemplateSaved() {
        assertThat(service.findFor(List.of("A", "B"))).isEmpty();
    }

    /** Minimal in-memory repository keyed by header signature. */
    private static final class FakeRepo implements ImportMappingRepository {
        private final List<ImportMapping> store = new ArrayList<>();

        @Override
        public ImportMapping save(ImportMapping mapping) {
            store.removeIf(m -> m.headerSignature().equals(mapping.headerSignature()));
            store.add(mapping);
            return mapping;
        }

        @Override
        public Optional<ImportMapping> findByHeaderSignature(String signature) {
            return store.stream().filter(m -> m.headerSignature().equals(signature)).findFirst();
        }

        @Override
        public List<ImportMapping> findAll() {
            return List.copyOf(store);
        }

        @Override
        public void delete(long id) {
            store.removeIf(m -> m.id().map(v -> v == id).orElse(false));
        }
    }
}
