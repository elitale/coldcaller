package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.*;
import com.elitale.coldbirds.coldcalling.storage.repository.CallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CallerIdSelectorTest {

    @Mock PhoneNumberService phoneNumbers;
    @Mock CallRepository     calls;

    CallerIdSelector selector;

    static final PhoneNumberId ID_A   = new PhoneNumberId(1L);
    static final PhoneNumberId ID_B   = new PhoneNumberId(2L);
    static final PhoneNumber   NUM_A  = new PhoneNumber("+12025550001");
    static final PhoneNumber   NUM_B  = new PhoneNumber("+12025550002");
    static final PhoneNumber   REMOTE  = new PhoneNumber("+13105550100");
    static final PhoneNumber   REMOTE2 = new PhoneNumber("+13105550200");
    static final PhoneNumber   REMOTE3 = new PhoneNumber("+13105550300");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        selector = new CallerIdSelector(phoneNumbers, calls);
    }

    @Test
    void emptyPool_fallsBackToDefault() {
        when(phoneNumbers.listOwned()).thenReturn(List.of());
        final OwnedNumber def = owned(ID_A, NUM_A);
        when(phoneNumbers.getDefault()).thenReturn(Optional.of(def));

        assertThat(selector.selectFor(REMOTE)).contains(def);
        verify(calls, never()).findByRemoteNumber(any());
    }

    @Test
    void emptyPool_noDefault_returnsEmpty() {
        when(phoneNumbers.listOwned()).thenReturn(List.of());
        when(phoneNumbers.getDefault()).thenReturn(Optional.empty());

        assertThat(selector.selectFor(REMOTE)).isEmpty();
    }

    @Test
    void firstCalls_rotateRoundRobinAcrossPool() {
        when(phoneNumbers.listOwned()).thenReturn(List.of(owned(ID_A, NUM_A), owned(ID_B, NUM_B)));
        when(calls.findByRemoteNumber(any())).thenReturn(List.of());

        assertThat(selector.selectFor(REMOTE).map(OwnedNumber::id)).contains(ID_A);
        assertThat(selector.selectFor(REMOTE2).map(OwnedNumber::id)).contains(ID_B);
        assertThat(selector.selectFor(REMOTE3).map(OwnedNumber::id)).contains(ID_A); // wraps
    }

    @Test
    void repeatCall_reusesStickyNumber_withoutAdvancingCursor() {
        when(phoneNumbers.listOwned()).thenReturn(List.of(owned(ID_A, NUM_A), owned(ID_B, NUM_B)));
        // This prospect was last called from B → sticky to B.
        when(calls.findByRemoteNumber(REMOTE))
                .thenReturn(List.of(call(CallDirection.OUTBOUND, ID_B, REMOTE, Instant.now())));
        when(calls.findByRemoteNumber(REMOTE2)).thenReturn(List.of());

        assertThat(selector.selectFor(REMOTE).map(OwnedNumber::id)).contains(ID_B);
        // Sticky must not consume a rotation slot: a fresh prospect still starts at A.
        assertThat(selector.selectFor(REMOTE2).map(OwnedNumber::id)).contains(ID_A);
    }

    @Test
    void sticky_usesMostRecentOutboundCall() {
        when(phoneNumbers.listOwned()).thenReturn(List.of(owned(ID_A, NUM_A), owned(ID_B, NUM_B)));
        final Instant older = Instant.now().minusSeconds(100);
        final Instant newer = Instant.now();
        when(calls.findByRemoteNumber(REMOTE)).thenReturn(List.of(
                call(CallDirection.OUTBOUND, ID_A, REMOTE, older),
                call(CallDirection.OUTBOUND, ID_B, REMOTE, newer)));

        assertThat(selector.selectFor(REMOTE).map(OwnedNumber::id)).contains(ID_B);
    }

    @Test
    void stickyNumberNoLongerInPool_fallsBackToRotation() {
        // Only A is active now; B was deactivated since the last call.
        when(phoneNumbers.listOwned()).thenReturn(List.of(owned(ID_A, NUM_A)));
        when(calls.findByRemoteNumber(REMOTE))
                .thenReturn(List.of(call(CallDirection.OUTBOUND, ID_B, REMOTE, Instant.now())));

        assertThat(selector.selectFor(REMOTE).map(OwnedNumber::id)).contains(ID_A);
    }

    @Test
    void inboundHistoryIsIgnoredForStickiness() {
        when(phoneNumbers.listOwned()).thenReturn(List.of(owned(ID_A, NUM_A), owned(ID_B, NUM_B)));
        when(calls.findByRemoteNumber(REMOTE))
                .thenReturn(List.of(call(CallDirection.INBOUND, ID_B, REMOTE, Instant.now())));

        // No outbound history → first rotation pick (A), not the inbound number (B).
        assertThat(selector.selectFor(REMOTE).map(OwnedNumber::id)).contains(ID_A);
    }

    @Test
    void selectFor_nullRemote_throws() {
        assertThatThrownBy(() -> selector.selectFor(null)).isInstanceOf(NullPointerException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static OwnedNumber owned(PhoneNumberId id, PhoneNumber number) {
        return new OwnedNumber(id, number, Optional.empty(), new AreaCode("202"), "twilio",
                new NumberReputation.Clean(), 0, true, Instant.now(), Instant.now());
    }

    private static Call call(CallDirection direction, PhoneNumberId localId,
                             PhoneNumber remote, Instant startedAt) {
        return new Call(new CallId(1L), direction, localId, Optional.empty(), remote,
                Optional.empty(), startedAt, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), startedAt, startedAt);
    }
}
