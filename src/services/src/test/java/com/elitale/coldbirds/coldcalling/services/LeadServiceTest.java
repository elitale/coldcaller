package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeadServiceTest {

    @Mock LeadRepository repo;
    LeadService service;

    private static final PhoneNumber PHONE = new PhoneNumber("+12025551234");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new LeadService(repo);
    }

    @Test
    void findAll_delegatesToRepo() {
        when(repo.findAll()).thenReturn(List.of());
        assertThat(service.findAll()).isEmpty();
        verify(repo).findAll();
    }

    @Test
    void search_delegatesToRepo() {
        when(repo.search("Alice")).thenReturn(List.of());
        service.search("Alice");
        verify(repo).search("Alice");
    }

    @Test
    void isDnc_trueWhenLeadMarked() {
        when(repo.findByPhone(PHONE)).thenReturn(Optional.of(dncLead(true)));
        assertThat(service.isDnc(PHONE)).isTrue();
    }

    @Test
    void isDnc_falseWhenLeadNotMarked() {
        when(repo.findByPhone(PHONE)).thenReturn(Optional.of(dncLead(false)));
        assertThat(service.isDnc(PHONE)).isFalse();
    }

    @Test
    void isDnc_falseWhenLeadUnknown() {
        when(repo.findByPhone(PHONE)).thenReturn(Optional.empty());
        assertThat(service.isDnc(PHONE)).isFalse();
    }

    @Test
    void save_delegatesToRepo() {
        // LeadService.NewLead is the service-layer DTO; internally it
        // maps to LeadRepository.NewLead before calling repo.save().
        final var nc = new LeadService.NewLead(
                Optional.of("Alice"), Optional.of("Smith"), PHONE,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty()
        );
        final var repoNc = new LeadRepository.NewLead(
                nc.firstName(), nc.lastName(), nc.phone(),
                nc.company(), nc.title(), nc.email(), nc.tags(), nc.notes()
        );
        when(repo.save(repoNc)).thenReturn(Result.err("stub"));
        service.save(nc);
        verify(repo).save(repoNc);
    }

    @Test
    void delete_delegatesToRepo() {
        when(repo.delete(new LeadId(1L))).thenReturn(Result.ok(null));
        service.delete(new LeadId(1L));
        verify(repo).delete(new LeadId(1L));
    }

    @Test
    void findPage_delegatesToRepo() {
        final LeadFilter filter = LeadFilter.builder().search("acme").build();
        final Page<Lead> page = new Page<>(List.of(), Optional.empty(), 0);
        when(repo.findPage(filter)).thenReturn(page);
        assertThat(service.findPage(filter)).isSameAs(page);
        verify(repo).findPage(filter);
    }

    @Test
    void findPage_nullFilterRejected() {
        assertThatNullPointerException().isThrownBy(() -> service.findPage(null));
    }

    @Test
    void customFieldKeys_delegatesToRepo() {
        when(repo.customFieldKeys(Optional.empty())).thenReturn(List.of("Source"));
        assertThat(service.customFieldKeys(Optional.empty())).containsExactly("Source");
        verify(repo).customFieldKeys(Optional.empty());
    }

    @Test
    void distinctTags_delegatesToRepo() {
        when(repo.distinctTags()).thenReturn(List.of("vip"));
        assertThat(service.distinctTags()).containsExactly("vip");
        verify(repo).distinctTags();
    }

    @Test
    void bulkDelete_delegatesToRepo() {
        final List<LeadId> ids = List.of(new LeadId(1L), new LeadId(2L));
        when(repo.bulkSoftDelete(ids)).thenReturn(2);
        assertThat(service.bulkDelete(ids)).isEqualTo(2);
        verify(repo).bulkSoftDelete(ids);
    }

    @Test
    void bulkSetStatus_delegatesToRepo() {
        final List<LeadId> ids = List.of(new LeadId(1L));
        when(repo.bulkSetStatus(ids, LeadStatus.INTERESTED)).thenReturn(1);
        assertThat(service.bulkSetStatus(ids, LeadStatus.INTERESTED)).isEqualTo(1);
        verify(repo).bulkSetStatus(ids, LeadStatus.INTERESTED);
    }

    @Test
    void bulkSetDnc_delegatesToRepo() {
        final List<LeadId> ids = List.of(new LeadId(1L));
        when(repo.bulkSetDnc(ids, true)).thenReturn(1);
        assertThat(service.bulkSetDnc(ids, true)).isEqualTo(1);
        verify(repo).bulkSetDnc(ids, true);
    }

    @Test
    void setCustomField_loadsModifiesAndUpdates() {
        final Lead lead = dncLead(false);
        when(repo.findById(new LeadId(1L))).thenReturn(Optional.of(lead));
        when(repo.update(any(Lead.class))).thenAnswer(inv -> Result.ok(inv.getArgument(0)));

        service.setCustomField(new LeadId(1L), "Source", "Apollo");

        final ArgumentCaptor<Lead> captor = ArgumentCaptor.forClass(Lead.class);
        verify(repo).update(captor.capture());
        assertThat(captor.getValue().customFields()).containsEntry("Source", "Apollo");
    }

    @Test
    void setCustomField_missingLeadReturnsErr() {
        when(repo.findById(new LeadId(9L))).thenReturn(Optional.empty());
        assertThat(service.setCustomField(new LeadId(9L), "Source", "x").isErr()).isTrue();
        verify(repo, never()).update(any());
    }

    @Test
    void setCustomField_blankKeyRejected() {
        assertThat(service.setCustomField(new LeadId(1L), "  ", "x").isErr()).isTrue();
        verify(repo, never()).findById(any());
    }

    private static Lead dncLead(boolean dnc) {
        return new Lead(
                new LeadId(1L),
                Optional.empty(), Optional.empty(),
                PHONE,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(),
                dnc, Map.of(), LeadStatus.NEW, Instant.now(), Instant.now()
        );
    }
}
