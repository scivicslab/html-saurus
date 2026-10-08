package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * A page lives at {@code <dir>/index.html} and is served at {@code /<dir>/}. A browser resolves
 * the relative references inside a page against everything up to the last {@code /} of the page's
 * URL, so the trailing slash is load-bearing: served at {@code /a/b} instead, the page's
 * {@code <img src="pic.png">} asks for {@code /a/pic.png} and gets a 404, which is how the
 * e-mail address image vanished from the public site's contact page.
 *
 * <p>These tests pin the server's half of that contract: a directory request without the slash is
 * answered with 301 to the slash form rather than with the page itself.
 */
class TrailingSlashRedirectTest {

    @TempDir
    Path tempDir;

    private Path staticDir;
    private HttpServer server;
    private int port;

    /** Follows no redirects, so a 301 is observable rather than silently resolved. */
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @BeforeEach
    void startServer() throws Exception {
        staticDir = tempDir.resolve("static-html");
        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(docsDir);

        // A page with a same-directory image beside it, as every imported figure is.
        Files.createDirectories(staticDir.resolve("a/b"));
        Files.writeString(staticDir.resolve("a/b/index.html"),
                "<html><body><p>mail (<img src=\"pic.png\" alt=\"\" />)</p></body></html>");
        Files.write(staticDir.resolve("a/b/pic.png"), new byte[] {1, 2, 3});

        // A directory with no index.html: nothing to redirect to.
        Files.createDirectories(staticDir.resolve("a/empty"));

        SearchServer ss = new SearchServer(staticDir, tempDir.resolve("search-index"), 0);
        server = ss.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("A directory request without the trailing slash is redirected, not served")
    void redirectsDirectoryWithoutSlash() throws Exception {
        HttpResponse<String> res = get("/a/b");
        assertEquals(301, res.statusCode(), "the page must not be served under /a/b");
        assertEquals("/a/b/", res.headers().firstValue("Location").orElse(null));
    }

    @Test
    @DisplayName("The slash form serves the page")
    void servesDirectoryWithSlash() throws Exception {
        HttpResponse<String> res = get("/a/b/");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("<img src=\"pic.png\""));
    }

    @Test
    @DisplayName("The image resolves under the slash form and not one directory up")
    void imageResolvesOnlyBelowThePage() throws Exception {
        assertEquals(200, get("/a/b/pic.png").statusCode(),
                "the image sits beside the page");
        assertEquals(404, get("/a/pic.png").statusCode(),
                "the parent directory holds no image; this is what the browser asked for "
                        + "before the redirect existed");
    }

    @Test
    @DisplayName("The query string survives the redirect")
    void keepsQueryString() throws Exception {
        HttpResponse<String> res = get("/a/b?q=lisp");
        assertEquals(301, res.statusCode());
        assertEquals("/a/b/?q=lisp", res.headers().firstValue("Location").orElse(null));
    }

    @Test
    @DisplayName("A directory holding no index.html answers 404 instead of redirecting")
    void noRedirectWhenThereIsNoPage() throws Exception {
        assertEquals(404, get("/a/empty").statusCode());
    }

    @Test
    @DisplayName("A request for a file is unaffected")
    void fileRequestsAreUnchanged() throws Exception {
        assertEquals(200, get("/a/b/pic.png").statusCode());
        assertEquals(404, get("/a/b/missing.png").statusCode());
    }

    @Test
    @DisplayName("withTrailingSlash keeps the percent-encoding of the path")
    void keepsPercentEncoding() {
        assertEquals("/doc/%E6%96%87%E6%9B%B8/",
                HttpUtils.withTrailingSlash(URI.create("/doc/%E6%96%87%E6%9B%B8")));
        assertEquals("/a/b/?x=1&y=2",
                HttpUtils.withTrailingSlash(URI.create("/a/b?x=1&y=2")));
    }

    // ---- The redirect must not become an open redirect -------------------------------
    //
    // The Location is built from the request, which the attacker writes. A location starting
    // with "//" is a scheme-relative URL: the browser reads up to the next "/" as the authority,
    // so "//anything@evil.com/x" leaves this origin. Four leading slashes in the request line
    // leave java.net.URI with an empty authority and a raw path that itself starts with "//",
    // which is how that string can reach the header.

    @Test
    @DisplayName("withTrailingSlash collapses a run of leading slashes to one")
    void collapsesLeadingSlashes() {
        assertEquals("/a@evil.com/x/",
                HttpUtils.withTrailingSlash(URI.create("////a@evil.com/x")));
        assertEquals("/evil.com/",
                HttpUtils.withTrailingSlash(URI.create("/////evil.com")));
    }

    @Test
    @DisplayName("A request path with four leading slashes never yields an off-origin Location")
    void leadingSlashRunDoesNotEscapeTheOutputDirectory() throws Exception {
        // Path.resolve returns its argument verbatim when the argument is absolute. Stripping one
        // slash left "/<abs>", so the lookup started at the filesystem root and a crafted absolute
        // path that lexically sat under staticDir passed the containment check — and the Location
        // echoed back the raw "//...@evil.com" the attacker had written.
        // Decoded, this is "//<staticDir>/x@evil.com/../a/b", which normalises onto the real page
        // directory a/b — so every containment check passes and the handler reaches the redirect.
        // Raw, it still begins "//" and carries "@evil.com" before the first literal "/".
        String abs = staticDir.toString().substring(1).replace("/", "%2F");
        String head = rawRequest("////" + abs + "%2Fx@evil.com/..%2fa%2fb");

        assertFalse(head.contains("Location: //"),
                "a scheme-relative Location hands the browser an attacker-chosen host:\n" + head);
        assertFalse(head.startsWith("HTTP/1.1 301"),
                "a directory lookup must not start at the filesystem root:\n" + head);
    }

    /**
     * Sends a hand-built request line. {@link HttpClient} normalises the path of the URI it is
     * given, which would quietly turn this request into one the server never sees.
     */
    private String rawRequest(String target) throws IOException {
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
            s.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            var in = new java.io.BufferedReader(new java.io.InputStreamReader(
                    s.getInputStream(), java.nio.charset.StandardCharsets.ISO_8859_1));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    @Test
    @DisplayName("redirect refuses a scheme-relative location instead of emitting it")
    void redirectRefusesSchemeRelativeLocation() throws Exception {
        // The last line of defence, for every present and future caller.
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> HttpUtils.redirect(ex, "//evil.com/"));
        server.start();
        try {
            HttpResponse<String> res = client.send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, res.statusCode());
            assertTrue(res.headers().firstValue("Location").isEmpty(),
                    "no Location header may be sent for a rejected redirect");
        } finally {
            server.stop(0);
        }
    }
}
