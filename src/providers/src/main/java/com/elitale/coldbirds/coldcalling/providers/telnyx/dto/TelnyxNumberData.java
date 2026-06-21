package com.elitale.coldbirds.coldcalling.providers.telnyx.dto;

/**
 * Represents a single phone number entry in a Telnyx GET /v2/phone_numbers response.
 * <p>
 * Telnyx JSON field name {@code phone_number} is mapped manually via
 * {@link com.fasterxml.jackson.databind.ObjectMapper} tree traversal in
 * {@link com.elitale.coldbirds.coldcalling.providers.telnyx.TelnyxClient}.
 */
public record TelnyxNumberData(String id, String phoneNumber, String status) {
}
