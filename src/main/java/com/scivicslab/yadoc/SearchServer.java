package com.scivicslab.yadoc;

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

public class SearchServer {

    private final Path staticDir;
    private final Path indexDir;
    private final int port;
    private final Runnable rebuild;

    public SearchServer(Path staticDir, Path indexDir, int port, Runnable rebuild) {
        this.staticDir = staticDir;
        this.indexDir = indexDir;
        this.port = port;
        this.rebuild = rebuild;
    }

    public void start() throws IOException {
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/build", this::handleBuild);
        server.createContext("/search", this::handleSearch);
        server.createContext("/", this::handleStatic);
        server.setExecutor(null);
        server.start();
        System.out.println("Serving at http://localhost:" + port);
        System.out.println("Press Ctrl+C to stop.");
    }

    // ---- Build endpoint -----------------------------------------

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

    private String search(String queryStr) {
        if (queryStr.isBlank()) return "[]";
        try (var dir = new NIOFSDirectory(indexDir);
             var reader = DirectoryReader.open(dir)) {

            var searcher = new IndexSearcher(reader);
            var parser = new MultiFieldQueryParser(
                new String[]{"title_idx", "doc_id_idx", "body"},
                new JapaneseAnalyzer(),
                Map.of("title_idx", 3.0f, "doc_id_idx", 5.0f, "body", 1.0f));

            var q = parser.parse(queryStr);
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

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        Path file = staticDir.resolve(path.replaceFirst("^/", "")).normalize();
        if (!file.startsWith(staticDir)) { respond(ex, 403, "text/plain", "Forbidden"); return; }
        if (!Files.exists(file) || Files.isDirectory(file)) { respond(ex, 404, "text/html",
            "<html><body><h1>404 Not Found</h1><p>" + path + "</p></body></html>"); return; }

        byte[] body = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.toString()));
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    private void respond(HttpExchange ex, int code, String ct, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(code, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }

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

    private String jsonStr(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
