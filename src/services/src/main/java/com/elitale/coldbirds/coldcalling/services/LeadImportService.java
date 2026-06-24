package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.services.imports.ColumnAutoDetector;
import com.elitale.coldbirds.coldcalling.services.imports.ColumnMapping;
import com.elitale.coldbirds.coldcalling.services.imports.ImportPreview;
import com.elitale.coldbirds.coldcalling.services.imports.ImportResult;
import com.elitale.coldbirds.coldcalling.services.imports.ImportRowStatus;
import com.elitale.coldbirds.coldcalling.services.imports.PreviewRow;
import com.elitale.coldbirds.coldcalling.services.imports.RowResolver;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.ImportBatchRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.ImportBatchRepository.ImportBatch;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadImportRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadImportRepository.UpsertResult;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadImportRepository.UpsertRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Two-phase calling-grade CSV import. {@code preview} parses, maps, normalizes
 * and classifies every row with no writes; {@code commit} runs a non-destructive
 * bulk upsert stamped with a batch id, optionally attaches to a list, and records
 * the reconciling summary. Notes / disposition / call history are never touched.
 */
public final class LeadImportService {

    private final RowResolver resolver;
    private final LeadImportRepository importRepo;
    private final ImportBatchRepository batchRepo;
    private final CallListRepository callListRepo;

    public LeadImportService(PhoneNormalizer normalizer,
                             LeadImportRepository importRepo,
                             ImportBatchRepository batchRepo,
                             CallListRepository callListRepo) {
        this.resolver = new RowResolver(Objects.requireNonNull(normalizer, "normalizer"));
        this.importRepo = Objects.requireNonNull(importRepo, "importRepo must not be null");
        this.batchRepo = Objects.requireNonNull(batchRepo, "batchRepo must not be null");
        this.callListRepo = Objects.requireNonNull(callListRepo, "callListRepo must not be null");
    }

    /** Auto-detect the column mapping, then preview. */
    public ImportPreview previewAutoDetect(List<String> headers, List<List<String>> rows,
                                           Optional<String> defaultCountry) {
        return preview(rows, ColumnAutoDetector.detect(headers, rows), defaultCountry);
    }

    /** Classify every row against the mapping. No writes occur. */
    public ImportPreview preview(List<List<String>> rows, ColumnMapping mapping,
                                 Optional<String> defaultCountry) {
        Objects.requireNonNull(rows, "rows must not be null");
        Objects.requireNonNull(mapping, "mapping must not be null");
        Objects.requireNonNull(defaultCountry, "defaultCountry must not be null");

        final List<PreviewRow> resolved = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            resolved.add(resolver.resolve(i + 2, rows.get(i), mapping, defaultCountry));
        }

        final Set<String> validPhones = resolved.stream()
                .filter(r -> r.status() == ImportRowStatus.VALID)
                .map(r -> r.primaryPhone().orElseThrow().value())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final Set<String> dnc = importRepo.findDncPhones(validPhones);

        final Set<String> seen = new HashSet<>();
        final List<PreviewRow> out = new ArrayList<>(resolved.size());
        for (PreviewRow r : resolved) {
            if (r.status() != ImportRowStatus.VALID) {
                out.add(r);
                continue;
            }
            final String phone = r.primaryPhone().orElseThrow().value();
            if (!seen.add(phone)) {
                out.add(r.withStatus(ImportRowStatus.DUPLICATE, Optional.of("duplicate of an earlier row")));
            } else if (dnc.contains(phone)) {
                out.add(r.withStatus(ImportRowStatus.DNC, Optional.of("on DNC list")));
            } else {
                out.add(r);
            }
        }
        return ImportPreview.of(rows.size(), out);
    }

    /** Commit the valid rows: non-destructive upsert + optional list attach + batch record. */
    public ImportResult commit(ImportPreview preview, String fileName,
                               Optional<String> defaultCountry, Optional<CallListId> targetListId) {
        Objects.requireNonNull(preview, "preview must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(defaultCountry, "defaultCountry must not be null");
        Objects.requireNonNull(targetListId, "targetListId must not be null");

        final String batchId = UUID.randomUUID().toString();
        final List<PreviewRow> valid = preview.dialableRows();
        final List<UpsertRow> upserts = valid.stream().map(LeadImportService::toUpsert).toList();
        final UpsertResult upserted = importRepo.bulkUpsert(upserts, batchId);

        if (targetListId.isPresent() && !valid.isEmpty()) {
            final Set<String> phones = valid.stream()
                    .map(r -> r.primaryPhone().orElseThrow().value())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            final List<LeadId> ids = importRepo.findLiveIdsByPhones(phones);
            callListRepo.addLeads(targetListId.get(), ids);
        }

        final int committed = upserted.created() + upserted.updated();
        final int errors = Math.max(0, valid.size() - committed);
        final int skipped = preview.duplicateCount() + preview.dncCount() + preview.emptyCount();
        batchRepo.record(new ImportBatch(batchId, fileName, defaultCountry,
                upserted.created(), upserted.updated(), skipped, preview.needsReviewCount() + errors,
                Instant.now()));

        final List<ImportResult.ErrorRow> errorRows = preview.rows().stream()
                .filter(r -> r.status() == ImportRowStatus.NEEDS_REVIEW)
                .map(r -> new ImportResult.ErrorRow(r.sourceLine(), r.reason().orElse("invalid"), r.rawValues()))
                .toList();

        return new ImportResult(batchId, preview.totalRows(), upserted.created(), upserted.updated(),
                preview.duplicateCount(), preview.needsReviewCount(), preview.dncCount(),
                preview.emptyCount(), errors, errorRows);
    }

    /**
     * Undo a committed import (Phase 3.5): soft-delete the leads <em>created</em> by the
     * batch and detach their list links. Updates to pre-existing leads are not reverted
     * in v1 (no before-images). Returns the number of leads removed.
     */
    public int undo(String batchId) {
        Objects.requireNonNull(batchId, "batchId must not be null");
        return batchRepo.undo(batchId);
    }

    /** Most recent import batches, newest first — powers the "Undo last import" affordance. */
    public List<ImportBatch> recentBatches(int limit) {
        return batchRepo.recentBatches(limit);
    }

    private static UpsertRow toUpsert(PreviewRow r) {
        return new UpsertRow(
                r.firstName(), r.lastName(), r.primaryPhone().orElseThrow(),
                r.company(), r.title(), r.email(), List.of(), r.customFields(), LeadStatus.NEW);
    }
}
