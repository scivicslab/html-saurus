package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HTTP server that hosts a unified documentation portal for multiple Docusaurus projects.
 *
 * <p>Provides a portal index page listing all discovered projects, cross-project full-text search
 * (both JSON API and server-side rendered HTML), per-project search endpoints, per-project
 * static file serving, and on-demand build triggers via REST API.
 */
public class PortalServer {

    /** Represents a single Docusaurus project with its name, root directory, static output, and search index paths. */
    record Project(String name, Path projectDir, Path staticDir, Path indexDir) {}

    private final List<Project> projects;
    private final Map<String, Project> projectMap;
    private final Map<String, LuceneSearcher> searchers = new LinkedHashMap<>();
    private final int port;
    private final boolean production;
    private final Path worksDir;

    /**
     * @param worksDir    root directory containing all Docusaurus projects (used for reload)
     * @param projectDirs list of Docusaurus project root directories to serve
     * @param port        HTTP port to listen on (0 for a system-assigned port)
     * @param production  {@code true} to disable the {@code /api/build} endpoint
     */
    public PortalServer(Path worksDir, List<Path> projectDirs, int port, boolean production) {
        this.worksDir = worksDir;
        this.port = port;
        this.production = production;
        this.projects = new ArrayList<>();
        this.projectMap = new LinkedHashMap<>();
        for (Path p : projectDirs) {
            String name = p.getFileName().toString();
            Project proj = new Project(name, p, p.resolve("static-html"), p.resolve("search-index"));
            projects.add(proj);
            projectMap.put(name, proj);
            searchers.put(name, new LuceneSearcher(proj.indexDir()));
            // Load locale-specific indexes from search-index/<locale>/
            try {
                if (Files.isDirectory(proj.indexDir())) {
                    Files.list(proj.indexDir())
                        .filter(Files::isDirectory)
                        .forEach(locDir -> searchers.put(
                            name + ":" + locDir.getFileName(), new LuceneSearcher(locDir)));
                }
            } catch (IOException e) {
                System.err.println("Warning: could not scan locale indexes for " + name + ": " + e.getMessage());
            }
        }
    }

