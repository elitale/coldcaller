package com.elitale.coldbirds.coldcalling.domain.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.PowerDialerState;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Locks the {@link PowerDialerSession} invariants. This record is rebuilt on every UI tick from the
 * live dialer state, so any violation throws on the FX thread and crashes the app — the constructor
 * is the last line of defence and must reject every illegal combination.
 */
class PowerDialerSessionTest {

  private static final CallListId LIST = new CallListId(1L);
  private static final Instant NOW = Instant.now();

  private static PowerDialerSession session(int dialedCount, int connectedCount) {
    return new PowerDialerSession(
        0L,
        LIST,
        0,
        new PowerDialerState.Running(),
        dialedCount,
        connectedCount,
        NOW,
        Optional.empty());
  }

  // ── counter invariants ──────────────────────────────────────────────────────

  @Test
  void rejectsConnectedExceedingDialed() {
    assertThatThrownBy(() -> session(1, 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connectedCount cannot exceed dialedCount");
  }

  @Test
  void allowsConnectedEqualToDialed() {
    assertThatCode(() -> session(3, 3)).doesNotThrowAnyException();
  }

  @Test
  void allowsConnectedBelowDialed() {
    assertThatCode(() -> session(5, 2)).doesNotThrowAnyException();
  }

  @Test
  void allowsZeroCounts() {
    assertThatCode(() -> session(0, 0)).doesNotThrowAnyException();
  }

  @Test
  void rejectsNegativeDialed() {
    assertThatThrownBy(() -> session(-1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dialedCount must be >= 0");
  }

  @Test
  void rejectsNegativeConnected() {
    assertThatThrownBy(() -> session(0, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connectedCount must be >= 0");
  }

  @Test
  void rejectsNegativePosition() {
    assertThatThrownBy(
            () ->
                new PowerDialerSession(
                    0L, LIST, -1, new PowerDialerState.Running(), 0, 0, NOW, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currentPosition must be >= 0");
  }

  // ── null guards ──────────────────────────────────────────────────────────────

  @Test
  void rejectsNullCallListId() {
    assertThatThrownBy(
            () ->
                new PowerDialerSession(
                    0L, null, 0, new PowerDialerState.Running(), 0, 0, NOW, Optional.empty()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("callListId");
  }

  @Test
  void rejectsNullState() {
    assertThatThrownBy(() -> new PowerDialerSession(0L, LIST, 0, null, 0, 0, NOW, Optional.empty()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("state");
  }

  @Test
  void rejectsNullStartedAt() {
    assertThatThrownBy(
            () ->
                new PowerDialerSession(
                    0L, LIST, 0, new PowerDialerState.Running(), 0, 0, null, Optional.empty()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("startedAt");
  }

  @Test
  void rejectsNullEndedAt() {
    assertThatThrownBy(
            () ->
                new PowerDialerSession(
                    0L, LIST, 0, new PowerDialerState.Running(), 0, 0, NOW, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("endedAt");
  }

  // ── happy path ───────────────────────────────────────────────────────────────

  @Test
  void buildsValidSession() {
    PowerDialerSession s =
        new PowerDialerSession(
            7L, LIST, 4, new PowerDialerState.Paused(), 10, 4, NOW, Optional.of(NOW));
    assertThatCode(() -> s.connectedCount()).doesNotThrowAnyException();
  }
}
