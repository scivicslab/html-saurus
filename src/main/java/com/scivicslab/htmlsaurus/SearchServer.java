package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * HTTP development server for a single Docusaurus project.
 *
 * <p>Serves static HTML files, provides a full-text search API endpoint ({@code /search?q=...}),
 * and exposes a build trigger endpoint ({@code POST /api/build}) for on-demand rebuilds.
 */
public class SearchServer {

    private final Path staticDir;
    private final Path indexDir;
    private final int port;
    private final Runnable rebuild;
    private final boolean production;

    /**
     * @param staticDir  directory containing the generated static HTML files
     * @param indexDir   directory containing the Lucene search index
     * @param port       HTTP port to listen on
     * @param rebuild    callback to trigger a full rebuild (build + reindex)
     * @param production {@code true} to disable the {@code /api/build} endpoint
     */
    public SearchServer(Path staticDir, Path indexDir, int port, Runnable rebuild, boolean production) {
        this.staticDir = staticDir;
        this.indexDir = indexDir;
        this.port = port;
        this.rebuild = rebuild;
        this.production = production;
    }

    /**
     * Creates and starts the HTTP server, registering handlers for build, search,
     * and static file endpoints.
     *
     * @throws IOException if the server socket cannot be opened
     */
    public void start() throws IOException {
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        if (!production) server.createContext("/api/build", this::handleBuild);
        server.createContext("/search", this::handleSearch);
        server.createContext("/", this::handleStatic);
        server.setExecutor(null);
        server.start();
        System.out.println("Serving at http://localhost:" + port);
        System.out.println("Press Ctrl+C to stop.");
    }

    // ---- Build endpoint -----------------------------------------

    /**
     * Handles {@code POST /api/build} requests. Triggers a full rebuild and returns
     * the elapsed time in a JSON response. Non-POST requests receive a 405 response.
     */
    private void handleBuild(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        long start = System.currentTimeMillis();
        rebuild.run();
        long ms = System.currentTimeMillis() - start;
        respond(ex, 200, "application/json", "{\"status\":\"ok\",\"ms\":" + ms + "}");
    }

    // ---- Search endpoint ----------------------------------------

    /**
     * Handles {@code GET /search?q=...} requests. Extracts the query string from the
     * URL parameters and returns search results as a JSON array with CORS headers.
     */
    private void handleSearch(HttpExchange ex) throws IOException {
        String q = "";
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                if (kv.startsWith("q=")) {
                    q = URLDecoder.decode(kv.substring(2), StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        byte[] body = search(q).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    /**
     * Executes a Lucene full-text search against the index directory.
     * Searches across title, document ID, and body fields with boosted weighting.
     * Returns up to 20 results as a JSON array of objects with title, path, and summary.
     *
     * @param queryStr the user's search query; blank returns an empty array
     * @return JSON array string of search results
     */
    private String search(String queryStr) {
        if (queryStr.isBlank()) return "[]";
        try (var dir = new NIOFSDirectory(indexDir);
             var reader = DirectoryReader.open(dir)) {

            var searcher = new IndexSearcher(reader);
            var parser = new MultiFieldQueryParser(
                new String[]{"title_idx", "doc_id_idx", "body"},
                new JapaneseAnalyzer(),
                Map.of("title_idx", 3.0f, "doc_id_idx", 5.0f, "body", 1.0f));
            parser.setDefaultOperator(MultiFieldQueryParser.AND_OPERATOR);

            var q = parser.parse(MultiFieldQueryParser.escape(queryStr));
            var hits = searcher.search(q, 20);
            var stored = searcher.storedFields();

            var sb = new StringBuilder("[");
            boolean first = true;
            for (var hit : hits.scoreDocs) {
                var doc = stored.document(hit.doc);
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                  .append("\"title\":").append(jsonStr(doc.get("title"))).append(",")
                  .append("\"path\":").append(jsonStr(doc.get("path"))).append(",")
                  .append("\"summary\":").append(jsonStr(doc.get("summary")))
                  .append("}");
            }
            return sb.append("]").toString();

        } catch (Exception e) {
            System.err.println("Search error: " + e.getMessage());
            return "[]";
        }
    }

    // ---- Static file endpoint -----------------------------------

    /**
     * Serves static files from the output directory. Maps the request path to a local file,
     * validates that it does not escape the static directory (path traversal protection),
     * and returns the file content with an appropriate Content-Type header.
     */
    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        Path file = staticDir.resolve(path.replaceFirst("^/", "")).normalize();
        if (!file.startsWith(staticDir)) { respond(ex, 403, "text/plain", "Forbidden"); return; }
        if (!Files.exists(file) || Files.isDirectory(file)) { respond(ex, 404, "text/html",
            "<html><body><h1>404 Not Found</h1><p>" + escapeHtml(path) + "</p></body></html>"); return; }

        byte[] body = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.toString()));
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    /** Sends an HTTP response with the given status code, content type, and UTF-8 body. */
    private void respond(HttpExchange ex, int code, String ct, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(code, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }

    /** HTML-escapes {@code <}, {@code >}, {@code &}, {@code "}, and {@code '} in the given string. */
    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#x27;");
    }

    /** Maps a file path's extension to the corresponding MIME type for the HTTP Content-Type header. */
    private String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".js"))   return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        return "application/octet-stream";
    }

    /** Wraps a string in JSON double quotes, escaping backslashes, quotes, and newlines. */
    private String jsonStr(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
