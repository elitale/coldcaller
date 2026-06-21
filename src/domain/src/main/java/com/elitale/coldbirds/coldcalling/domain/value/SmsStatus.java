package com.elitale.coldbirds.coldcalling.domain.value;

/** Delivery state of an SMS message. */
public sealed interface SmsStatus permits
        SmsStatus.Pending,
        SmsStatus.Delivered,
        SmsStatus.Failed {

    record Pending()              implements SmsStatus {}
    record Delivered()            implements SmsStatus {}
    record Failed(String reason)  implements SmsStatus {}
}
