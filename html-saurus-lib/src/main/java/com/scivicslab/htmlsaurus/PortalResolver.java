package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Asks a running portal whether a document id has a document, through {@code GET /api/resolve}.
 *
 * <p>The portal answers 200 with the document's address, or 404 for an id it does not know. Any
 * other answer, and a portal that cannot be reached, is an {@link IOException}: "I could not ask"
 * must not be reported as "no such document".</p>
 */
public final class PortalResolver implements DocIdChecker.Resolver {

    private final String baseUrl;
    // HTTP/1.1 only: a cleartext HTTP/2 upgrade offer breaks some servers behind the portal.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** @param baseUrl the portal's address, such as {@code http://localhost:28001} */
    public PortalResolver(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public boolean exists(String id) throws IOException {
        URI uri = URI.create(baseUrl + "/api/resolve?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8));
        try {
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return switch (response.statusCode()) {
                case 200 -> true;
                case 404 -> false;
                default -> throw new IOException("the portal answered " + response.statusCode()
                        + " for " + uri);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while asking the portal about " + id, e);
        }
    }
}
