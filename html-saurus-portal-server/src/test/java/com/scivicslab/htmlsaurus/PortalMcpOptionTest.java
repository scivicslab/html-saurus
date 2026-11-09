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

/** The {@code /mcp} endpoint can be left out; the rest of the portal is the same either way. */
class PortalMcpOptionTest {

    @TempDir
    Path tempDir;

    private Path project() throws IOException {
        Path dir = tempDir.resolve("proj");
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(dir.resolve("docs/intro.md"), "---\ntitle: Introduction\n---\n\n# Introduction\n\nHello world.");
        BuildStages.build(dir.resolve("docs"), dir.resolve("static-html"), false);
        BuildStages.reindex(dir.resolve("docs"), dir.resolve("search-index"));
        return dir;
    }

    private static HttpResponse<String> send(HttpServer http, String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + http.getAddress().getPort() + path));
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) builder.header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";

    @Test
    void withoutMcp_everyRequestForItIs404_andTheRestOfThePortalWorks() throws Exception {
        HttpServer http = new PortalServer(tempDir, List.of(project()), 0, null, 0, BasePath.none(), null, false).start();
        try {
            assertEquals(404, send(http, "POST", "/mcp", INITIALIZE).statusCode());
            assertEquals(404, send(http, "GET", "/mcp", null).statusCode());
            assertEquals(404, send(http, "OPTIONS", "/mcp", null).statusCode());

            assertEquals(200, send(http, "GET", "/", null).statusCode(), "the portal page");
            assertEquals(200, send(http, "GET", "/api/search?q=Hello", null).statusCode(), "the search API");
            assertEquals(200, send(http, "GET", "/search?q=Hello", null).statusCode(), "the search page");
            assertEquals(200, send(http, "GET", "/proj/intro/", null).statusCode(), "a document");
            assertEquals(200, send(http, "GET", "/api/related?path=/proj/intro/", null).statusCode(), "related documents");
        } finally {
            http.stop(0);
        }
    }

    @Test
    void byDefault_mcpIsThereAsItWas() throws Exception {
        HttpServer http = new PortalServer(tempDir, List.of(project()), 0, null, 0, BasePath.none(), null).start();
        try {
            var r = send(http, "POST", "/mcp", INITIALIZE);
            assertNotEquals(404, r.statusCode(), "the endpoint answers: " + r.body());
            assertTrue(r.body().contains("jsonrpc"), r.body());
        } finally {
            http.stop(0);
        }
    }
}
