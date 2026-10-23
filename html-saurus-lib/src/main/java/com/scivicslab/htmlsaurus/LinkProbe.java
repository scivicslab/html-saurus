package com.scivicslab.htmlsaurus;

import com.scivicslab.htmlsaurus.ExternalLinkChecker.Kind;
import com.scivicslab.htmlsaurus.ExternalLinkChecker.Outcome;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Asks one URL over HTTP and says whether the page is there.
 *
 * <p>Redirects are followed here, one hop at a time, because a permanent one (301, 308) is a
 * finding in itself: the link works, and the author should write the new address. Only 404 and
 * 410 are "gone". A refusal (403), a rate limit (429), a server error or no answer at all says
 * little about the page, so it is {@link Kind#UNCERTAIN}.</p>
 */
public final class LinkProbe implements ExternalLinkChecker.Probe {

    private static final int MAX_HOPS = 5;

    // HTTP/1.1 only: a cleartext HTTP/2 upgrade offer breaks some servers.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public Outcome probe(String url) {
        String current = url;
        boolean moved = false;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            Answer answer;
            try {
                answer = ask(current);
            } catch (IOException | IllegalArgumentException e) {
                return new Outcome(Kind.UNCERTAIN, 0, reason(e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(Kind.UNCERTAIN, 0, "interrupted");
            }
            int status = answer.status();
            if (status >= 200 && status < 300) {
                return moved ? new Outcome(Kind.MOVED, status, current) : new Outcome(Kind.OK, status, "");
            }
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                if (answer.location().isEmpty()) return new Outcome(Kind.UNCERTAIN, status, "redirect without Location");
                try {
                    current = URI.create(current).resolve(answer.location().get()).toString();
                } catch (IllegalArgumentException e) {
                    return new Outcome(Kind.UNCERTAIN, status, "bad Location: " + answer.location().get());
                }
                if (status == 301 || status == 308) moved = true;
                continue;
            }
            if (status == 404 || status == 410) return new Outcome(Kind.BROKEN, status, current);
            return new Outcome(Kind.UNCERTAIN, status, "HTTP " + status);
        }
        return new Outcome(Kind.UNCERTAIN, 0, "too many redirects");
    }

    private record Answer(int status, Optional<String> location) {}

    /** HEAD first, as it carries no body; some servers refuse it, and are asked again with GET. */
    private Answer ask(String url) throws IOException, InterruptedException {
        Answer head = send(url, "HEAD");
        int s = head.status();
        if (s == 400 || s == 403 || s == 405 || s == 501) return send(url, "GET");
        return head;
    }

    private Answer send(String url, String method) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "html-saurus-link-check")
                .build();
        HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        // The status is known; the body is not wanted, and closing the stream drops it unread.
        response.body().close();
        return new Answer(response.statusCode(), response.headers().firstValue("Location"));
    }

    private static String reason(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }
}
