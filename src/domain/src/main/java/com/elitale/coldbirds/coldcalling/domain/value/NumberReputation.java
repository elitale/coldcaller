package com.elitale.coldbirds.coldcalling.domain.value;

/** Reputation status of an owned phone number. */
public sealed interface NumberReputation permits
        NumberReputation.Clean,
        NumberReputation.Warning,
        NumberReputation.Flagged {

    record Clean()                  implements NumberReputation {}
    record Warning(String reason)   implements NumberReputation {}
    record Flagged(String reason)   implements NumberReputation {}
}
