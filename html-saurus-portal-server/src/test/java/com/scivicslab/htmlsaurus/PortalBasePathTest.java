package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The portal behind a proxy that publishes it below a prefix: the proxy removes the prefix before it forwards, so the
 * routes are the same, and every URL the portal writes must carry the prefix. Without a prefix the pages are what they
 * were.
 */
class PortalBasePathTest {

    private static final String PREFIX = "/pups-with-audit/session/abc123";

    @TempDir
    Path tempDir;

    private Path createProject(String name) throws IOException {
        Path projectDir = tempDir.resolve(name);
        Files.createDirectories(projectDir.resolve("docs"));
        Files.writeString(projectDir.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(projectDir.resolve("docs/intro.md"),
                "---\ntitle: Introduction\n---\n\n# Introduction\n\nHello world.");
        BuildStages.build(projectDir.resolve("docs"), projectDir.resolve("static-html"), false);
        BuildStages.reindex(projectDir.resolve("docs"), projectDir.resolve("search-index"));
        return projectDir;
    }

    private static HttpResponse<String> get(HttpServer http, String path) throws Exception {
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + http.getAddress().getPort() + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpServer start(BasePath base) throws Exception {
        Path proj = createProject("proj");
        return new PortalServer(tempDir, List.of(proj), 0, null, 0, base).start();
    }

    @Test
    void underABasePath_everyUrlOfThePortalPageCarriesIt() throws Exception {
        HttpServer http = start(BasePath.of(PREFIX));
        try {
            String index = get(http, "/").body();
            assertTrue(index.contains("<script>var BASE = \"" + PREFIX + "\";</script>"), "The page script needs the base path");
            assertTrue(index.contains("href=\"" + PREFIX + "/proj/\""), "The project link must stay below the prefix");
            assertFalse(index.contains("href=\"/proj/\""));
            assertTrue(index.contains("fetch(BASE + '/api/"), "Calls to the server go through BASE");
            assertFalse(index.contains("fetch('/"), "A call from the host root leaves the session");
            assertFalse(index.contains("fetch(\"/"));

            var redirect = get(http, "/proj");
            assertEquals(302, redirect.statusCode());
            assertEquals(PREFIX + "/proj/", redirect.headers().firstValue("Location").orElseThrow());
        } finally {
            http.stop(0);
        }
    }

    @Test
    void underABasePath_theSearchPageLinksStayBelowIt() throws Exception {
        HttpServer http = start(BasePath.of(PREFIX));
        try {
            String page = get(http, "/search?q=Hello").body();
            assertTrue(page.contains("href=\"" + PREFIX + "/\" target=\"_top\""), "The home link goes to the portal, not the host root");
            assertTrue(page.contains("href=\"" + PREFIX + "/proj/"), "A result links to the document below the prefix: " + page);
            assertTrue(page.contains("var BASE = '" + PREFIX + "';"), "The search box script needs the base path");
            assertFalse(page.contains("YADOC_BASE_PATH"));

            String related = get(http, "/related?path=/proj/intro/").body();
            assertTrue(related.contains("href=\"" + PREFIX + "/\" target=\"_top\""));
            assertTrue(related.contains("href=\"" + PREFIX + "/proj/intro/\""), "The link to the source page: " + related);
        } finally {
            http.stop(0);
        }
    }

    @Test
    void underABasePath_aDocumentPageCallsTheServerBelowIt() throws Exception {
        HttpServer http = start(BasePath.of(PREFIX));
        try {
            String doc = get(http, "/proj/intro/").body();
            assertTrue(doc.contains("<h1"), "The document is served: " + doc);
            assertTrue(doc.contains("/api/related?path="), "The related documents widget is injected");
            assertFalse(doc.contains("fetch('/api/related"), "The widget must call through the base path");
        } finally {
            http.stop(0);
        }
    }

    @Test
    void withoutABasePath_thePagesAreWhatTheyWere() throws Exception {
        HttpServer http = start(BasePath.none());
        try {
            String index = get(http, "/").body();
            assertTrue(index.contains("<script>var BASE = \"\";</script>"));
            assertTrue(index.contains("href=\"/proj/\""));

            var redirect = get(http, "/proj");
            assertEquals("/proj/", redirect.headers().firstValue("Location").orElseThrow());

            String page = get(http, "/search?q=Hello").body();
            assertTrue(page.contains("href=\"/\" target=\"_top\""));
            assertTrue(page.contains("href=\"/proj/"));
        } finally {
            http.stop(0);
        }
    }
}
