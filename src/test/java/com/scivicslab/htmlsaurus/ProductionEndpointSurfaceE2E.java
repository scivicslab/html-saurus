package com.scivicslab.htmlsaurus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * E2E test verifying that an already-running production-mode deployment exposes exactly the
 * endpoint surface specified in {@code ProductionModeSpec_260806_oo01} (doc_SCIVICS002,
 * html-saurus/010_concepts) — no more, no less — and that {@code /search} never returns
 * {@code srcPath}.
 *
 * <p>This does not start a server: per the testing standard (see
 * {@code TestingStandard_260404_oo01}, doc_SCIVICS001), an E2E test connects to an environment
 * someone else already brought up. Start one first:
 * <pre>
 *   java -jar html-saurus.jar &lt;docusaurus-project&gt; --production --port 28001
 * </pre>
 *
 * <p>Run:
 * <pre>
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.htmlsaurus.ProductionEndpointSurfaceE2E \
 *     -Dexec.classpathScope=test
 *
 *   # Override URL:
 *   PRODUCTION_URL=http://localhost:28010 mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.htmlsaurus.ProductionEndpointSurfaceE2E \
 *     -Dexec.classpathScope=test
 * </pre>
 */
public class ProductionEndpointSurfaceE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("PRODUCTION_URL", "http://localhost:28001");

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static int passed = 0;
    private static int failed = 0;

    record Probe(String method, String path) {}

    // Must match the "エンドポイント一覧" table in ProductionModeSpec_260806_oo01 exactly.
    private static final List<Probe> OPEN = List.of(
            new Probe("GET", "/"),
            new Probe("GET", "/search?q=a")
    );

    private static final List<Probe> CLOSED = List.of(
            new Probe("GET", "/mcp"),
            new Probe("POST", "/api/build-all"),
            new Probe("GET", "/api/related?path=/"),
            new Probe("POST", "/api/find-related"),
            new Probe("GET", "/api/related-semantic?path=/"),
            new Probe("GET", "/related-semantic?path=/"),
            new Probe("GET", "/api/search-semantic?q=a"),
            new Probe("GET", "/search-semantic?q=a"),
            new Probe("POST", "/api/translate?lang=English")
    );

    public static void main(String[] args) throws Exception {
        System.out.println("=== Production Endpoint Surface E2E: " + BASE_URL + " ===");

        for (Probe p : OPEN) {
            check("OPEN " + p.method() + " " + p.path() + " returns 200",
                    status(p) == 200,
                    "expected 200, got " + status(p));
        }

        for (Probe p : CLOSED) {
            check("CLOSED " + p.method() + " " + p.path() + " does not return 200",
                    status(p) != 200,
                    "expected non-200 (this endpoint must be closed in production), got 200");
        }

        check("GET /search response never includes srcPath",
                !body("GET", "/search?q=a").contains("srcPath"),
                "response includes srcPath (a local filesystem path) — this must never reach a public reader");

        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static int status(Probe p) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(BASE_URL + p.path()));
        builder = p.method().equals("POST") ? builder.POST(HttpRequest.BodyPublishers.noBody()) : builder.GET();
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String body(String method, String path) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(BASE_URL + path));
        builder = method.equals("POST") ? builder.POST(HttpRequest.BodyPublishers.noBody()) : builder.GET();
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void check(String name, boolean condition, String failureMessage) {
        if (condition) {
            System.out.println("PASS: " + name);
            passed++;
        } else {
            System.err.println("FAIL: " + name + " — " + failureMessage);
            failed++;
        }
    }
}
