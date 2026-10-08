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

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@code /search} puts in a response.
 *
 * <p>A hit carries the Markdown's path on the server's own disk, which is how the portal offers
 * "Source / Copy" and how an agent reads the source of a result. A reader on the internet has no
 * use for it and should not be told the server's directory layout, so this server never writes
 * it. It is not a mode: there is no deployment of this server that does.
 */
@Tag("S1")
class ProductionSearchResponseTest {

    @TempDir
    Path tempDir;

    private Path buildProject() throws IOException {
        Path proj = tempDir.resolve("proj");
        Files.createDirectories(proj.resolve("docs"));
        Files.writeString(proj.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(proj.resolve("docs/intro.md"),
                "---\ntitle: Introduction\n---\n\n# Introduction\n\nHello world.\n");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), true);
        BuildStages.reindex(proj.resolve("docs"), proj.resolve("search-index"));
        return proj;
    }

    @Test
    @DisplayName("/search answers, and its hits never carry srcPath")
    void searchAnswersWithoutSrcPath() throws Exception {
        Path proj = buildProject();
        SearchServer ss = new SearchServer(proj.resolve("static-html"), proj.resolve("search-index"), 0);
        HttpServer http = ss.start();
        try {
            String json = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + http.getAddress().getPort() + "/search?q=Introduction")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertNotEquals("[]", json, "the one document must come back for its own title");
            assertFalse(json.contains("srcPath"),
                    "a hit must not carry the Markdown's path on the server's disk: " + json);
        } finally {
            http.stop(0);
        }
    }
}
