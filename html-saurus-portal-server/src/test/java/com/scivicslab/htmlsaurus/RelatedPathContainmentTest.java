package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
 * {@code /api/related?path=} names a page, and the portal falls back to reading that page's HTML
 * off disk when no index entry carries its body text. The read resolved the caller's string
 * against the project's output directory without normalising it or checking where it landed, so
 * {@code ?path=/proj/../../../../etc/passwd} reached a file outside the project — and the portal
 * binds 0.0.0.0, so that is reachable from the network. The file's words then became the query
 * that chooses the related documents, which is what makes the read observable from outside.
 */
@Tag("S1")
class RelatedPathContainmentTest {

    @TempDir
    Path tempDir;

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** A word the project's documents share and nothing else uses. */
    private static final String MARKER = "zyzzyva";

    private Path buildProject(String name) throws IOException {
        Path proj = tempDir.resolve(name);
        Files.createDirectories(proj.resolve("docs"));
        Files.writeString(proj.resolve("docusaurus.config.js"), "module.exports = {};");
        // Two documents, not one: "related" means "like this one but not this one", so a corpus
        // of a single page can never answer with anything.
        Files.writeString(proj.resolve("docs/intro.md"),
                "---\ntitle: Intro\nid: intro\n---\n\n# Intro\n\n" + MARKER + " " + MARKER + "\n");
        Files.writeString(proj.resolve("docs/other.md"),
                "---\ntitle: Otherpage\nid: other\n---\n\n# Otherpage\n\n" + MARKER + " " + MARKER + "\n");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        BuildStages.reindexAll(proj, false);
        return proj;
    }

    @Test
    @DisplayName("A ?path= that climbs out of the project reads nothing outside it")
    void pathOutsideTheProjectIsNotRead() throws Exception {
        Path proj = buildProject("proj");

        // Sits outside the project and repeats the project's marker word. If the portal reads it,
        // the words become the related-documents query and Intro comes back as a hit. If the
        // portal refuses to read it, the query is empty and nothing comes back. The difference in
        // the response is the only way to observe the read from outside.
        Files.writeString(tempDir.resolve("secret.html"),
                "<html><body>" + MARKER + " " + MARKER + "</body></html>");

        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, null, 0);
        HttpServer http = ps.start();
        try {
            String base = "http://localhost:" + http.getAddress().getPort();

            // Sanity: a page really inside the project does produce a hit, so an empty answer
            // below means "refused to read", not "this test can never find anything".
            String inside = get(base + "/api/related?path=" + enc("/proj/intro/"));
            assertTrue(inside.contains("Otherpage"),
                    "a page inside the project must produce a hit, otherwise an empty answer "
                            + "below would prove nothing: " + inside);

            // Two levels up from <tempDir>/proj/static-html lands on <tempDir>, where the file is.
            String climb = "/proj/../../secret.html";
            String body = get(base + "/api/related?path=" + enc(climb));
            assertFalse(body.contains("Otherpage"),
                    "reading the file outside the project is what produced this hit: " + body);
        } finally {
            http.stop(0);
        }
    }

    @Test
    @DisplayName("A page inside the project is still read")
    void pathInsideTheProjectStillWorks() throws Exception {
        Path proj = buildProject("p2");
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, null, 0);
        HttpServer http = ps.start();
        try {
            String base = "http://localhost:" + http.getAddress().getPort();
            assertEquals(200, status(base + "/api/related?path=" + enc("/p2/intro/")),
                    "the guard must not have closed the ordinary case along with the hostile one");
        } finally {
            http.stop(0);
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String get(String url) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private int status(String url) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
