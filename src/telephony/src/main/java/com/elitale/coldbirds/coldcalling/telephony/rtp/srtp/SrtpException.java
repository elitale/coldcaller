package com.elitale.coldbirds.coldcalling.telephony.rtp.srtp;

/** Thrown when an SRTP packet fails authentication or cannot be processed. */
public final class SrtpException extends Exception {
    public SrtpException(final String message) {
        super(message);
    }
}
