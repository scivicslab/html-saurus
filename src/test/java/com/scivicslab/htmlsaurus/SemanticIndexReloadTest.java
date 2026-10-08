package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;
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
 * A rebuild replaces what the portal answers semantic questions from.
 *
 * <p>The vectors were read once, when the portal started, into a field it never wrote again. A
 * rebuild wrote a new vector file and the portal went on answering from the old one, so after a
 * page moved to another project its old address kept being offered and every such hit answered 404.
 * The full-text side never had this: its searcher reopens the Lucene index when it changes.
 */
@Tag("S1")
class SemanticIndexReloadTest {

    @TempDir
    Path tempDir;

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private Path createProject(String pagePath) throws IOException {
        Path proj = tempDir.resolve("proj");
        Files.createDirectories(proj.resolve("docs"));
        Files.writeString(proj.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(proj.resolve("docs/intro.md"), "---\ntitle: Intro\n---\n\nContent.\n");
        Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        writeVectors(proj, pagePath);
        return proj;
    }

    /** Two documents, so each is the other's related document and the served map is not empty. */
    private void writeVectors(Path proj, String pagePath) throws IOException {
        var a = new SemanticIndexer.DocVec(pagePath, "A", "ja", "sa", "idA", "/src/a.md",
                new float[]{1f, 0f, 0f});
        var b = new SemanticIndexer.DocVec("/other/", "B", "ja", "sb", "idB", "/src/b.md",
                new float[]{0.9f, 0.1f, 0f});
        SemanticIndexer.writeVectors(
                proj.resolve(SemanticIndexer.EMBED_DIR).resolve(SemanticIndexer.VECTORS_FILE),
                List.of(a, b));
    }

    private String get(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void rebuildingTheVectorsChangesWhatThePortalAnswersFrom() throws Exception {
        Path proj = createProject("/the-old-address.html");
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false,
                SemanticIndex.load(List.of(proj), 20), 0);
        HttpServer http = ps.start();
        try {
            String base = "http://localhost:" + http.getAddress().getPort();
            assertTrue(get(base + "/api/related-semantic?path=/proj/other/")
                            .contains("the-old-address"),
                    "before the rebuild the portal answers from the vectors it started with");

            // What a rebuild of the embedding stage does: write a new vector file.
            writeVectors(proj, "/the-new-address/");
            ps.reloadSemanticIndex();

            String after = get(base + "/api/related-semantic?path=/proj/other/");
            assertTrue(after.contains("the-new-address"), "the new address must be offered: " + after);
            assertFalse(after.contains("the-old-address"), "the old address must be gone: " + after);
        } finally {
            http.stop(0);
        }
    }
}
