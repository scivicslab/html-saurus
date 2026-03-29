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
import java.util.*;
import java.util.stream.Collectors;

public class PortalServer {

    record Project(String name, Path projectDir, Path staticDir, Path indexDir) {}

    private final List<Project> projects;
    private final Map<String, Project> projectMap;
    private final int port;

    public PortalServer(List<Path> projectDirs, int port) {
        this.port = port;
        this.projects = new ArrayList<>();
        this.projectMap = new LinkedHashMap<>();
        for (Path p : projectDirs) {
            String name = p.getFileName().toString();
            Project proj = new Project(name, p, p.resolve("static-html"), p.resolve("search-index"));
            projects.add(proj);
            projectMap.put(name, proj);
        }
    }

    public HttpServer start() throws IOException {
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handleAll);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("Portal at http://localhost:" + server.getAddress().getPort());
        System.out.println("Press Ctrl+C to stop.");
        return server;
    }

    // ---- Routing ------------------------------------------------

    private void handleAll(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();

        if (path.equals("/") || path.isEmpty()) {
            servePortalIndex(ex);
            return;
        }

        // Build API: POST /api/build/<project>
        if (path.startsWith("/api/build/")) {
            String name = path.substring("/api/build/".length());
            handleBuild(ex, name);
            return;
        }

        // Cross-project search API (JSON): GET /api/search?q=...
        if (path.equals("/api/search")) {
            handleGlobalSearch(ex);
            return;
        }

        // Cross-project search page (SSR): GET /search?q=...
        if (path.equals("/search")) {
            handleSearchPage(ex);
            return;
        }

        // Split: /<project>[/<rest>]
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        int slash = stripped.indexOf('/');
        String projectName = slash >= 0 ? stripped.substring(0, slash) : stripped;
        String rest = slash >= 0 ? stripped.substring(slash) : "/";

        Project proj = projectMap.get(projectName);
        if (proj == null) {
            respond(ex, 404, "text/plain", "Project not found: " + projectName);
            return;
        }

        // Redirect /<project> → /<project>/
        if (slash < 0) {
            ex.getResponseHeaders().set("Location", "/" + projectName + "/");
            ex.sendResponseHeaders(302, -1);
            return;
        }

        if (rest.equals("/") || rest.isEmpty()) rest = "/index.html";

        if (rest.startsWith("/search")) {
            handleSearch(ex, proj);
        } else {
            handleStatic(ex, proj, rest);
        }
    }

    // ---- Build API endpoint -------------------------------------

    private void handleBuild(HttpExchange ex, String name) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        Project proj = projectMap.get(name);
        if (proj == null) {
            respond(ex, 404, "application/json", "{\"error\":\"Project not found\"}");
            return;
        }
        long start = System.currentTimeMillis();
        System.out.println("Build requested: " + name);
        try {
            Main.build(proj.projectDir().resolve("docs"), proj.staticDir());
            Main.reindex(proj.projectDir().resolve("docs"), proj.indexDir());
            long elapsed = System.currentTimeMillis() - start;
            respond(ex, 200, "application/json",
                "{\"status\":\"ok\",\"project\":\"" + name + "\",\"ms\":" + elapsed + "}");
        } catch (Exception e) {
            System.err.println("Build error for " + name + ": " + e.getMessage());
            respond(ex, 500, "application/json",
                "{\"error\":" + jsonStr(e.getMessage()) + "}");
        }
    }

    // ---- Portal index page --------------------------------------

    private void servePortalIndex(HttpExchange ex) throws IOException {
        var sb = new StringBuilder();
        sb.append("""
            <!DOCTYPE html>
            <html lang="ja">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Documentation Portal</title>
              <link rel="icon" type="image/png" href="data:image/png;base64,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">
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                       background: #f6f7f8; min-height: 100vh; }
                header { background: #1c1e21; color: #fff; padding: 1rem 2rem;
                         display: flex; align-items: center; gap: 1.5rem; flex-wrap: wrap; }
                header h1 { font-size: 1.3rem; font-weight: 700; }
                header p { font-size: 0.85rem; color: #aaa; }
                form.portal-search { display: flex; gap: 0.5rem; margin-left: auto; }
                form.portal-search input[type=search] { padding: 0.35rem 0.8rem; border-radius: 4px;
                  border: 1px solid #666; background: rgba(255,255,255,0.12); color: #fff;
                  font-size: 0.875rem; width: 240px; outline: none; }
                form.portal-search input[type=search]::placeholder { color: #aaa; }
                form.portal-search input[type=search]:focus { background: rgba(255,255,255,0.22); border-color: #aaa; }
                form.portal-search button { padding: 0.35rem 0.9rem; border-radius: 4px; border: none;
                  background: #2e8555; color: #fff; font-weight: 600; cursor: pointer; font-size: 0.875rem; }
                form.portal-search button:hover { background: #267a4e; }
                main { max-width: 960px; margin: 2rem auto; padding: 0 1.5rem; }
                h2 { font-size: 1rem; color: #555; font-weight: 600;
                     text-transform: uppercase; letter-spacing: 0.05em;
                     margin-bottom: 1rem; border-bottom: 1px solid #ddd; padding-bottom: 0.5rem; }
                .project-list { background: #fff; border: 1px solid #e3e4e5; border-radius: 8px;
                                overflow: hidden; }
                .project-row { display: flex; align-items: center; gap: 14px; padding: 10px 16px;
                               border-bottom: 1px solid #e3e4e5; }
                .project-row:last-child { border-bottom: none; }
                .project-name { font-size: 0.95rem; font-weight: 700; min-width: 180px; }
                .project-name a { color: #1c1e21; text-decoration: none; }
                .project-name a:hover { color: #2e8555; text-decoration: underline; }
                .project-labels { flex: 1; display: flex; flex-wrap: wrap; gap: 0.5rem; }
                .project-label { font-size: 0.78rem; color: #888; }
                .project-actions { display: flex; gap: 0.5rem; align-items: center; }
                .btn { padding: 0.3rem 0.85rem; border-radius: 5px; font-size: 0.82rem;
                       font-weight: 600; cursor: pointer; border: none; text-decoration: none;
                       display: inline-flex; align-items: center; }
                .btn-build { background: #e8eaf0; color: #333; }
                .btn-build:hover { background: #d5d8e0; }
                .btn-build:disabled { opacity: 0.5; cursor: not-allowed; }
                .build-status { font-size: 0.75rem; color: #888; }
              </style>
            </head>
            <body>
            <header>
              <div>
                <h1>Documentation Portal</h1>
                <p>%d project(s)</p>
              </div>
              <form class="portal-search" action="/search" method="get">
                <input type="search" name="q" placeholder="Search all docs...">
                <button type="submit">Search</button>
              </form>
            </header>
            <main>
              <h2>Projects</h2>
              <div class="project-list">
            """.formatted(projects.size()));

        for (Project p : projects) {
            List<String> labels = readNavbarLabels(p.projectDir());
            sb.append("    <div class=\"project-row\">\n");
            sb.append("      <div class=\"project-name\"><a href=\"/").append(escHtml(p.name())).append("/\" target=\"_blank\" rel=\"noopener noreferrer\">").append(escHtml(p.name())).append("</a></div>\n");
            sb.append("      <div class=\"project-labels\">\n");
            for (String label : labels) {
                sb.append("        <span class=\"project-label\">").append(escHtml(label)).append("</span>\n");
            }
            sb.append("      </div>\n");
            sb.append("      <div class=\"project-actions\">\n");
            sb.append("        <button class=\"btn btn-build\" onclick=\"doBuild('").append(escHtml(p.name())).append("', this)\">Build</button>\n");
            sb.append("        <span class=\"build-status\" id=\"status-").append(escHtml(p.name())).append("\"></span>\n");
            sb.append("      </div>\n");
            sb.append("    </div>\n");
        }

        sb.append("""
              </div>
            </main>
            <script>
            async function doBuild(name, btn) {
              const status = document.getElementById('status-' + name);
              btn.disabled = true;
              btn.textContent = 'Building...';
              status.textContent = '';
              try {
                const r = await fetch('/api/build/' + encodeURIComponent(name), {method: 'POST'});
                const j = await r.json();
                if (j.status === 'ok') {
                  status.textContent = 'Done (' + j.ms + 'ms)';
                  status.style.color = '#2e8555';
                } else {
                  status.textContent = 'Error: ' + (j.error || 'unknown');
                  status.style.color = '#e13238';
                }
              } catch (e) {
                status.textContent = 'Error: ' + e.message;
                status.style.color = '#e13238';
              }
              btn.disabled = false;
              btn.textContent = 'Build';
            }
            </script>
            </body>
            </html>
            """);

        respond(ex, 200, "text/html; charset=UTF-8", sb.toString());
    }

    // ---- Search results page (SSR) ------------------------------

    private static final int PAGE_SIZE = 100;

    private void handleSearchPage(HttpExchange ex) throws IOException {
        String q = queryParam(ex, "q");
        String pageStr = queryParam(ex, "page");
        int page = 1;
        try { if (!pageStr.isBlank()) page = Math.max(1, Integer.parseInt(pageStr)); }
        catch (NumberFormatException ignored) {}

        List<Map<String, String>> allHits = q.isBlank() ? List.of() : globalSearch(q);
        int total = allHits.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, totalPages);
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, total);
        List<Map<String, String>> hits = allHits.subList(from, to);

        var sb = new StringBuilder();
        sb.append("""
            <!DOCTYPE html>
            <html lang="ja">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Search: %s</title>
              <link rel="icon" type="image/png" href="data:image/png;base64,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">
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                       background: #f6f7f8; min-height: 100vh; }
                header { background: #1c1e21; color: #fff; padding: 1rem 2rem;
                         display: flex; align-items: center; gap: 1.5rem; }
                header a.home { color: #fff; text-decoration: none; font-weight: 700; font-size: 1.1rem;
                                white-space: nowrap; }
                header a.home:hover { color: #aaa; }
                form { display: flex; gap: 0.5rem; flex: 1; max-width: 600px; }
                form input[type=search] { flex: 1; padding: 0.4rem 0.8rem; border-radius: 4px;
                  border: 1px solid #666; background: rgba(255,255,255,0.12); color: #fff;
                  font-size: 0.9rem; outline: none; }
                form input[type=search]::placeholder { color: #aaa; }
                form input[type=search]:focus { background: rgba(255,255,255,0.22); border-color: #aaa; }
                form button { padding: 0.4rem 1rem; border-radius: 4px; border: none;
                  background: #2e8555; color: #fff; font-weight: 600; cursor: pointer; font-size: 0.9rem; }
                form button:hover { background: #267a4e; }
                main { max-width: 860px; margin: 2rem auto; padding: 0 1.5rem; }
                .result-count { color: #666; font-size: 0.875rem; margin-bottom: 1.5rem; }
                .result-count strong { color: #1c1e21; }
                .result { background: #fff; border: 1px solid #e3e4e5; border-radius: 8px;
                          padding: 1rem 1.25rem; margin-bottom: 0.75rem;
                          text-decoration: none; display: block; color: inherit;
                          transition: box-shadow 0.15s, border-color 0.15s; }
                .result:hover { box-shadow: 0 3px 10px rgba(0,0,0,0.08); border-color: #2e8555; }
                .result-project { font-size: 0.72rem; font-weight: 700; color: #2e8555;
                                  text-transform: uppercase; letter-spacing: 0.05em; }
                .result-title { font-size: 1rem; font-weight: 600; color: #1c1e21; margin: 0.2rem 0; }
                .result-breadcrumb { font-size: 0.75rem; color: #2e8555; margin-bottom: 0.3rem; }
                .result-summary { font-size: 0.82rem; color: #666; line-height: 1.5; }
                .no-results { color: #888; text-align: center; padding: 3rem; }
                .pager { margin-top: 2rem; display: flex; gap: 0.4rem; flex-wrap: wrap; align-items: center; }
                .pager a { padding: 0.3rem 0.7rem; border: 1px solid #ddd; border-radius: 4px;
                           text-decoration: none; color: #2e8555; font-size: 0.875rem; }
                .pager a:hover { background: #e8f4ee; border-color: #2e8555; }
                .pager-cur { padding: 0.3rem 0.7rem; border: 1px solid #2e8555; border-radius: 4px;
                             background: #2e8555; color: #fff; font-size: 0.875rem; font-weight: 600; }
              </style>
            </head>
            <body>
            <header>
              <a class="home" href="/">Documentation Portal</a>
              <form action="/search" method="get">
                <input type="search" name="q" value="%s" placeholder="Search all docs..." autofocus>
                <button type="submit">Search</button>
              </form>
            </header>
            <main>
            """.formatted(escHtml(q), escHtml(q)));

        if (q.isBlank()) {
            sb.append("<p class=\"no-results\">Please enter a search query.</p>\n");
        } else {
            sb.append("<p class=\"result-count\"><strong>").append(total)
              .append("</strong> result(s) for &ldquo;").append(escHtml(q)).append("&rdquo;");
            if (totalPages > 1)
                sb.append(" &mdash; page ").append(page).append(" / ").append(totalPages);
            sb.append("</p>\n");
            if (total == 0) {
                sb.append("<p class=\"no-results\">No results found.</p>\n");
            } else {
                for (var hit : hits) {
                    String href = "/" + hit.get("project") + hit.get("pagePath");
                    sb.append("<a class=\"result\" href=\"").append(escHtml(href)).append("\">\n");
                    sb.append("  <div class=\"result-project\">").append(escHtml(hit.get("project"))).append("</div>\n");
                    sb.append("  <div class=\"result-title\">").append(escHtml(hit.get("title"))).append("</div>\n");
                    sb.append("  <div class=\"result-breadcrumb\">").append(escHtml(pathToBreadcrumb(hit.get("pagePath")))).append("</div>\n");
                    sb.append("  <div class=\"result-summary\">").append(escHtml(hit.get("summary"))).append("</div>\n");
                    sb.append("</a>\n");
                }
                // Pager
                if (totalPages > 1) {
                    sb.append("<div class=\"pager\">");
                    String qEnc = java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8);
                    if (page > 1)
                        sb.append("<a href=\"/search?q=").append(qEnc).append("&page=").append(page - 1).append("\">&laquo; Prev</a> ");
                    for (int p = 1; p <= totalPages; p++) {
                        if (p == page)
                            sb.append("<span class=\"pager-cur\">").append(p).append("</span> ");
                        else
                            sb.append("<a href=\"/search?q=").append(qEnc).append("&page=").append(p).append("\">").append(p).append("</a> ");
                    }
                    if (page < totalPages)
                        sb.append("<a href=\"/search?q=").append(qEnc).append("&page=").append(page + 1).append("\">Next &raquo;</a>");
                    sb.append("</div>\n");
                }
            }
        }
        sb.append("</main>\n</body>\n</html>\n");
        respond(ex, 200, "text/html; charset=UTF-8", sb.toString());
    }

    // ---- Cross-project search -----------------------------------

    private void handleGlobalSearch(HttpExchange ex) throws IOException {
        String q = queryParam(ex, "q");
        List<Map<String, String>> hits = globalSearch(q);
        var sb = new StringBuilder("[");
        boolean first = true;
        for (var hit : hits) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
              .append("\"title\":").append(jsonStr(hit.get("title"))).append(",")
              .append("\"project\":").append(jsonStr(hit.get("project"))).append(",")
              .append("\"pagePath\":").append(jsonStr(hit.get("pagePath"))).append(",")
              .append("\"summary\":").append(jsonStr(hit.get("summary")))
              .append("}");
        }
        sb.append("]");
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    // Returns merged search results across all projects
    private List<Map<String, String>> globalSearch(String q) {
        var results = new ArrayList<Map<String, String>>();
        if (q.isBlank()) return results;
        for (Project proj : projects) {
            if (!Files.exists(proj.indexDir())) continue;
            collectHits(q, proj, results);
        }
        return results;
    }

    private void collectHits(String queryStr, Project proj, List<Map<String, String>> out) {
        try (var dir = new NIOFSDirectory(proj.indexDir());
             var reader = DirectoryReader.open(dir)) {
            var searcher = new IndexSearcher(reader);
            var parser = new MultiFieldQueryParser(
                new String[]{"title_idx", "doc_id_idx", "body"}, new JapaneseAnalyzer(),
                Map.of("title_idx", 3.0f, "doc_id_idx", 5.0f, "body", 1.0f));
            var hits = searcher.search(parser.parse(queryStr), 1000);
            var stored = searcher.storedFields();
            for (var hit : hits.scoreDocs) {
                var doc = stored.document(hit.doc);
                out.add(Map.of(
                    "project",  proj.name(),
                    "title",    doc.get("title") != null ? doc.get("title") : "",
                    "pagePath", doc.get("path")  != null ? doc.get("path")  : "",
                    "summary",  doc.get("summary") != null ? doc.get("summary") : ""
                ));
            }
        } catch (Exception e) {
            System.err.println("Search error [" + proj.name() + "]: " + e.getMessage());
        }
    }

    // ---- Per-project search endpoint ----------------------------

    private void handleSearch(HttpExchange ex, Project proj) throws IOException {
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
        byte[] body = searchWithProject(q, proj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    private String search(String queryStr, Path indexDir) {
        return searchWithProject(queryStr, new Project("", null, null, indexDir));
    }

    private String searchWithProject(String queryStr, Project proj) {
        if (queryStr.isBlank()) return "[]";
        try (var dir = new NIOFSDirectory(proj.indexDir());
             var reader = DirectoryReader.open(dir)) {
            var searcher = new IndexSearcher(reader);
            var parser = new MultiFieldQueryParser(
                new String[]{"title_idx", "body"},
                new JapaneseAnalyzer(),
                Map.of("title_idx", 3.0f, "body", 1.0f));
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
                  .append("\"path\":").append(jsonStr((proj.name().isEmpty() ? "" : "/" + proj.name()) + doc.get("path"))).append(",")
                  .append("\"pagePath\":").append(jsonStr(doc.get("path"))).append(",")
                  .append("\"project\":").append(jsonStr(proj.name())).append(",")
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

    private void handleStatic(HttpExchange ex, Project proj, String rest) throws IOException {
        Path file = proj.staticDir().resolve(rest.replaceFirst("^/", "")).normalize();
        if (!file.startsWith(proj.staticDir())) { respond(ex, 403, "text/plain", "Forbidden"); return; }
        if (!Files.exists(file) || Files.isDirectory(file)) {
            respond(ex, 404, "text/html",
                "<html><body><h1>404 Not Found</h1><p>" + escHtml(rest) + "</p></body></html>");
            return;
        }
        byte[] body = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.toString()));
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    // ---- Helpers ------------------------------------------------

    private String pathToBreadcrumb(String path) {
        if (path == null || path.isEmpty()) return "";
        return Arrays.stream(path.replaceFirst("^/", "").split("/"))
            .map(seg -> seg.replaceFirst("^\\d+_", "").replaceAll("\\.html$", ""))
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.joining(" › "));
    }

    private String queryParam(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return "";
        for (String kv : raw.split("&")) {
            if (kv.startsWith(key + "=")) {
                return URLDecoder.decode(kv.substring(key.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
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

    private List<String> readNavbarLabels(Path projectDir) {
        Path config = projectDir.resolve("docusaurus.config.js");
        if (!Files.exists(config)) return List.of();
        try {
            List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.contains("position: 'left'") || line.contains("position: \"left\"")) {
                    int start = Math.max(0, i - 5);
                    int end = Math.min(lines.size() - 1, i + 5);
                    for (int j = start; j <= end; j++) {
                        String ll = lines.get(j).trim();
                        if (ll.startsWith("label:")) {
                            String label = ll.replaceFirst("^label:\\s*['\"]", "").replaceFirst("['\"],?\\s*$", "");
                            if (!label.isBlank()) labels.add(label);
                            break;
                        }
                    }
                }
            }
            return labels;
        } catch (IOException e) {
            return List.of();
        }
    }

    private String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String jsonStr(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
