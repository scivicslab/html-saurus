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
 * Every long-running piece of work the portal starts appears in the Batch Job list.
 *
 * <p>Builds started from the Projects tab were kept in a map of their own and shown only to the
 * button that started them, so a reader on another tab had no way to see that the portal was
 * building, or that a build had failed. Imports, which the Import tab starts, were in the job
 * registry the list reads. Both are now in that registry.
 */
@Tag("S1")
class BatchJobListTest {

    @TempDir
    Path tempDir;

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private Path createProject(String name) throws IOException {
        Path projectDir = tempDir.resolve(name);
        Files.createDirectories(projectDir.resolve("docs"));
        Files.writeString(projectDir.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(projectDir.resolve("docs/intro.md"), "---\ntitle: Intro\n---\n\nContent.\n");
        Main.build(projectDir.resolve("docs"), projectDir.resolve("static-html"), false);
        return projectDir;
    }

    private String request(String method, String url) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        b = method.equals("POST") ? b.POST(HttpRequest.BodyPublishers.noBody()) : b.GET();
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void aBuildStartedFromTheProjectsTabIsListedWithTheImports() throws Exception {
        Path proj = createProject("proj");
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false, null, 0);
        HttpServer http = ps.start();
        try {
            String base = "http://localhost:" + http.getAddress().getPort();

            String started = request("POST", base + "/api/build-async/html/proj");
            assertTrue(started.contains("\"jobId\""), "the build answers with a job id: " + started);

            // The build of one small project finishes in well under this.
            String jobs = "";
            for (int i = 0; i < 100 && !jobs.contains("\"label\":\"proj\""); i++) {
                Thread.sleep(100);
                jobs = request("GET", base + "/api/import/jobs");
            }
            assertTrue(jobs.contains("\"label\":\"proj\""),
                    "the Batch Job list must hold the build: " + jobs);
            assertTrue(jobs.contains("\"kind\":\"build\""),
                    "the build must say what kind of work it is: " + jobs);
        } finally {
            http.stop(0);
        }
    }

    @Test
    void theButtonThatStartedTheBuildCanStillAskHowItIsGoing() throws Exception {
        Path proj = createProject("proj");
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false, null, 0);
        HttpServer http = ps.start();
        try {
            String base = "http://localhost:" + http.getAddress().getPort();
            String started = request("POST", base + "/api/build-async/html/proj");
            String id = started.replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

            String status = request("GET", base + "/api/build-status?jobId=" + id);
            for (String field : List.of("\"state\"", "\"project\"", "\"stage\"", "\"ms\"",
                                        "\"message\"", "\"listChanged\"")) {
                assertTrue(status.contains(field), field + " must be in " + status);
            }
            assertTrue(status.contains("\"project\":\"proj\""), status);
            assertTrue(status.contains("\"stage\":\"html\""), status);
        } finally {
            http.stop(0);
        }
    }
}
