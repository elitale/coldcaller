package com.elitale.coldbirds.coldcalling.domain.routing;

/**
 * How a provider routes a registered SIP client's outbound call to the PSTN.
 *
 * <ul>
 *   <li>{@link #NONE} — not configured; outbound calls will not bridge to the PSTN.</li>
 *   <li>{@link #MANUAL} — the user supplied an explicit Voice webhook URL.</li>
 *   <li>{@link #AUTO} — the app resolved + applied a managed bridge URL for the provider.</li>
 * </ul>
 *
 * <p>A three-constant mode with no associated data — an enum rather than a sealed
 * interface (AGENTS §5.1 reserves sealed sum types for variants carrying data).
 */
public enum CallRoutingMode {
    NONE,
    MANUAL,
    AUTO
}
