package com.itajay.superassistant.tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The single JSON GET the open-access lookups make (Unpaywall, Crossref).
 *
 * <p>A seam rather than a direct {@link HttpClient} call so the lookup logic — DOI
 * extraction, title matching, location ranking — is tested against captured responses
 * without touching the network.</p>
 */
@FunctionalInterface
interface OpenAccessHttp {

    Response get(URI uri, Duration timeout) throws Exception;

    record Response(int status, String body) {
    }

    static OpenAccessHttp over(HttpClient client, String userAgent) {
        return (uri, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .timeout(timeout)
                    .GET()
                    .build();
            // sendAsync under our own deadline: a stalled host must not block a lookup.
            HttpResponse<String> response = HttpSend.send(client, request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), timeout);
            return new Response(response.statusCode(), response.body());
        };
    }
}
