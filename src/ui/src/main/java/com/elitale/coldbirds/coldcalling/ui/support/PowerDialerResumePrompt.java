package com.elitale.coldbirds.coldcalling.ui.support;

/**
 * Builds the named resume-prompt message shown after a power-dialer session was
 * paused-and-held because the app went offline. The prompt names the lead and the
 * position so the rep can decide deliberately — the dialer never auto-resumes.
 */
public final class PowerDialerResumePrompt {

    private PowerDialerResumePrompt() {}

    /**
     * @param leadName  current lead's display name (may be {@code null} or blank)
     * @param position  zero-based index of the current lead within the call list
     * @param remaining leads left to dial, including the current one
     * @return e.g. {@code "Resume with Maria Lopez — 47 of 120?"}
     */
    public static String message(String leadName, int position, int remaining) {
        final int current = Math.max(position, 0) + 1;
        final int total = Math.max(Math.max(position, 0) + Math.max(remaining, 0), current);
        final String where = current + " of " + total;
        final String name = leadName == null ? "" : leadName.trim();
        return name.isEmpty()
                ? "Resume power dialer \u2014 " + where + "?"
                : "Resume with " + name + " \u2014 " + where + "?";
    }
}
