package com.elitale.coldbirds.coldcalling.services;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.storage.repository.CallRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chooses which owned number places an outbound call.
 *
 * <p>Two rules, in order:
 * <ol>
 *   <li><b>Sticky</b> — if this prospect was already called from one of our numbers and
 *       that number is still in the active pool, reuse it. The prospect always sees the
 *       same caller-ID on follow-up calls.</li>
 *   <li><b>Rotate</b> — otherwise round-robin across the active pool so usage is spread
 *       evenly and no single number is over-dialled.</li>
 * </ol>
 *
 * <p>The rotation cursor is process-local; it advances only when a fresh (non-sticky)
 * number is handed out. When no numbers are active, falls back to the configured default.
 *
 * <p>Thread-safe: the only mutable state is an {@link AtomicInteger} cursor.
 */
public final class CallerIdSelector {

    private final PhoneNumberService phoneNumbers;
    private final CallRepository     calls;
    private final AtomicInteger      cursor = new AtomicInteger();

    public CallerIdSelector(PhoneNumberService phoneNumbers, CallRepository calls) {
        this.phoneNumbers = Objects.requireNonNull(phoneNumbers, "phoneNumbers must not be null");
        this.calls        = Objects.requireNonNull(calls,        "calls must not be null");
    }

    /**
     * Pick the caller-ID for a call to {@code remote}: sticky number first, then rotation,
     * then the configured default when the pool is empty.
     *
     * @param remote the E.164 number being dialled
     * @return the owned number to dial from, or empty when no usable number exists
     */
    public Optional<OwnedNumber> selectFor(PhoneNumber remote) {
        Objects.requireNonNull(remote, "remote must not be null");
        final List<OwnedNumber> pool = phoneNumbers.listOwned();
        if (pool.isEmpty()) return phoneNumbers.getDefault();

        final Optional<OwnedNumber> sticky = sticky(remote, pool);
        if (sticky.isPresent()) return sticky;

        final int index = Math.floorMod(cursor.getAndIncrement(), pool.size());
        return Optional.of(pool.get(index));
    }

    /** The number this prospect was last called from, if it is still in the active pool. */
    private Optional<OwnedNumber> sticky(PhoneNumber remote, List<OwnedNumber> pool) {
        return calls.findByRemoteNumber(remote).stream()
                .filter(call -> call.direction() == CallDirection.OUTBOUND)
                .max(Comparator.comparing(Call::startedAt))
                .map(Call::phoneNumberId)
                .flatMap(id -> pool.stream().filter(number -> number.id().equals(id)).findFirst());
    }
}
