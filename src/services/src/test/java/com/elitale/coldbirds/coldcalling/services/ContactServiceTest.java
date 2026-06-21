package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.Contact;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.storage.repository.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContactServiceTest {

    @Mock ContactRepository repo;
    ContactService service;

    private static final PhoneNumber PHONE = new PhoneNumber("+12025551234");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ContactService(repo);
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
    void isDnc_trueWhenContactMarked() {
        when(repo.findByPhone(PHONE)).thenReturn(Optional.of(dncContact(true)));
        assertThat(service.isDnc(PHONE)).isTrue();
    }

    @Test
    void isDnc_falseWhenContactNotMarked() {
        when(repo.findByPhone(PHONE)).thenReturn(Optional.of(dncContact(false)));
        assertThat(service.isDnc(PHONE)).isFalse();
    }

    @Test
    void isDnc_falseWhenContactUnknown() {
        when(repo.findByPhone(PHONE)).thenReturn(Optional.empty());
        assertThat(service.isDnc(PHONE)).isFalse();
    }

    @Test
    void save_delegatesToRepo() {
        // ContactService.NewContact is the service-layer DTO; internally it
        // maps to ContactRepository.NewContact before calling repo.save().
        final var nc = new ContactService.NewContact(
                Optional.of("Alice"), Optional.of("Smith"), PHONE,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty()
        );
        final var repoNc = new ContactRepository.NewContact(
                nc.firstName(), nc.lastName(), nc.phone(),
                nc.company(), nc.title(), nc.email(), nc.tags(), nc.notes()
        );
        when(repo.save(repoNc)).thenReturn(Result.err("stub"));
        service.save(nc);
        verify(repo).save(repoNc);
    }

    @Test
    void delete_delegatesToRepo() {
        when(repo.delete(new ContactId(1L))).thenReturn(Result.ok(null));
        service.delete(new ContactId(1L));
        verify(repo).delete(new ContactId(1L));
    }

    private static Contact dncContact(boolean dnc) {
        return new Contact(
                new ContactId(1L),
                Optional.empty(), Optional.empty(),
                PHONE,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(),
                dnc, Instant.now(), Instant.now()
        );
    }
}
