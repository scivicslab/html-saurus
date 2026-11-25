package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The production server on an ephemeral port, real requests, and the file read back: every request makes exactly one line,
 * whatever answered it, and every line is Apache's combined format.
 */
class AccessLogIntegrationTest {

    /** {@code %h %l %u [%t] "%r" %>s %b "%{Referer}i" "%{User-Agent}i"} */
    static final Pattern COMBINED = Pattern.compile(
            "^(\\S+) - - \\[(\\d{2}/[A-Z][a-z]{2}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4})\\] \"((?:[^\"\\\\]|\\\\.)*)\" (\\d{3}) (\\d+|-)"
                    + " \"((?:[^\"\\\\]|\\\\.)*)\" \"((?:[^\"\\\\]|\\\\.)*)\"$");

    @TempDir
    Path tempDir;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private SearchServer site(Path root) throws Exception {
        Path staticDir = root.resolve("static-html");
        Files.createDirectories(staticDir.resolve("a/b"));
        Files.writeString(staticDir.resolve("index.html"), "<html><body>top</body></html>");
        Files.writeString(staticDir.resolve("a/b/index.html"), "<html><body>page</body></html>");
        Files.write(staticDir.resolve("a/b/pic.png"), new byte[] {1, 2, 3});
        Path index = root.resolve("search-index");
        Files.createDirectories(index.resolve("en"));
        return new SearchServer(staticDir, index, 0);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyRegisteredPathAndAnUnknownOneMakeOneCombinedLine() throws Exception {
        Path log = tempDir.resolve("logs/access.log");
        SearchServer ss = site(tempDir);
        AccessLog accessLog = AccessLog.open(new AccessLogConfig(true, log, false, TrustedProxies.NONE));
        HttpServer server = ss.withAccessLog(accessLog).start();
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        List<String> sent = new ArrayList<>();
        try {
            // Every path the server declared, then ones none of them is exactly: a page, a missing page, a redirect.
            List<String> paths = new ArrayList<>();
            for (Endpoint e : ss.endpoints()) paths.add(e.path().endsWith("/") ? e.path() : e.path() + "?q=lisp");
            paths.addAll(List.of("/a/b/", "/a/b/pic.png", "/a/b", "/a/b?x=1", "/no/such/page", "/search-semantic", "/%E3%81%82", "/a/%22quoted%22"));
            for (String p : paths) {
                HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + p)).header("Referer", "https://example/ref")
                        .header("User-Agent", "Mozilla/5.0 (test)"));
                sent.add(p + " -> " + r.statusCode());
            }
            send(HttpRequest.newBuilder(URI.create(base + "/a/b/")).method("HEAD", HttpRequest.BodyPublishers.noBody()));
            send(HttpRequest.newBuilder(URI.create(base + "/a/b/")).POST(HttpRequest.BodyPublishers.ofString("x=1")));
            paths.add("HEAD /a/b/");
            paths.add("POST /a/b/");
        } finally {
            server.stop(0);
            accessLog.close();
        }
        List<String> lines = Files.readAllLines(log);
        assertEquals(sent.size() + 2, lines.size(), "one line per request: " + lines);
        for (String line : lines) assertTrue(COMBINED.matcher(line).matches(), "not the combined format: " + line);
        System.out.println("--- access.log written by the integration test ---");
        lines.forEach(System.out::println);

        // The lines say what the server answered.
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"GET /a/b/ HTTP/1.1\" 200 ") && l.contains("\"https://example/ref\" \"Mozilla/5.0 (test)\"")), "a page");
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"GET /a/b HTTP/1.1\" 301 -")), "a redirect has no body: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"GET /a/b?x=1 HTTP/1.1\" 301 ")), "the query string is in the request line");
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"GET /no/such/page HTTP/1.1\" 404 ")), "an unknown path");
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"GET /search-semantic HTTP/1.1\" 404 ")), "a path of the dev mode is closed here");
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"HEAD /a/b/ HTTP/1.1\" 200 - ")), "HEAD sends no body: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"POST /a/b/ HTTP/1.1\" ")), "POST");
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"GET /a/%22quoted%22 HTTP/1.1\"")), "the target as the client wrote it");
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"GET /a/b/pic.png HTTP/1.1\" 200 3 ")), "the byte count is the body's");
        for (Endpoint e : ss.endpoints()) {
            assertTrue(lines.stream().anyMatch(l -> l.contains("\"GET " + e.path())), "no line for the registered path " + e.path());
        }
        // The client is the peer: the test connects over loopback.
        assertTrue(lines.get(0).startsWith("127.0.0.1 - - ["), lines.get(0));
        Matcher m = COMBINED.matcher(lines.get(0));
        assertTrue(m.matches());
        assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(log)));
    }

    @Test
    void aForgedForwardedHeaderFromAnUntrustedPeerDoesNotChangeTheAddress_aTrustedProxyIsBelieved() throws Exception {
        for (boolean trusted : new boolean[] {false, true}) {
            Path log = tempDir.resolve(trusted ? "trusted.log" : "untrusted.log");
            SearchServer ss = site(tempDir.resolve(trusted ? "t" : "u"));
            AccessLog accessLog = AccessLog.open(new AccessLogConfig(true, log, false,
                    trusted ? TrustedProxies.parse("127.0.0.1, ::1") : TrustedProxies.NONE));
            HttpServer server = ss.withAccessLog(accessLog).start();
            try {
                send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                        .header("X-Forwarded-For", "203.0.113.77"));
            } finally {
                server.stop(0);
                accessLog.close();
            }
            String line = Files.readAllLines(log).get(0);
            if (trusted) assertTrue(line.startsWith("203.0.113.77 - - ["), line);
            else assertTrue(line.startsWith("127.0.0.1 - - ["), "forged header was believed: " + line);
        }
    }

    @Test
    void theResponseTimeIsAppendedOnlyWhenTheOptionIsOn() throws Exception {
        for (boolean on : new boolean[] {false, true}) {
            Path log = tempDir.resolve(on ? "timed.log" : "plain.log");
            SearchServer ss = site(tempDir.resolve(on ? "tt" : "pp"));
            AccessLog accessLog = AccessLog.open(new AccessLogConfig(true, log, on, TrustedProxies.NONE));
            HttpServer server = ss.withAccessLog(accessLog).start();
            try {
                send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")));
            } finally {
                server.stop(0);
                accessLog.close();
            }
            String line = Files.readAllLines(log).get(0);
            if (on) assertTrue(line.matches(".*\" \\d+$") && !COMBINED.matcher(line).matches(), line);
            else assertTrue(COMBINED.matcher(line).matches(), line);
        }
    }

    @Test
    void aRequestWhoseHandlerFailsStillGetsItsLine() throws Exception {
        Path log = tempDir.resolve("failed.log");
        AccessLog accessLog = AccessLog.open(new AccessLogConfig(true, log, false, TrustedProxies.NONE));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/boom", ex -> { throw new IllegalStateException("handler failed before answering"); })
                .getFilters().add(accessLog.filter());
        server.createContext("/silent", ex -> { /* returns without answering */ ex.close(); })
                .getFilters().add(accessLog.filter());
        server.createContext("/half", ex -> {
            byte[] body = new byte[100];
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body, 0, 40);   // the client goes away after part of the body: the handler's stream fails
            throw new java.io.IOException("connection aborted");
        }).getFilters().add(accessLog.filter());
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String p : List.of("/boom", "/silent", "/half")) {
                try {
                    send(HttpRequest.newBuilder(URI.create(base + p)));
                } catch (java.io.IOException expected) {
                    // The client sees the connection end; the log must still have the request.
                }
            }
        } finally {
            server.stop(0);
            accessLog.close();
        }
        // The client retries a GET whose connection ended, so a path can have more than one line; each is the same request.
        List<String> lines = Files.readAllLines(log);
        for (String l : lines) assertTrue(COMBINED.matcher(l).matches(), l);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"GET /boom HTTP/1.1\" 500 - ")), "a handler that threw: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"GET /silent HTTP/1.1\" 000 - ")), "a handler that never answered: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"GET /half HTTP/1.1\" 200 40 ")),
                "status as the server saw it, and the bytes written before it stopped: " + lines);
    }

    @Test
    void anAccessLogOpenedOnAnUnwritablePathNeverBreaksServing() throws Exception {
        Path blocker = tempDir.resolve("file-in-the-way");
        Files.writeString(blocker, "x");
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream saved = System.err;
        System.setErr(new java.io.PrintStream(err, true));
        try {
            SearchServer ss = site(tempDir.resolve("w"));
            AccessLog accessLog = AccessLog.open(new AccessLogConfig(true, blocker.resolve("access.log"), false, TrustedProxies.NONE));
            HttpServer server = ss.withAccessLog(accessLog).start();
            try {
                assertEquals(200, send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/a/b/"))).statusCode());
            } finally {
                server.stop(0);
                accessLog.close();
            }
        } finally {
            System.setErr(saved);
        }
        assertTrue(err.toString().contains("cannot write"), err.toString());
    }
}
