package com.elitale.coldbirds.coldcalling.domain.value;

/** Reason a call ended. */
public sealed interface EndReason permits
        EndReason.HungUp,
        EndReason.RemoteHungUp,
        EndReason.Timeout,
        EndReason.Rejected,
        EndReason.Busy,
        EndReason.Error {

    record HungUp()        implements EndReason {}
    record RemoteHungUp()  implements EndReason {}
    record Timeout()       implements EndReason {}
    record Rejected()      implements EndReason {}
    record Busy()          implements EndReason {}
    record Error(String message) implements EndReason {}
}
