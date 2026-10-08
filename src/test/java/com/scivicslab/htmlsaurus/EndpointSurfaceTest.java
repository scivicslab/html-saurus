package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a public deployment answers, checked against what the server declares rather than against
 * a list someone wrote out by hand.
 *
 * <p>The endpoints a public site must not answer reach into the machine the server runs on:
 * {@code /mcp} reads and writes files and rebuilds the site and asks for no credential, and
 * {@code /api/build-all} rebuilds the whole site on one request. Which deployment answers which
 * path used to follow from the {@code if} block a {@code createContext} line happened to sit in,
 * and the only check was a copy of the list kept in an E2E test. The copy fell behind:
 * {@code /api/build-html} was added to the server and never added to it, so the test passed while
 * saying nothing about that path.
 *
 * <p>Every path now carries its {@link Endpoint.Visibility} at the line that declares it, and
 * these tests read {@link SearchServer#endpoints()}. A path added tomorrow is covered tomorrow.
 */
@Tag("S1")
class EndpointSurfaceTest {

    @TempDir
    Path tempDir;

    private final List<HttpServer> started = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @AfterEach
    void stopServers() {
        started.forEach(s -> s.stop(0));
    }

    /** A server of the given mode, with one page and one locale index so /en/search exists. */
    private SearchServer serverOf(String name, boolean production) throws IOException {
        Path root = tempDir.resolve(name);
        Path docs = root.resolve("docs");
        Path staticDir = root.resolve("static-html");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("intro.md"), "---\ntitle: Intro\nid: intro\n---\n\nHello.\n");
        Main.build(docs, staticDir, production);
        Path indexDir = root.resolve("search-index");
        Main.reindex(docs, indexDir, "ja", production);
        Main.reindex(docs, indexDir.resolve("en"), "en", production);
        return new SearchServer(staticDir, indexDir, 0, () -> {}, production, docs, null);
    }

    private HttpServer start(SearchServer ss) throws IOException {
        HttpServer http = ss.start();
        started.add(http);
        return http;
    }

    private int status(HttpServer http, String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + http.getAddress().getPort() + path))
                        .build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    @DisplayName("A public deployment answers every path declared PUBLIC")
    void publicPathsAreAnswered() throws Exception {
        SearchServer ss = serverOf("prod-open", true);
        HttpServer http = start(ss);
        for (Endpoint e : ss.endpoints()) {
            if (!e.servedInProduction()) continue;
            assertNotEquals(404, status(http, e.path()),
                    e.path() + " is declared PUBLIC but a public deployment does not answer it");
        }
    }

    @Test
    @DisplayName("A public deployment answers no path declared DEV_ONLY")
    void devOnlyPathsAreNotAnswered() throws Exception {
        SearchServer ss = serverOf("prod-closed", true);
        HttpServer http = start(ss);
        int checked = 0;
        for (Endpoint e : ss.endpoints()) {
            if (e.servedInProduction()) continue;
            assertEquals(404, status(http, e.path()),
                    e.path() + " is declared DEV_ONLY and must not be registered on a public site");
            checked++;
        }
        assertTrue(checked >= 10, "expected the declaration to hold the dev-only paths, saw " + checked);
    }

    @Test
    @DisplayName("A development deployment answers every declared path")
    void developmentAnswersEverything() throws Exception {
        SearchServer ss = serverOf("dev", false);
        HttpServer http = start(ss);
        for (Endpoint e : ss.endpoints()) {
            assertNotEquals(404, status(http, e.path()),
                    e.path() + " is declared but a development server does not answer it");
        }
    }

    @Test
    @DisplayName("Both modes declare the same paths; only the registration differs")
    void bothModesDeclareTheSamePaths() throws Exception {
        SearchServer prod = serverOf("decl-prod", true);
        SearchServer dev = serverOf("decl-dev", false);
        start(prod);
        start(dev);
        assertEquals(dev.endpoints(), prod.endpoints(),
                "the declaration is the whole list in either mode, so the test covers the same "
                        + "paths whichever mode it reads");
    }

    @Test
    @DisplayName("/mcp is declared, and declared DEV_ONLY")
    void mcpIsDeclaredDevOnly() throws Exception {
        SearchServer ss = serverOf("mcp", true);
        start(ss);
        Endpoint mcp = ss.endpoints().stream().filter(e -> e.path().equals("/mcp"))
                .findFirst().orElseThrow(() -> new AssertionError("/mcp is no longer declared"));
        assertEquals(Endpoint.Visibility.DEV_ONLY, mcp.visibility(),
                "/mcp reads and writes files and rebuilds the site without asking for a credential");
    }

    @Test
    @DisplayName("Every context in start() goes through register()")
    void registrationHasOneDoor() throws Exception {
        // The declaration only covers a path that went through register(). A createContext call
        // written straight into start() would register a handler the declaration never mentions
        // and the tests above would not look at it, which is the hole this whole arrangement
        // exists to close. One door, checked at the source.
        Path src = Path.of("src/main/java/com/scivicslab/htmlsaurus/SearchServer.java");
        assertTrue(Files.exists(src), "expected to run from the project directory, looked at " + src);

        String body = Files.readString(src);
        Matcher m = Pattern.compile("createContext\\s*\\(").matcher(body);
        List<String> lines = new ArrayList<>();
        while (m.find()) {
            long line = body.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
            lines.add("line " + line + ": " + body.lines().toList().get((int) line - 1).strip());
        }
        assertEquals(1, lines.size(),
                "createContext belongs only inside register(); found:\n  " + String.join("\n  ", lines));
        assertTrue(lines.get(0).contains("server.createContext(path, handler.get())"),
                "the one call should be register()'s own: " + lines.get(0));
    }
}
