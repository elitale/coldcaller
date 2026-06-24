package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;

/**
 * Maps an {@link SmsStatus} to the iMessage-style caption shown under the last outbound bubble.
 *
 * <p>Deliberately labels a provider-accepted send <strong>"Sent"</strong>, not "Delivered": send
 * success only means the carrier accepted the message, not that it reached the handset. A true
 * "Delivered" is reserved for a real Twilio status-callback receipt (future). Pure.
 */
public final class SmsStatusLabel {

    private SmsStatusLabel() {}

    /** Human caption: Pending -> "Sending\u2026", Delivered -> "Sent", Failed -> "Failed". */
    public static String label(SmsStatus status) {
        return switch (status) {
            case SmsStatus.Pending ignored   -> "Sending\u2026";
            case SmsStatus.Delivered ignored -> "Sent";
            case SmsStatus.Failed ignored    -> "Failed";
        };
    }

    /** AtlantaFX-style CSS class for the caption colour. */
    public static String styleClass(SmsStatus status) {
        return switch (status) {
            case SmsStatus.Pending ignored   -> "sms-status-sending";
            case SmsStatus.Delivered ignored -> "sms-status-sent";
            case SmsStatus.Failed ignored    -> "sms-status-failed";
        };
    }
}
