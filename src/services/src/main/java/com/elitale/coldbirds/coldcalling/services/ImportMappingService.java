package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.services.imports.ColumnMapping;
import com.elitale.coldbirds.coldcalling.services.imports.LeadField;
import com.elitale.coldbirds.coldcalling.storage.repository.ImportMappingRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.ImportMappingRepository.ImportMapping;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Saves / matches reusable column-mapping templates keyed by a header signature,
 * so a recognised export source (Apollo, ZoomInfo, …) re-imports in one click.
 */
public final class ImportMappingService {

    private final ImportMappingRepository repo;
    private final ObjectMapper json = new ObjectMapper();

    public ImportMappingService(ImportMappingRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo must not be null");
    }

    /** Order-independent, case-insensitive signature of a header set. */
    public String signatureOf(List<String> headers) {
        Objects.requireNonNull(headers, "headers must not be null");
        return headers.stream()
                .map(h -> h.toLowerCase(Locale.ROOT).strip())
                .filter(h -> !h.isEmpty())
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    /** Find a saved template whose signature matches these headers. */
    public Optional<ImportMapping> findFor(List<String> headers) {
        return repo.findByHeaderSignature(signatureOf(headers));
    }

    /** Persist (insert or update by signature) a named template for a mapping. */
    public ImportMapping save(String name, ColumnMapping mapping,
                              Optional<String> defaultCountry, Optional<Long> targetListId) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(mapping, "mapping must not be null");
        final Instant now = Instant.now();
        return repo.save(new ImportMapping(
                Optional.empty(), name, signatureOf(mapping.headers()), serialize(mapping),
                defaultCountry, targetListId, now, now));
    }

    public List<ImportMapping> all() {
        return repo.findAll();
    }

    public void delete(long id) {
        repo.delete(id);
    }

    /** Rebuild a {@link ColumnMapping} from a stored template against current headers. */
    public ColumnMapping toColumnMapping(ImportMapping stored, List<String> headers) {
        Objects.requireNonNull(stored, "stored must not be null");
        final Stored decoded = deserialize(stored.mappingJson());
        final Map<String, LeadField> fields = new LinkedHashMap<>();
        for (String header : headers) {
            final String name = decoded.fields().get(header);
            fields.put(header, name == null ? LeadField.CUSTOM : LeadField.valueOf(name));
        }
        final Optional<String> primary = Optional.ofNullable(decoded.primaryPhone())
                .filter(headers::contains);
        return new ColumnMapping(headers, fields, primary);
    }

    private String serialize(ColumnMapping mapping) {
        final Map<String, String> fields = new LinkedHashMap<>();
        mapping.fieldByHeader().forEach((h, f) -> fields.put(h, f.name()));
        try {
            return json.writeValueAsString(new Stored(fields, mapping.primaryPhoneHeader().orElse(null)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize mapping", e);
        }
    }

    private Stored deserialize(String mappingJson) {
        try {
            return json.readValue(mappingJson, Stored.class);
        } catch (Exception e) {
            return new Stored(Map.of(), null);
        }
    }

    /** Wire format for a persisted mapping. */
    private record Stored(Map<String, String> fields, String primaryPhone) {
        Stored {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }
}
