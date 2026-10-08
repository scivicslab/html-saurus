package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * E2E test verifying that an already-running production-mode deployment answers exactly the paths
 * the server declares PUBLIC and none of the ones it declares DEV_ONLY, and that {@code /search}
 * never returns {@code srcPath}.
 *
 * <p>The list of paths is not written out here. It used to be, as two literal lists that someone
 * had to keep level with {@link SearchServer#start()}, and it fell behind: {@code /api/build-html}
 * was added to the server and never added to the list, so this test passed while saying nothing
 * about that path. It now starts a throwaway production server on an ephemeral port, reads
 * {@link SearchServer#endpoints()} off it, stops it, and probes the deployment named by
 * {@code PRODUCTION_URL} for each path it found. A path added to the server tomorrow is probed
 * tomorrow.
 *
 * <p>This does not serve the site it checks: per the testing standard (see
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
 *   PRODUCTION_URL=https://sc.ddbj.nig.ac.jp mvn test-compile exec:java \
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

    public static void main(String[] args) throws Exception {
        System.out.println("=== Production Endpoint Surface E2E: " + BASE_URL + " ===");

        List<Endpoint> declared = declaredEndpoints();
        System.out.println("Paths declared by SearchServer: " + declared.size());

        for (Endpoint e : declared) {
            String path = e.servedInProduction() ? withQuery(e.path()) : e.path();
            int code = status(path);
            if (e.servedInProduction()) {
                check("PUBLIC   GET " + path + " answers", code != 404,
                        "expected the deployment to answer, got " + code);
            } else {
                check("DEV_ONLY GET " + path + " is not answered", code == 404,
                        "expected 404 — this path reaches into the machine the server runs on "
                                + "and must not be registered on a public site — got " + code);
            }
        }

        check("GET /search response never includes srcPath",
                !body("/search?q=a").contains("srcPath"),
                "response includes srcPath (a local filesystem path) — this must never reach a public reader");

        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    /**
     * The paths a production-mode {@link SearchServer} declares. Read off a throwaway server bound
     * to an ephemeral port and stopped straight away, so the declaration comes from the code under
     * test rather than from a copy of it kept here.
     */
    private static List<Endpoint> declaredEndpoints() throws Exception {
        Path root = Files.createTempDirectory("endpoint-surface-");
        Path docs = root.resolve("docs");
        Path staticDir = root.resolve("static-html");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("intro.md"), "---\ntitle: Intro\nid: intro\n---\n\nHello.\n");
        Main.build(docs, staticDir, true);
        Path indexDir = root.resolve("search-index");
        Main.reindex(docs, indexDir, "ja", true);
        // A locale index as well, so the per-locale search paths appear in the declaration.
        Main.reindex(docs, indexDir.resolve("en"), "en", true);

        SearchServer ss = new SearchServer(staticDir, indexDir, 0, () -> {}, true, docs, null);
        HttpServer http = ss.start();
        try {
            return ss.endpoints();
        } finally {
            http.stop(0);
        }
    }

    /** The two public paths need an argument to answer with anything. */
    private static String withQuery(String path) {
        return path.endsWith("/search") ? path + "?q=a" : path;
    }

    private static int status(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(BASE_URL + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String body(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(BASE_URL + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
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
