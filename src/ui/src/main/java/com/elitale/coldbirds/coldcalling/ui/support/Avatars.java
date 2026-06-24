package com.elitale.coldbirds.coldcalling.ui.support;

/**
 * Monogram avatar helpers for the iMessage-style conversation list: initials and a deterministic
 * colour bucket derived from a stable key (name or number), so the same contact always gets the
 * same colour. Pure and side-effect free.
 */
public final class Avatars {

    private static final int PALETTE = 8;

    private Avatars() {}

    /** Up to two uppercase initials; "#" for a number, "?" when blank. */
    public static String initials(String name) {
        if (name == null) return "?";
        final String s = name.strip();
        if (s.isEmpty()) return "?";
        if (s.charAt(0) == '+' || Character.isDigit(s.charAt(0))) return "#";
        final String[] parts = s.split("\\s+");
        final StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(parts[0].charAt(0)));
        if (parts.length > 1) {
            sb.append(Character.toUpperCase(parts[parts.length - 1].charAt(0)));
        }
        return sb.toString();
    }

    /** Stable colour bucket in {@code [0, paletteSize())} for {@code key}. */
    public static int colorIndex(String key) {
        if (key == null || key.isEmpty()) return 0;
        return Math.floorMod(key.hashCode(), PALETTE);
    }

    /** Number of distinct avatar colours. */
    public static int paletteSize() {
        return PALETTE;
    }
}
