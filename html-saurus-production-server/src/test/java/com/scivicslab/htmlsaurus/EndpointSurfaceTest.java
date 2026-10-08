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
 * <p>The endpoints that reach into the machine the server runs on — {@code /mcp}, which reads and
 * writes files and rebuilds the site without asking for a credential, the import endpoints, the
 * build endpoints — used to sit in this same class behind {@code if (!production)}, and the only
 * check was a copy of the list kept in an E2E test. The copy fell behind:
 * {@code /api/build-html} was added and never added to it, so the test passed while saying
 * nothing about that path.
 *
 * <p>They are now in another module. This one declares nothing it will not serve, which is a
 * stronger statement than "the flag was set right", and the last test here shows it holds at the
 * level that matters: those classes are not on this module's classpath at all.
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

    /** A server with one page and two locale indexes, so /search and /en/search both exist. */
    private SearchServer serverOf(String name) throws IOException {
        Path root = tempDir.resolve(name);
        Path docs = root.resolve("docs");
        Path staticDir = root.resolve("static-html");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("intro.md"), "---\ntitle: Intro\nid: intro\n---\n\nHello.\n");
        BuildStages.build(docs, staticDir, true);
        Path indexDir = root.resolve("search-index");
        BuildStages.reindex(docs, indexDir, "ja", true);
        BuildStages.reindex(docs, indexDir.resolve("en"), "en", true);
        return new SearchServer(staticDir, indexDir, 0);
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
    @DisplayName("Every path this server declares is PUBLIC")
    void everyDeclaredPathIsPublic() throws Exception {
        SearchServer ss = serverOf("all-public");
        start(ss);
        List<Endpoint> declared = ss.endpoints();
        assertFalse(declared.isEmpty(), "the server must declare the paths it answers");
        for (Endpoint e : declared) {
            assertEquals(Endpoint.Visibility.PUBLIC, e.visibility(),
                    e.path() + " is declared DEV_ONLY. An endpoint a public site must not answer "
                            + "does not belong in this module at all — put it in the portal server, "
                            + "whose jar the public deployment does not contain.");
        }
    }

    @Test
    @DisplayName("Every path this server declares is answered")
    void everyDeclaredPathIsAnswered() throws Exception {
        SearchServer ss = serverOf("answered");
        HttpServer http = start(ss);
        for (Endpoint e : ss.endpoints()) {
            assertNotEquals(404, status(http, e.path()),
                    e.path() + " is declared but the server does not answer it");
        }
    }

    @Test
    @DisplayName("The declaration holds the three paths production mode serves")
    void theDeclarationIsTheWholeSurface() throws Exception {
        SearchServer ss = serverOf("surface");
        start(ss);
        List<String> paths = ss.endpoints().stream().map(Endpoint::path).sorted().toList();
        assertEquals(List.of("/", "/en/search", "/search"), paths,
                "the pages, and full-text search for each locale that has an index");
    }

    @Test
    @DisplayName("Every context goes through register()")
    void registrationHasOneDoor() throws Exception {
        // The declaration only covers a path that went through register(). A createContext call
        // written straight into start() would register a handler the declaration never mentions,
        // and the tests above would not look at it. One door, checked at the source.
        Path src = Path.of("src/main/java/com/scivicslab/htmlsaurus/SearchServer.java");
        assertTrue(Files.exists(src), "expected to run from the module directory, looked at " + src);

        String body = Files.readString(src);
        Matcher m = Pattern.compile("createContext\\s*\\(").matcher(body);
        List<String> hits = new ArrayList<>();
        List<String> all = body.lines().toList();
        while (m.find()) {
            int line = (int) body.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
            hits.add("line " + line + ": " + all.get(line - 1).strip());
        }
        assertEquals(1, hits.size(),
                "createContext belongs only inside register(); found:\n  " + String.join("\n  ", hits));
        assertTrue(hits.get(0).contains("server.createContext(path, handler.get())"),
                "the one call should be register()'s own: " + hits.get(0));
    }

    @Test
    @DisplayName("The endpoints that reach into the machine are not on this module's classpath")
    void theDangerousClassesAreNotHere() {
        // This is the guarantee the module split buys, and the only one a flag could never give.
        // McpHandler reads and writes files and rebuilds the site, and asks for no credential;
        // the import services fetch and write; PortalServer registers all of them. A deployment
        // cannot reach code that was never packaged with it.
        for (String name : List.of("McpHandler", "PortalServer", "PdfImportService",
                                   "WordImportService", "WebImportService", "TranscriptClient")) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("com.scivicslab.htmlsaurus." + name),
                    name + " is on the production server's classpath, so it ships in its jar");
        }
    }
}
