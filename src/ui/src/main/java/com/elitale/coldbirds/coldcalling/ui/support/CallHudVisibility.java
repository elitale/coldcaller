package com.elitale.coldbirds.coldcalling.ui.support;

/** Pure decision for when the Mini Call HUD (the alt-tab call pill) should be on screen. */
public final class CallHudVisibility {

    private CallHudVisibility() {
    }

    /**
     * The HUD is shown only while a call is live <em>and</em> the main window is unfocused:
     * it is the alt-tab pill, so it must never compete with the on-screen call card.
     *
     * @param callLive    whether a call is currently active (or on hold) on screen
     * @param mainFocused whether the primary window currently holds OS focus
     * @return {@code true} when the HUD should be visible
     */
    public static boolean shouldShow(boolean callLive, boolean mainFocused) {
        return callLive && !mainFocused;
    }
}