    /**
     * Creates and starts the portal HTTP server with a cached thread pool executor.
     *
     * @return the running {@link HttpServer} instance
     * @throws IOException if the server socket cannot be opened
     */
    public HttpServer start() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handleAll);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("Portal at http://localhost:" + server.getAddress().getPort());
        System.out.println("Press Ctrl+C to stop.");
        return server;
    }

    // ---- Routing ------------------------------------------------

    /**
     * Central request router. Dispatches incoming requests to the appropriate handler based on
     * the URL path: portal index, build API, global search API/page, or per-project routes.
     */
    private void handleAll(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();

        if (path.equals("/") || path.isEmpty()) {
            servePortalIndex(ex);
            return;
        }

        // Reload API: POST /api/reload (development mode only)
        if (!production && path.equals("/api/reload")) {
            handleReload(ex);
            return;
        }

        // Build API: POST /api/build/<project> (development mode only)
        if (!production && path.startsWith("/api/build/")) {
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

    // ---- Reload API endpoint ------------------------------------

    /**
     * Handles {@code POST /api/reload} requests. Rescans the works directory, rebuilds
     * and reindexes any new projects, and refreshes the in-memory project list.
     */
    private synchronized void handleReload(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        long start = System.currentTimeMillis();
        System.out.println("Reload requested: rescanning " + worksDir);
        try {
            List<Path> found = Main.findProjects(worksDir);
            int added = 0;
            for (Path p : found) {
                String name = p.getFileName().toString();
                if (!projectMap.containsKey(name)) {
                    Main.build(p.resolve("docs"), p.resolve("static-html"), production);
                    Main.reindex(p.resolve("docs"), p.resolve("search-index"));
                    Project proj = new Project(name, p, p.resolve("static-html"), p.resolve("search-index"));
                    projects.add(proj);
                    projectMap.put(name, proj);
                    System.out.println("  Added: " + name);
                    added++;
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            respond(ex, 200, "application/json",
                "{\"status\":\"ok\",\"total\":" + projects.size() + ",\"added\":" + added + ",\"ms\":" + elapsed + "}");
        } catch (Exception e) {
            System.err.println("Reload error: " + e.getMessage());
            respond(ex, 500, "application/json", "{\"error\":\"Reload failed\"}");
        }
    }

    // ---- Build API endpoint -------------------------------------

    /**
     * Handles {@code POST /api/build/<project>} requests. Triggers a rebuild and reindex
     * for the named project and returns the elapsed time as JSON.
     */
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
            Main.build(proj.projectDir().resolve("docs"), proj.staticDir(), false);
            Main.reindex(proj.projectDir().resolve("docs"), proj.indexDir());
            long elapsed = System.currentTimeMillis() - start;
            respond(ex, 200, "application/json",
                "{\"status\":\"ok\",\"project\":\"" + name + "\",\"ms\":" + elapsed + "}");
        } catch (Exception e) {
            System.err.println("Build error for " + name + ": " + e.getMessage());
            respond(ex, 500, "application/json", "{\"error\":\"Build failed\"}");
        }
    }

    // ---- Portal index page --------------------------------------

    /**
     * Renders and serves the portal index page listing all registered projects.
     * Each project row shows the project name (opening in a new tab), navbar labels
     * extracted from the Docusaurus config, and a build trigger button.
     * Supports multiple color themes via CSS custom properties.
     */
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
                :root, [data-theme="dark-catppuccin"] {
                  --bg-primary:#1e1e2e; --bg-secondary:#313244; --bg-tertiary:#45475a;
                  --text-primary:#cdd6f4; --text-secondary:#a6adc8;
                  --accent-green:#a6e3a1; --border-color:#585b70; }
                [data-theme="dark-nord"] {
                  --bg-primary:#2e3440; --bg-secondary:#3b4252; --bg-tertiary:#434c5e;
                  --text-primary:#eceff4; --text-secondary:#d8dee9;
                  --accent-green:#a3be8c; --border-color:#4c566a; }
                [data-theme="dark-blue"] {
                  --bg-primary:#0d1b2a; --bg-secondary:#1b2838; --bg-tertiary:#2a3a4e;
                  --text-primary:#d4dce8; --text-secondary:#8a9bb5;
                  --accent-green:#6ec87a; --border-color:#3a4e68; }
                [data-theme="dark-green"] {
                  --bg-primary:#0f1e14; --bg-secondary:#1a2e20; --bg-tertiary:#2a4030;
                  --text-primary:#d0e4d4; --text-secondary:#88a890;
                  --accent-green:#5ec87a; --border-color:#3a5842; }
                [data-theme="dark-red"] {
                  --bg-primary:#1e0f0f; --bg-secondary:#2e1a1a; --bg-tertiary:#402a2a;
                  --text-primary:#e4d0d0; --text-secondary:#a88888;
                  --accent-green:#68b870; --border-color:#583a3a; }
                [data-theme="light-clean"] {
                  --bg-primary:#ffffff; --bg-secondary:#f0f2f5; --bg-tertiary:#e4e6eb;
                  --text-primary:#1c1e21; --text-secondary:#606770;
                  --accent-green:#31a24c; --border-color:#ced0d4; }
                [data-theme="light-warm"] {
                  --bg-primary:#faf6f0; --bg-secondary:#f0ebe3; --bg-tertiary:#e6dfd5;
                  --text-primary:#3d3529; --text-secondary:#7a6f60;
                  --accent-green:#6a8f5e; --border-color:#d5cec4; }
                [data-theme="light-blue"] {
                  --bg-primary:#eef4fb; --bg-secondary:#dce8f5; --bg-tertiary:#c8d9ed;
                  --text-primary:#1a2a40; --text-secondary:#4a6080;
                  --accent-green:#3a9e50; --border-color:#b0c8e4; }
                [data-theme="light-green"] {
                  --bg-primary:#f0f8f0; --bg-secondary:#e0f0e0; --bg-tertiary:#cce4cc;
                  --text-primary:#1a301a; --text-secondary:#4a704a;
                  --accent-green:#2e8b48; --border-color:#a8cca8; }
                [data-theme="light-red"] {
                  --bg-primary:#fbf0f0; --bg-secondary:#f5dce0; --bg-tertiary:#ecc8cc;
                  --text-primary:#401a1e; --text-secondary:#804a50;
                  --accent-green:#3a9850; --border-color:#e0b0b8; }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                       background: var(--bg-primary); color: var(--text-primary); min-height: 100vh; }
                header { background: var(--bg-secondary); color: var(--text-primary);
                         padding: 0.75rem 2rem; display: flex; align-items: center;
                         gap: 1.5rem; flex-wrap: wrap; border-bottom: 1px solid var(--border-color); }
                header h1 { font-size: 1.15rem; font-weight: 700; }
                header p { font-size: 0.82rem; color: var(--text-secondary); }
                .header-right { display: flex; gap: 1rem; align-items: center; margin-left: auto; }
                select#theme-select { padding: 0.3rem 0.5rem; border-radius: 4px; font-size: 0.8rem;
                  border: 1px solid var(--border-color); background: var(--bg-tertiary);
                  color: var(--text-primary); cursor: pointer; }
                form.portal-search { display: flex; gap: 0.5rem; }
                form.portal-search input[type=search] { padding: 0.35rem 0.8rem; border-radius: 4px;
                  border: 1px solid var(--border-color); background: var(--bg-primary);
                  color: var(--text-primary); font-size: 0.875rem; width: 220px; outline: none; }
                form.portal-search input[type=search]::placeholder { color: var(--text-secondary); }
                form.portal-search button { padding: 0.35rem 0.9rem; border-radius: 4px; border: none;
                  background: var(--accent-green); color: #fff; font-weight: 600; cursor: pointer; font-size: 0.875rem; }
                main { max-width: 960px; margin: 2rem auto; padding: 0 1.5rem; }
                h2 { font-size: 0.8rem; color: var(--text-secondary); font-weight: 600;
                     text-transform: uppercase; letter-spacing: 0.06em;
                     margin-bottom: 0.75rem; border-bottom: 1px solid var(--border-color); padding-bottom: 0.4rem; }
                .project-list { background: var(--bg-secondary); border: 1px solid var(--border-color);
                                border-radius: 8px; overflow: hidden; }
                .project-row { display: flex; align-items: center; gap: 14px; padding: 9px 16px;
                               border-bottom: 1px solid var(--border-color); }
                .project-row:last-child { border-bottom: none; }
                .project-name { font-size: 0.92rem; font-weight: 700; min-width: 180px; }
                .project-name a { color: var(--text-primary); text-decoration: none; }
                .project-name a:hover { color: var(--accent-green); text-decoration: underline; }
                .project-labels { flex: 1; display: flex; flex-wrap: wrap; gap: 0.5rem; }
                .project-label { font-size: 0.78rem; color: var(--text-secondary); }
                .project-actions { display: flex; gap: 0.5rem; align-items: center; }
                .btn { padding: 0.28rem 0.8rem; border-radius: 5px; font-size: 0.8rem;
                       font-weight: 600; cursor: pointer; border: 1px solid var(--border-color);
                       background: var(--bg-tertiary); color: var(--text-primary);
                       text-decoration: none; display: inline-flex; align-items: center; }
                .btn:hover { border-color: var(--accent-green); color: var(--accent-green); }
                .btn:disabled { opacity: 0.5; cursor: not-allowed; }
                .build-status { font-size: 0.75rem; color: var(--text-secondary); }
              </style>
            </head>
            <body>
            <header>
              <div>
                <h1>Documentation Portal</h1>
                <p>%d project(s)</p>
              </div>
              <div class="header-right">
            """.formatted(projects.size()));
        if (!production) {
            sb.append("""
                <label style="font-size:0.8rem;color:var(--text-secondary)">Theme:
                  <select id="theme-select">
                    <option value="dark-catppuccin">Dark Catppuccin</option>
                    <option value="dark-nord">Dark Nord</option>
                    <option value="dark-blue">Dark Blue</option>
                    <option value="dark-green">Dark Green</option>
                    <option value="dark-red">Dark Red</option>
                    <option value="light-clean">Light Clean</option>
                    <option value="light-warm">Light Warm</option>
                    <option value="light-blue">Light Blue</option>
                    <option value="light-green">Light Green</option>
                    <option value="light-red">Light Red</option>
                  </select>
                </label>
                <button class="btn btn-reload" id="reload-btn" onclick="doReload(this)">Reload</button>
                <span class="build-status" id="reload-status"></span>
            """);
        }
        sb.append("""
                <form class="portal-search" action="/search" method="get">
                  <input type="search" name="q" placeholder="Search all docs...">
                  <button type="submit">Search</button>
                </form>
              </div>
            </header>
            <main>
              <h2>Projects</h2>
              <div class="project-list">
            """);

        for (Project p : projects) {
            List<String> labels = readNavbarLabels(p.projectDir());
            sb.append("    <div class=\"project-row\">\n");
            sb.append("      <div class=\"project-name\"><a href=\"/").append(escHtml(p.name())).append("/\" target=\"_blank\" rel=\"noopener noreferrer\">").append(escHtml(p.name())).append("</a></div>\n");
            sb.append("      <div class=\"project-labels\">\n");
            for (String label : labels) {
                sb.append("        <span class=\"project-label\">").append(escHtml(label)).append("</span>\n");
            }
            sb.append("      </div>\n");
            if (!production) {
                sb.append("      <div class=\"project-actions\">\n");
                sb.append("        <button class=\"btn btn-build\" onclick=\"doBuild('").append(escHtml(p.name())).append("', this)\">Build</button>\n");
                sb.append("        <span class=\"build-status\" id=\"status-").append(escHtml(p.name())).append("\"></span>\n");
                sb.append("      </div>\n");
            }
            sb.append("    </div>\n");
        }

        sb.append("  </div>\n</main>\n");
        if (!production) {
            sb.append("""
            <script>
            (function() {
              const sel = document.getElementById('theme-select');
              const saved = localStorage.getItem('portal-theme') || 'dark-catppuccin';
              document.documentElement.setAttribute('data-theme', saved);
              sel.value = saved;
              sel.addEventListener('change', function() {
                document.documentElement.setAttribute('data-theme', this.value);
                localStorage.setItem('portal-theme', this.value);
              });
            })();
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
                  status.style.color = 'var(--accent-green)';
                  setTimeout(() => location.reload(), 800);
                } else {
                  status.textContent = 'Error: ' + (j.error || 'unknown');
                  status.style.color = '#e06060';
                }
              } catch (e) {
                status.textContent = 'Error: ' + e.message;
                status.style.color = '#e06060';
              }
              btn.disabled = false;
              btn.textContent = 'Build';
            }
            async function doReload(btn) {
              const status = document.getElementById('reload-status');
              btn.disabled = true;
              btn.textContent = 'Reloading...';
              status.textContent = '';
              try {
                const r = await fetch('/api/reload', {method: 'POST'});
                const j = await r.json();
                if (j.status === 'ok') {
                  if (j.added > 0) {
                    status.textContent = '+' + j.added + ' project(s) (' + j.ms + 'ms)';
                    status.style.color = 'var(--accent-green)';
                    setTimeout(() => location.reload(), 800);
                  } else {
                    status.textContent = 'No new projects';
                    status.style.color = 'var(--text-secondary)';
                  }
                } else {
                  status.textContent = 'Error: ' + (j.error || 'unknown');
                  status.style.color = '#e06060';
                }
              } catch (e) {
                status.textContent = 'Error: ' + e.message;
                status.style.color = '#e06060';
              }
              btn.disabled = false;
              btn.textContent = 'Reload';
            }
            </script>
            """);
        }
        sb.append("</body>\n</html>\n");

        respond(ex, 200, "text/html; charset=UTF-8", sb.toString());
    }

    // ---- Search results page (SSR) ------------------------------

    /** Number of search results displayed per page. */
    private static final int PAGE_SIZE = 100;

    /**
     * Handles {@code GET /search?q=...&page=N} requests. Performs a cross-project search
     * and renders the results as a server-side rendered HTML page with pagination.
     */
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
                    sb.append("<a class=\"result\" href=\"").append(escHtml(href)).append("\" target=\"_blank\" rel=\"noopener noreferrer\">\n");
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

    /**
     * Handles {@code GET /api/search?q=...} requests. Returns cross-project search results
     * as a JSON array with CORS headers for client-side consumption.
     */
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
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    /**
     * Performs a full-text search across all project indexes and merges the results.
     *
     * @param q the search query string
     * @return list of result maps, each containing project, title, pagePath, and summary
     */
    private List<Map<String, String>> globalSearch(String q) {
        var results = new ArrayList<Map<String, String>>();
        if (q.isBlank()) return results;
        for (Project proj : projects) {
            if (!Files.exists(proj.indexDir())) continue;
            collectHits(q, proj, results);
        }
        return results;
    }

    /** Queries a single project's Lucene index and appends matching results to the output list. */
    private void collectHits(String queryStr, Project proj, List<Map<String, String>> out) {
        LuceneSearcher s = searchers.get(proj.name());
        if (s == null) return;
        try {
            var hits = s.search(queryStr, 1000,
                new String[]{"title_idx", "doc_id_idx", "body"},
                Map.of("title_idx", 3.0f, "doc_id_idx", 5.0f, "body", 1.0f));
            for (var hit : hits) {
                out.add(Map.of(
                    "project",  proj.name(),
                    "title",    hit.title(),
                    "pagePath", hit.path(),
                    "summary",  hit.summary()
                ));
            }
        } catch (Exception e) {
            System.err.println("Search error [" + proj.name() + "]: " + e.getMessage());
        }
    }

    // ---- Per-project search endpoint ----------------------------

    /**
     * Handles per-project search requests ({@code GET /<project>/search?q=...}).
     * Returns results from a single project's index as a JSON array.
     */
    private void handleSearch(HttpExchange ex, Project proj) throws IOException {
        String q = HttpUtils.queryParam(ex, "q");
        String locale = HttpUtils.queryParam(ex, "locale");
        String key = (!locale.isEmpty()) ? proj.name() + ":" + locale : proj.name();
        LuceneSearcher s = searchers.getOrDefault(key, searchers.get(proj.name()));
        HttpUtils.respond(ex, 200, "application/json; charset=UTF-8", searchWithSearcher(q, proj, s));
    }

    /**
     * Executes a Lucene search within a single project's index, returning up to 20 results
     * as a JSON array. Each result includes title, path (prefixed with project name), and summary.
     */
    private String searchWithProject(String queryStr, Project proj) {
        return searchWithSearcher(queryStr, proj, searchers.get(proj.name()));
    }

    private String searchWithSearcher(String queryStr, Project proj, LuceneSearcher s) {
        if (s == null) return "[]";
        try {
            var hits = s.search(queryStr, 20,
                new String[]{"title_idx", "body"},
                Map.of("title_idx", 3.0f, "body", 1.0f));
            var sb = new StringBuilder("[");
            boolean first = true;
            for (var hit : hits) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                  .append("\"title\":").append(jsonStr(hit.title())).append(",")
                  .append("\"path\":").append(jsonStr((proj.name().isEmpty() ? "" : "/" + proj.name()) + hit.path())).append(",")
                  .append("\"pagePath\":").append(jsonStr(hit.path())).append(",")
                  .append("\"project\":").append(jsonStr(proj.name())).append(",")
                  .append("\"summary\":").append(jsonStr(hit.summary()))
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
     * Serves a static file from the project's output directory. Validates against path traversal
     * and returns 404 for missing files.
     */
    private void handleStatic(HttpExchange ex, Project proj, String rest) throws IOException {
        Path file = proj.staticDir().resolve(rest.replaceFirst("^/", "")).normalize();
        if (!file.startsWith(proj.staticDir())) { respond(ex, 403, "text/plain", "Forbidden"); return; }
        if (Files.isDirectory(file)) {
            file = file.resolve("index.html").normalize();
            if (!file.startsWith(proj.staticDir()) || !Files.exists(file)) {
                respond(ex, 404, "text/html",
                    "<html><body><h1>404 Not Found</h1><p>" + escHtml(rest) + "</p></body></html>");
                return;
            }
        } else if (!Files.exists(file)) {
            respond(ex, 404, "text/html",
                "<html><body><h1>404 Not Found</h1><p>" + escHtml(rest) + "</p></body></html>");
            return;
        }
        byte[] body = Files.readAllBytes(file);
        String ct = contentType(file.toString());
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        if (ct.startsWith("text/html")) {
            ex.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                "img-src 'self' data:; " +
                "font-src 'self' https://cdn.jsdelivr.net; " +
                "connect-src 'self'; " +
                "frame-ancestors 'none';");
        }
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    // ---- Helpers ------------------------------------------------

    /** Converts a page path like {@code /01_intro/setup.html} to a breadcrumb string like {@code intro › setup}. */
    private String pathToBreadcrumb(String path) {
        if (path == null || path.isEmpty()) return "";
        return Arrays.stream(path.replaceFirst("^/", "").split("/"))
            .map(seg -> seg.replaceFirst("^\\d+_", "").replaceAll("\\.html$", ""))
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.joining(" › "));
    }

    private String queryParam(HttpExchange ex, String key) { return HttpUtils.queryParam(ex, key); }
    private void respond(HttpExchange ex, int code, String ct, String body) throws IOException { HttpUtils.respond(ex, code, ct, body); }
    private String contentType(String path) { return HttpUtils.contentType(path); }

    /**
     * Reads the Docusaurus configuration file and extracts left-positioned navbar labels.
     * These labels are displayed on the portal index page to describe each project's content.
     *
     * @param projectDir root directory of the Docusaurus project
     * @return list of navbar label strings, or an empty list if the config is missing or unreadable
     */
    private List<String> readNavbarLabels(Path projectDir) {
        Path config = projectDir.resolve("docusaurus.config.ts");
        if (!Files.exists(config)) config = projectDir.resolve("docusaurus.config.js");
        if (!Files.exists(config)) return List.of();
        try {
            List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("//")) continue;
                if (line.contains("position: 'left'") || line.contains("position: \"left\"")) {
                    // Inline form: {to: '/blog', label: 'Blog', position: 'left'}
                    if (line.contains("label:")) {
                        String label = line.replaceFirst(".*label:\\s*['\"]", "").replaceFirst("['\"],?.*$", "");
                        if (!label.isBlank()) labels.add(label);
                        continue;
                    }
                    // Multi-line form: look forward only to avoid picking up previous item's label
                    int end = Math.min(lines.size() - 1, i + 3);
                    for (int j = i + 1; j <= end; j++) {
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

    private static String escHtml(String s) { return HttpUtils.escapeHtml(s); }
    private String jsonStr(String s) { return HttpUtils.jsonStr(s); }
}
