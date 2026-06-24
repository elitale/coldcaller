package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.services.imports.ColumnAutoDetector;
import com.elitale.coldbirds.coldcalling.services.imports.ColumnMapping;
import com.elitale.coldbirds.coldcalling.services.imports.ImportPreview;
import com.elitale.coldbirds.coldcalling.services.imports.ImportResult;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.ImportBatchRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadImportRepository;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadImportRepository.UpsertResult;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadImportRepository.UpsertRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

final class LeadImportServiceTest {

    private static final List<String> HEADERS = List.of("First Name", "Mobile Phone", "Company");
    private static final Optional<String> US = Optional.of("US");

    private LeadImportRepository importRepo;
    private ImportBatchRepository batchRepo;
    private CallListRepository callListRepo;
    private LeadImportService service;
    private ColumnMapping mapping;

    @BeforeEach
    void setUp() {
        importRepo = mock(LeadImportRepository.class);
        batchRepo = mock(ImportBatchRepository.class);
        callListRepo = mock(CallListRepository.class);
        service = new LeadImportService(new PhoneNormalizer(), importRepo, batchRepo, callListRepo);
        mapping = ColumnAutoDetector.detect(HEADERS, List.of());
        when(importRepo.findDncPhones(any())).thenReturn(Set.of());
    }

    private static List<List<String>> sampleRows() {
        return List.of(
                List.of("Ann", "(415) 555-1234", "Acme"),
                List.of("Bob", "415-555-9999", "Globex"),
                List.of("Cara", "not a phone", "X"),
                List.of("Dan", "(415) 555-1234", "Dup"),
                List.of("Eve", "", "Empty"));
    }

    @Test
    void previewClassifiesValidReviewDuplicateAndEmpty() {
        ImportPreview preview = service.preview(sampleRows(), mapping, US);

        assertThat(preview.totalRows()).isEqualTo(5);
        assertThat(preview.validCount()).isEqualTo(2);
        assertThat(preview.needsReviewCount()).isEqualTo(1);
        assertThat(preview.duplicateCount()).isEqualTo(1);
        assertThat(preview.emptyCount()).isEqualTo(1);
        assertThat(preview.dncCount()).isZero();
    }

    @Test
    void previewFlagsDncFromRepository() {
        when(importRepo.findDncPhones(any())).thenReturn(Set.of("+14155559999"));

        ImportPreview preview = service.preview(sampleRows(), mapping, US);

        assertThat(preview.dncCount()).isEqualTo(1);
        assertThat(preview.validCount()).isEqualTo(1);
    }

    @Test
    void commitUpsertsValidRowsAndAttachesToList() {
        when(importRepo.bulkUpsert(any(), anyString())).thenReturn(new UpsertResult(2, 0));
        when(importRepo.findLiveIdsByPhones(any()))
                .thenReturn(List.of(new LeadId(1), new LeadId(2)));
        ImportPreview preview = service.preview(sampleRows(), mapping, US);

        ImportResult result = service.commit(preview, "apollo.csv", US, Optional.of(new CallListId(7)));

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.skippedDuplicate()).isEqualTo(1);
        assertThat(result.skippedInvalid()).isEqualTo(1);
        assertThat(result.skippedEmpty()).isEqualTo(1);
        // reconciliation: file rows == every disposition summed
        assertThat(result.created() + result.updated() + result.skippedDuplicate()
                + result.skippedInvalid() + result.skippedDnc() + result.skippedEmpty()
                + result.errors()).isEqualTo(result.totalRows());
        verify(callListRepo).addLeads(eq(new CallListId(7)), any());
        verify(batchRepo).record(any());
    }

    @Test
    void commitOnlyUpsertsTwoRowsFromTheFiveRowFile() {
        when(importRepo.bulkUpsert(any(), anyString())).thenReturn(new UpsertResult(2, 0));
        ImportPreview preview = service.preview(sampleRows(), mapping, US);

        service.commit(preview, "apollo.csv", US, Optional.empty());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<UpsertRow>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(importRepo).bulkUpsert(captor.capture(), anyString());
        assertThat(captor.getValue()).hasSize(2);
    }
}
