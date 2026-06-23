package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.Country;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A single row in the dialer's "Recent Calls" list, aggregated per phone number.
 *
 * <p>Renders as two lines:
 * <pre>
 *   [lead name or number]   [last call ago]   [total calls]
 *   [flag] [country]   [local time] [timezone]      [Call] [Message]
 * </pre>
 *
 * @param number     the remote party's E.164 number (used to call / message / open detail)
 * @param lastCallAt timestamp of the most recent call to this number
 * @param callCount  total number of calls ever made to/from this number
 * @param country    the resolved country for this number, or empty if unknown
 * @param lead       the matching saved lead, or empty if this number is not in leads
 */
public record RecentCallRow(
        String number,
        Instant lastCallAt,
        int callCount,
        Optional<Country> country,
        Optional<Lead> lead) {

    public RecentCallRow {
        Objects.requireNonNull(number, "number must not be null");
        Objects.requireNonNull(lastCallAt, "lastCallAt must not be null");
        Objects.requireNonNull(country, "country must not be null");
        Objects.requireNonNull(lead, "lead must not be null");
        if (callCount < 0) {
            throw new IllegalArgumentException("callCount must not be negative: " + callCount);
        }
    }
}
