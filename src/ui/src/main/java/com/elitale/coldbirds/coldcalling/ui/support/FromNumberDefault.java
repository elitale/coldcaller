package com.elitale.coldbirds.coldcalling.ui.support;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;

/**
 * Picks the sending ("From") number for a thread, lead-aware, so the rep never silently sends from
 * the wrong number. Priority: the number this conversation already uses (continuity) → the pinned /
 * default number → the first active owned number. Pure and side-effect free.
 *
 * <p>Call-continuity and local-area-code presence are a later upgrade (reuse {@code CallerIdSelector});
 * this v1 covers the rock-solid signals.
 */
public final class FromNumberDefault {

    private FromNumberDefault() {}

    public static Optional<OwnedNumber> resolve(
            List<OwnedNumber> owned,
            Optional<PhoneNumberId> continuity,
            Optional<OwnedNumber> pinned) {
        Objects.requireNonNull(owned, "owned must not be null");
        Objects.requireNonNull(continuity, "continuity must not be null");
        Objects.requireNonNull(pinned, "pinned must not be null");

        if (continuity.isPresent()) {
            final Optional<OwnedNumber> match = owned.stream()
                    .filter(o -> o.id().equals(continuity.get()))
                    .findFirst();
            if (match.isPresent()) return match;
        }
        if (pinned.isPresent() && owned.stream().anyMatch(o -> o.id().equals(pinned.get().id()))) {
            return pinned;
        }
        return owned.stream().filter(OwnedNumber::active).findFirst()
                .or(() -> owned.stream().findFirst());
    }
}
