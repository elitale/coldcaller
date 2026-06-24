package com.elitale.coldbirds.coldcalling.storage.repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores reusable named column-mapping templates so a known export source
 * (Apollo, ZoomInfo, …) re-imports in one click when its header signature matches.
 */
public interface ImportMappingRepository {

    /** A saved column-mapping template. {@code id} is empty before first save. */
    record ImportMapping(
            Optional<Long> id,
            String name,
            String headerSignature,
            String mappingJson,
            Optional<String> defaultCountry,
            Optional<Long> targetListId,
            Instant createdAt,
            Instant updatedAt
    ) {
        public ImportMapping {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(headerSignature, "headerSignature must not be null");
            Objects.requireNonNull(mappingJson, "mappingJson must not be null");
            Objects.requireNonNull(defaultCountry, "defaultCountry must not be null");
            Objects.requireNonNull(targetListId, "targetListId must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        }
    }

    /** Insert or update by header signature; returns the stored mapping with its id. */
    ImportMapping save(ImportMapping mapping);

    /** Find a template whose header signature matches an incoming file's. */
    Optional<ImportMapping> findByHeaderSignature(String signature);

    /** All saved templates, newest first. */
    List<ImportMapping> findAll();

    /** Delete a template by id. */
    void delete(long id);
}
