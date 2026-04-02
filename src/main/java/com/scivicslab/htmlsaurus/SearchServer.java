package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP development server for a single Docusaurus project.
 *
 * <p>Serves static HTML files, provides a full-text search API endpoint ({@code /search?q=...}),
 * and exposes a build trigger endpoint ({@code POST /api/build}) for on-demand rebuilds.
 */
public class SearchServer {

    private final Path staticDir;
    private final int port;
    private final Runnable rebuild;
    private final boolean production;
    private final LuceneSearcher searcher;
    private final Map<String, LuceneSearcher> localeSearchers = new HashMap<>();

    /**
     * @param staticDir  directory containing the generated static HTML files
     * @param indexDir   directory containing the Lucene search index
     * @param port       HTTP port to listen on
     * @param rebuild    callback to trigger a full rebuild (build + reindex)
     * @param production {@code true} to disable the {@code /api/build} endpoint
     */
    public SearchServer(Path staticDir, Path indexDir, int port, Runnable rebuild, boolean production) {
        this.staticDir = staticDir;
        this.port = port;
        this.rebuild = rebuild;
        this.production = production;
        this.searcher = new LuceneSearcher(indexDir);
        // Load locale-specific indexes from search-index/<locale>/
        try {
            if (Files.isDirectory(indexDir)) {
                Files.list(indexDir)
                    .filter(Files::isDirectory)
                    .forEach(locDir -> localeSearchers.put(
                        locDir.getFileName().toString(), new LuceneSearcher(locDir)));
            }
        } catch (IOException e) {
            System.err.println("Warning: could not scan locale indexes: " + e.getMessage());
        }
    }

    /**
     * Creates and starts the HTTP server, registering handlers for build, search,
     * and static file endpoints.
     *
     * @return the started {@link HttpServer} instance (caller may call {@code stop(0)} when done)
     * @throws IOException if the server socket cannot be opened
     */
    public HttpServer start() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        if (!production) server.createContext("/api/build", this::handleBuild);
        server.createContext("/search", this::handleSearch);
        server.createContext("/", this::handleStatic);
        server.setExecutor(null);
        server.start();
        System.out.println("Serving at http://localhost:" + server.getAddress().getPort());
        System.out.println("Press Ctrl+C to stop.");
        return server;
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
        String q = HttpUtils.queryParam(ex, "q");
        String locale = HttpUtils.queryParam(ex, "locale");
        LuceneSearcher s = (!locale.isEmpty() && localeSearchers.containsKey(locale))
            ? localeSearchers.get(locale) : searcher;
        HttpUtils.respond(ex, 200, "application/json; charset=UTF-8", search(q, s));
    }

    /**
     * Executes a Lucene full-text search against the index directory.
     * Searches across title, document ID, and body fields with boosted weighting.
     * Returns up to 20 results as a JSON array of objects with title, path, and summary.
     *
     * @param queryStr the user's search query; blank returns an empty array
     * @return JSON array string of search results
     */
    private String search(String queryStr, LuceneSearcher s) {
        try {
            var hits = s.search(queryStr, 20,
                new String[]{"title_idx", "doc_id_idx", "body"},
                Map.of("title_idx", 3.0f, "doc_id_idx", 5.0f, "body", 1.0f));
            var sb = new StringBuilder("[");
            boolean first = true;
            for (var hit : hits) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                  .append("\"title\":").append(HttpUtils.jsonStr(hit.title())).append(",")
                  .append("\"path\":").append(HttpUtils.jsonStr(hit.path())).append(",")
                  .append("\"summary\":").append(HttpUtils.jsonStr(hit.summary()))
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
        if (Files.isDirectory(file)) {
            file = file.resolve("index.html").normalize();
            if (!file.startsWith(staticDir) || !Files.exists(file)) {
                respond(ex, 404, "text/html",
                    "<html><body><h1>404 Not Found</h1><p>" + HttpUtils.escapeHtml(path) + "</p></body></html>");
                return;
            }
        } else if (!Files.exists(file)) {
            respond(ex, 404, "text/html",
                "<html><body><h1>404 Not Found</h1><p>" + HttpUtils.escapeHtml(path) + "</p></body></html>");
            return;
        }

        byte[] body = Files.readAllBytes(file);
        HttpUtils.respond(ex, 200, HttpUtils.contentType(file.toString()), body);
    }

    private void respond(HttpExchange ex, int code, String ct, String body) throws IOException {
        HttpUtils.respond(ex, code, ct, body);
    }
}
