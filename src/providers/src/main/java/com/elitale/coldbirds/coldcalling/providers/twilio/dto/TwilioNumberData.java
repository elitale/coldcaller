package com.elitale.coldbirds.coldcalling.providers.twilio.dto;

/**
 * Represents a single phone number entry in a Twilio
 * {@code GET /2010-04-01/Accounts/{sid}/IncomingPhoneNumbers.json} response.
 * <p>
 * Twilio JSON field name {@code phone_number} is mapped manually via
 * {@link com.fasterxml.jackson.databind.ObjectMapper} tree traversal in
 * {@link com.elitale.coldbirds.coldcalling.providers.twilio.TwilioClient}.
 * The {@code id} carries the Twilio resource SID ({@code PNxxxxxxxx}).
 */
public record TwilioNumberData(String id, String phoneNumber, String status) {
}
