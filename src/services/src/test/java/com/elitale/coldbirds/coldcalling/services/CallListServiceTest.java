package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallListServiceTest {

    @Mock CallListRepository repo;
    CallListService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CallListService(repo);
    }

    @Test
    void listsWithCounts_pairsEachListWithItsCount() {
        CallList a = list(1L, "A");
        CallList b = list(2L, "B");
        when(repo.findAll()).thenReturn(List.of(a, b));
        when(repo.countLeads(new CallListId(1L))).thenReturn(5);
        when(repo.countLeads(new CallListId(2L))).thenReturn(0);

        List<CallListService.ListSummary> summaries = service.listsWithCounts();

        assertThat(summaries).extracting(CallListService.ListSummary::leadCount)
                .containsExactly(5, 0);
        assertThat(summaries).extracting(s -> s.list().name()).containsExactly("A", "B");
    }

    @Test
    void create_rejectsBlankAndDelegatesOtherwise() {
        assertThat(service.create("  ").isErr()).isTrue();
        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());

        when(repo.save(new CallListRepository.NewCallList("VIPs", Optional.empty())))
                .thenReturn(Result.ok(list(3L, "VIPs")));
        assertThat(service.create("VIPs").isOk()).isTrue();
    }

    @Test
    void rename_delegatesToRepo() {
        when(repo.rename(new CallListId(1L), "New")).thenReturn(Result.ok(list(1L, "New")));
        assertThat(service.rename(new CallListId(1L), "New").isOk()).isTrue();
        verify(repo).rename(new CallListId(1L), "New");
    }

    @Test
    void addLeads_andRemoveLead_delegate() {
        when(repo.addLeads(new CallListId(1L), List.of(new LeadId(7L)))).thenReturn(1);
        assertThat(service.addLeads(new CallListId(1L), List.of(new LeadId(7L)))).isEqualTo(1);

        when(repo.removeLead(new CallListId(1L), new LeadId(7L))).thenReturn(true);
        assertThat(service.removeLead(new CallListId(1L), new LeadId(7L))).isTrue();
    }

    @Test
    void nullArgumentsRejected() {
        assertThatNullPointerException().isThrownBy(() -> service.create(null));
        assertThatNullPointerException().isThrownBy(() -> service.addLeads(null, List.of()));
    }

    private static CallList list(long id, String name) {
        return new CallList(new CallListId(id), name, Optional.empty(),
                List.of(), Instant.now(), Instant.now());
    }
}
