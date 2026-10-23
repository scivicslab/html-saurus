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
 * {@code GET /api/link-check?project=} answers with the same-site references in one project's built
 * pages that resolve to nothing. A build only prints them to the log, where nobody opens a
 * portal's log; the answer is what a screen can read.
 */
@Tag("S1")
class LinkCheckApiTest {

    @TempDir
    Path tempDir;

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private Path buildProject(String name, String body) throws IOException {
        Path proj = tempDir.resolve(name);
        Files.createDirectories(proj.resolve("docs"));
        Files.writeString(proj.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(proj.resolve("docs/intro.md"),
                "---\ntitle: Intro\nid: intro\n---\n\n# Intro\n\n" + body + "\n");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        return proj;
    }

    @Test
    @DisplayName("A broken link in a built page is listed with its page, href and kind")
    void brokenLinkIsListed() throws Exception {
        Path proj = buildProject("proj", "See <a href=\"/proj/nowhere/\">nothing</a>.");
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, null, 0);
        HttpServer http = ps.start();
        try {
            String body = get("http://localhost:" + http.getAddress().getPort()
                    + "/api/link-check?project=proj");
            assertTrue(body.contains("\"project\":\"proj\""), body);
            assertTrue(body.contains("\"kind\":\"MISSING\""), body);
            assertTrue(body.contains("nowhere"), body);
        } finally {
            http.stop(0);
        }
    }

    @Test
    @DisplayName("A project with no broken link answers with a count of zero")
    void cleanProjectHasNoFindings() throws Exception {
        Path proj = buildProject("clean", "No links here.");
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, null, 0);
        HttpServer http = ps.start();
        try {
            String body = get("http://localhost:" + http.getAddress().getPort()
                    + "/api/link-check?project=clean");
            assertTrue(body.contains("\"count\":0"), body);
        } finally {
            http.stop(0);
        }
    }

    @Test
    @DisplayName("A project the portal does not hold is a 404")
    void unknownProjectIsNotFound() throws Exception {
        Path proj = buildProject("known", "No links here.");
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, null, 0);
        HttpServer http = ps.start();
        try {
            assertEquals(404, status("http://localhost:" + http.getAddress().getPort()
                    + "/api/link-check?project=other"));
        } finally {
            http.stop(0);
        }
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
