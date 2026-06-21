package com.elitale.coldbirds.coldcalling.providers.telnyx;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Single-method abstraction over {@link java.net.http.HttpClient#send}.
 * Exists solely to enable deterministic unit testing of {@link TelnyxClient}
 * without a real network connection.
 */
@FunctionalInterface
interface HttpSender {
    HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
}
