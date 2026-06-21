package com.elitale.coldbirds.coldcalling.domain.value;

/** Running state of a power dialer session. */
public sealed interface PowerDialerState permits
        PowerDialerState.Stopped,
        PowerDialerState.Running,
        PowerDialerState.Paused {

    record Stopped() implements PowerDialerState {}
    record Running() implements PowerDialerState {}
    record Paused()  implements PowerDialerState {}
}
