package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that each operating mode behaves according to the CLI options specification.
 *
 * <ul>
 *   <li>Mode 1 – Build-only: creates {@code static-html/}, no {@code search-index/},
 *       cleans output before rebuild</li>
 *   <li>Mode 2 – Single (dev): generated HTML contains Rebuild button and Theme selector</li>
 *   <li>Mode 3 – Portal: builds all projects at startup; portal page shows Build / Theme /
 *       Reload controls in dev mode, hides them in production mode</li>
 *   <li>Mode 4 – Production: generated HTML has no Rebuild button and no Theme selector</li>
 * </ul>
 */
class ModeTest {

    @TempDir
    Path tempDir;

    // ---- Test helpers -----------------------------------------------

    /**
     * Creates a minimal Docusaurus project directory under {@code tempDir}.
     * The project contains {@code docusaurus.config.js} and {@code docs/intro.md}.
     */
    private Path createProject(String name) throws IOException {
        Path projectDir = tempDir.resolve(name);
        Files.createDirectories(projectDir.resolve("docs"));
        Files.writeString(projectDir.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(projectDir.resolve("docs/intro.md"),
                "---\ntitle: Introduction\n---\n\n# Introduction\n\nHello world.");
        return projectDir;
    }

    /**
     * Returns the content of the first documentation HTML page found under {@code staticDir},
     * skipping the root {@code index.html} (which is a meta-refresh redirect).
     */
    private String firstDocPage(Path staticDir) throws IOException {
        try (var stream = Files.walk(staticDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".html"))
                    .filter(p -> !(p.getFileName().toString().equals("index.html") && p.getParent().equals(staticDir)))
                    .map(p -> {
                        try { return Files.readString(p); } catch (IOException e) { return ""; }
                    })
                    .filter(s -> s.contains("<html"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No doc HTML page found in " + staticDir));
        }
    }

    /** Performs a blocking HTTP GET and returns the response body as a string. */
    private String httpGet(String url) throws Exception {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    @DisplayName("injectDocTheme adds the theme before </head> and is idempotent")
    void injectDocTheme_insertsBeforeHead_idempotent() {
        String page = "<html><head><title>x</title></head><body>y</body></html>";
        String out = HttpUtils.injectDocTheme(page);
        assertTrue(out.contains("data-hs-theme"), "must inject the theme block");
        assertTrue(out.indexOf("data-hs-theme") < out.indexOf("</head>"),
                "theme must be injected before </head> so it overrides the doc's page.css");
        assertEquals(out, HttpUtils.injectDocTheme(out), "must be idempotent");
    }

    // ---- Mode 1: Build-only ----------------------------------------

    @Nested
    @Tag("S1")
    @DisplayName("Mode 1 – Build-only")
    class BuildOnlyMode {

        @Test
        @DisplayName("build creates static-html directory")
        void build_createsStaticHtmlDir() throws IOException {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            assertTrue(Files.isDirectory(proj.resolve("static-html")),
                    "static-html/ must be created by build");
        }

        @Test
        @DisplayName("build does not create search-index")
        void build_doesNotCreateSearchIndex() throws IOException {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            assertFalse(Files.exists(proj.resolve("search-index")),
                    "Build-only mode must not create search-index/");
        }

        @Test
        @DisplayName("build cleans output dir before rebuilding")
        void build_removesStaleFilesBeforeRebuild() throws IOException {
            Path proj = createProject("proj");
            Path outDir = proj.resolve("static-html");
            // Pre-populate output dir with a stale file
            Files.createDirectories(outDir);
            Path staleFile = outDir.resolve("stale-page.html");
            Files.writeString(staleFile, "<html>stale</html>");

            Main.build(proj.resolve("docs"), outDir, false);

            assertFalse(Files.exists(staleFile),
                    "Stale file must be removed when SiteBuilder cleans output dir before rebuild");
        }

        @Test
        @DisplayName("build generates intro.html from intro.md")
        void build_generatesHtmlFromMarkdown() throws IOException {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            assertTrue(Files.exists(proj.resolve("static-html/intro.html")),
                    "intro.md must produce intro.html");
        }
    }

    // ---- Mode 2: Single (dev) vs Mode 4: Production ----------------

    @Nested
    @Tag("S2")
    @DisplayName("Mode 2 – Single (dev) HTML output")
    class SingleDevMode {

        @Test
        @DisplayName("dev build HTML contains Rebuild button")
        void devBuild_html_hasRebuildButton() throws IOException {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            String html = firstDocPage(proj.resolve("static-html"));
            assertTrue(html.contains("id=\"rebuild-btn\""),
                    "Dev mode HTML must render <button id=\"rebuild-btn\">");
        }

        @Test
        @DisplayName("dev build HTML contains Theme selector")
        void devBuild_html_hasThemeSelector() throws IOException {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            String html = firstDocPage(proj.resolve("static-html"));
            assertTrue(html.contains("id=\"theme-sel\""),
                    "Dev mode HTML must render <select id=\"theme-sel\">");
        }
    }

    @Nested
    @Tag("S4")
    @DisplayName("Mode 4 – Production HTML output")
    class ProductionMode {

        @Test
        @DisplayName("production build HTML has no Rebuild button")
        void productionBuild_html_noRebuildButton() throws IOException {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);
            String html = firstDocPage(proj.resolve("static-html"));
            // CSS selector "#rebuild-btn {" may appear; check that the HTML element is absent
            assertFalse(html.contains("id=\"rebuild-btn\""),
                    "Production HTML must not render <button id=\"rebuild-btn\">");
        }

        @Test
        @DisplayName("production build HTML has no Theme selector")
        void productionBuild_html_noThemeSelector() throws IOException {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);
            String html = firstDocPage(proj.resolve("static-html"));
            // CSS selector "#theme-sel {" may appear; check that the HTML element is absent
            assertFalse(html.contains("id=\"theme-sel\""),
                    "Production HTML must not render <select id=\"theme-sel\">");
        }
    }

    // ---- Mode 3: Portal ----------------------------------------

    @Nested
    @Tag("S3")
    @DisplayName("Mode 3 – Portal mode")
    class PortalMode {

        @Test
        @DisplayName("startup builds only projects missing static-html or search-index")
        void startup_buildsOnlyMissingOutputs() throws IOException {
            Path proj1 = createProject("proj1");
            Path proj2 = createProject("proj2");

            // Pre-build proj1 so it already has both output directories
            Main.build(proj1.resolve("docs"), proj1.resolve("static-html"), false);
            Main.reindex(proj1.resolve("docs"), proj1.resolve("search-index"));

            // Write a sentinel file into proj1's static-html to detect whether it gets rebuilt
            Path sentinel = proj1.resolve("static-html/sentinel.html");
            Files.writeString(sentinel, "should-survive");

            // Simulate portal startup logic: skip if output already exists
            for (Path p : List.of(proj1, proj2)) {
                if (!Files.isDirectory(p.resolve("static-html"))) {
                    Main.build(p.resolve("docs"), p.resolve("static-html"), false);
                }
                if (!Files.isDirectory(p.resolve("search-index"))) {
                    Main.reindex(p.resolve("docs"), p.resolve("search-index"));
                }
            }

            // proj1 was skipped — sentinel must still exist
            assertTrue(Files.exists(sentinel), "proj1 must not be rebuilt when static-html already exists");
            // proj2 was built from scratch
            assertTrue(Files.isDirectory(proj2.resolve("static-html")), "proj2 static-html must be created");
            assertTrue(Files.isDirectory(proj2.resolve("search-index")), "proj2 search-index must be created");
        }

        @Test
        @DisplayName("commented-out navbar items must not appear as labels")
        void navbarLabels_ignoredWhenCommentedOut() throws Exception {
            Path proj = createProject("proj");
            // Config with one active label and one commented-out label
            Files.writeString(proj.resolve("docusaurus.config.js"), """
                module.exports = {
                  themeConfig: { navbar: { items: [
                    { label: 'Active', position: 'left' },
                    // { label: 'Commented', position: 'left' },
                  ] } }
                };
                """);
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false, null);
            HttpServer http = ps.start();
            try {
                String html = httpGet("http://localhost:" + http.getAddress().getPort() + "/");
                assertTrue(html.contains("Active"), "Active label must appear");
                assertFalse(html.contains("Commented"), "Commented-out label must not appear");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("portal page (dev) contains Build button per project row")
        void devPortalPage_hasBuildButton() throws Exception {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false, null);
            HttpServer http = ps.start();
            try {
                String html = httpGet("http://localhost:" + http.getAddress().getPort() + "/");
                assertTrue(html.contains("btn-build"),
                        "Dev portal page must contain Build button for each project");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("portal page (dev) contains Theme selector and Scan Works Dir button")
        void devPortalPage_hasThemeAndScanWorksDir() throws Exception {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false, null);
            HttpServer http = ps.start();
            try {
                String html = httpGet("http://localhost:" + http.getAddress().getPort() + "/");
                assertTrue(html.contains("id=\"theme-select\""),
                        "Dev portal page must render Theme selector element");
                assertTrue(html.contains("id=\"scan-works-dir-btn\""),
                        "Dev portal page must render Scan Works Dir button element");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("portal page (dev) shows the title without a project count")
        void devPortalPage_hidesProjectCount() throws Exception {
            Path proj1 = createProject("proj1");
            Path proj2 = createProject("proj2");
            for (Path p : List.of(proj1, proj2)) {
                Main.build(p.resolve("docs"), p.resolve("static-html"), false);
            }
            PortalServer ps = new PortalServer(tempDir, List.of(proj1, proj2), 0, false, null);
            HttpServer http = ps.start();
            try {
                String html = httpGet("http://localhost:" + http.getAddress().getPort() + "/");
                assertTrue(html.contains("Documentation Portal"),
                        "Portal header must show the title");
                assertFalse(html.contains("project(s)</p>"),
                        "Portal header must not show the project count");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("portal page (production) has no Build button, no Theme, no Scan Works Dir")
        void productionPortalPage_hidesDevControls() throws Exception {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, true, null);
            HttpServer http = ps.start();
            try {
                String html = httpGet("http://localhost:" + http.getAddress().getPort() + "/");
                assertFalse(html.contains("btn-build"),
                        "Production portal must not contain Build button");
                // CSS has "select#theme-select"; check HTML attribute is absent
                assertFalse(html.contains("id=\"theme-select\""),
                        "Production portal must not render Theme selector element");
                assertFalse(html.contains("id=\"scan-works-dir-btn\""),
                        "Production portal must not render Scan Works Dir button element");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("project name link loads into the right-pane iframe (no new tab)")
        void portalPage_projectLink_loadsInRightPane() throws Exception {
            Path proj = createProject("myproj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false, null);
            HttpServer http = ps.start();
            try {
                String html = httpGet("http://localhost:" + http.getAddress().getPort() + "/");
                // The link is a real anchor (so right-click / Ctrl-click can still open a new tab),
                // tagged project-link so the portal script intercepts a plain left-click and loads
                // the project into the right-pane iframe instead of opening a new browser tab.
                assertTrue(html.contains("class=\"project-link\" href=\"/myproj/\""),
                        "Project name link must be a project-link anchor to /myproj/");
                assertFalse(html.contains("target=\"_blank\""),
                        "Portal page must not open projects in a new tab");
                assertTrue(html.contains("id=\"doc-frame\""),
                        "Portal must contain the right-pane iframe");
                assertTrue(html.contains("data-hs-responsive"),
                        "SSR pages must carry the shared responsive style");
                assertTrue(html.contains("[data-theme=\"dark-catppuccin\"]"),
                        "Portal must emit the shared theme palette (HttpUtils.themeVariables)");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("served pages allow same-origin framing (enables the right-pane iframe)")
        void portalPage_servedPages_allowSameOriginFraming() throws Exception {
            Path proj = createProject("myproj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false, null);
            HttpServer http = ps.start();
            try {
                int port = http.getAddress().getPort();
                var client = HttpClient.newHttpClient();
                var request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/myproj/")).GET().build();
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                String xfo = resp.headers().firstValue("X-Frame-Options").orElse("");
                String csp = resp.headers().firstValue("Content-Security-Policy").orElse("");
                // The portal embeds each project in its own same-origin iframe, so framing
                // must be allowed for same origin but still blocked cross-origin.
                assertEquals("SAMEORIGIN", xfo,
                        "Served pages must allow same-origin framing, not DENY");
                assertTrue(csp.contains("frame-ancestors 'self'"),
                        "CSP must allow same-origin framing via frame-ancestors 'self'");
                assertFalse(csp.contains("frame-ancestors 'none'"),
                        "CSP must not block framing with frame-ancestors 'none'");
                // Docusaurus static pages ship their own responsive stylesheet and must not
                // receive the injected html-saurus shared style.
                assertFalse(resp.body().contains("data-hs-responsive"),
                        "Static project pages must not carry the injected shared style");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("scan works dir API adds only new projects, skips existing ones")
        void scanWorksDirApi_addsOnlyNewProjects() throws Exception {
            Path proj1 = createProject("existing");
            Main.build(proj1.resolve("docs"), proj1.resolve("static-html"), false);
            Main.reindex(proj1.resolve("docs"), proj1.resolve("search-index"));

            PortalServer ps = new PortalServer(tempDir, List.of(proj1), 0, false, null);
            HttpServer http = ps.start();
            int port = http.getAddress().getPort();

            // Add a new project to tempDir after server startup
            Path proj2 = createProject("newproject");

            try {
                var client = HttpClient.newHttpClient();
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/scan-works-dir"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                String response = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
                assertTrue(response.contains("\"added\":1"),
                        "Scan should report 1 newly added project");
                assertTrue(response.contains("\"total\":2"),
                        "Scan should report total of 2 projects after adding new one");
            } finally {
                http.stop(0);
            }
        }
    }

    // ---- Production mode security: closed API surface --------------
    //
    // See ProductionModeSpec_260806_oo01 (doc_SCIVICS002, html-saurus/010_concepts): in
    // production mode only "/" (static files) and "/search" may be reachable, from either
    // server class, and "/search" must never include srcPath (a local filesystem path).

    @Nested
    @Tag("S_prod")
    @DisplayName("Production mode security — closed API surface")
    class ProductionSecurity {

        private int status(HttpClient client, String url, boolean post) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(url));
            builder = post ? builder.POST(HttpRequest.BodyPublishers.noBody()) : builder.GET();
            return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }

        @Test
        @DisplayName("SearchServer (production): /mcp, /api/build-all, /api/related, /api/translate are closed")
        void searchServerProduction_closesDevAndApiEndpoints() throws Exception {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);
            Path indexDir = proj.resolve("search-index");
            Main.reindex(proj.resolve("docs"), indexDir);
            SearchServer ss = new SearchServer(proj.resolve("static-html"), indexDir, 0, () -> {}, true,
                    proj.resolve("docs"), null);
            HttpServer http = ss.start();
            try {
                int port = http.getAddress().getPort();
                var client = HttpClient.newHttpClient();
                String base = "http://localhost:" + port;
                assertNotEquals(200, status(client, base + "/mcp", true), "/mcp must be closed in production");
                assertNotEquals(200, status(client, base + "/api/build-all", true),
                        "/api/build-all must be closed in production");
                assertNotEquals(200, status(client, base + "/api/related?path=/intro.html", false),
                        "/api/related must be closed in production");
                assertNotEquals(200, status(client, base + "/api/translate?lang=English", true),
                        "/api/translate must be closed in production");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("SearchServer (production): /search stays open and never includes srcPath")
        void searchServerProduction_searchOpenWithoutSrcPath() throws Exception {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);
            Path indexDir = proj.resolve("search-index");
            Main.reindex(proj.resolve("docs"), indexDir);
            SearchServer ss = new SearchServer(proj.resolve("static-html"), indexDir, 0, () -> {}, true,
                    proj.resolve("docs"), null);
            HttpServer http = ss.start();
            try {
                int port = http.getAddress().getPort();
                String json = httpGet("http://localhost:" + port + "/search?q=Introduction");
                assertFalse(json.equals("[]"), "Search must return at least one hit for 'Introduction'");
                assertFalse(json.contains("srcPath"),
                        "Production /search response must never include srcPath (local filesystem path)");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("SearchServer (dev, not production): /search still includes srcPath")
        void searchServerDev_searchIncludesSrcPath() throws Exception {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            Path indexDir = proj.resolve("search-index");
            Main.reindex(proj.resolve("docs"), indexDir);
            SearchServer ss = new SearchServer(proj.resolve("static-html"), indexDir, 0, () -> {}, false,
                    proj.resolve("docs"), null);
            HttpServer http = ss.start();
            try {
                int port = http.getAddress().getPort();
                String json = httpGet("http://localhost:" + port + "/search?q=Introduction");
                assertTrue(json.contains("srcPath"),
                        "Dev-mode /search response must include srcPath (agents/authors Read the source from it)");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("PortalServer (production): /mcp, /api/*, /related*, /search-semantic are closed")
        void portalServerProduction_closesDevAndApiEndpoints() throws Exception {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);
            Main.reindexAll(proj, true);
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, true, null);
            HttpServer http = ps.start();
            try {
                var client = HttpClient.newHttpClient();
                String base = "http://localhost:" + http.getAddress().getPort();
                assertNotEquals(200, status(client, base + "/mcp", true), "/mcp must be closed");
                assertNotEquals(200, status(client, base + "/api/resolve?id=Introduction", false),
                        "/api/resolve must be closed");
                assertNotEquals(200, status(client, base + "/api/siblings?id=Introduction", false),
                        "/api/siblings must be closed");
                assertNotEquals(200, status(client, base + "/api/prerequisites?id=Introduction", false),
                        "/api/prerequisites must be closed");
                assertNotEquals(200, status(client, base + "/api/prerequisite-of?id=Introduction", false),
                        "/api/prerequisite-of must be closed");
                assertNotEquals(200, status(client, base + "/api/search?q=Introduction", false),
                        "/api/search must be closed");
                assertNotEquals(200, status(client, base + "/api/related?path=/proj/intro.html", false),
                        "/api/related must be closed");
                assertNotEquals(200, status(client, base + "/api/related-semantic?path=/proj/intro.html", false),
                        "/api/related-semantic must be closed");
                assertNotEquals(200, status(client, base + "/api/search-semantic?q=Introduction", false),
                        "/api/search-semantic must be closed");
                assertNotEquals(200, status(client, base + "/related?path=/proj/intro.html", false),
                        "/related page must be closed");
                assertNotEquals(200, status(client, base + "/related-semantic?path=/proj/intro.html", false),
                        "/related-semantic page must be closed");
                assertNotEquals(200, status(client, base + "/search-semantic?q=Introduction", false),
                        "/search-semantic page must be closed");
                assertNotEquals(200, status(client, base + "/api/import/pdf/start", true),
                        "/api/import/pdf/start must be closed");
                assertNotEquals(200, status(client, base + "/api/import/pdf/status?jobId=x", false),
                        "/api/import/pdf/status must be closed");
                assertNotEquals(200, status(client, base + "/api/import/pdf/stop", true),
                        "/api/import/pdf/stop must be closed");
                assertNotEquals(200, status(client, base + "/api/import/word", true),
                        "/api/import/word must be closed");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("PortalServer (production): SSR /search and per-project static files stay open")
        void portalServerProduction_searchAndStaticOpen() throws Exception {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);
            Main.reindexAll(proj, true);
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, true, null);
            HttpServer http = ps.start();
            try {
                var client = HttpClient.newHttpClient();
                String base = "http://localhost:" + http.getAddress().getPort();
                assertEquals(200, status(client, base + "/search?q=Introduction", false),
                        "SSR /search page must stay open in production");
                assertEquals(200, status(client, base + "/proj/", false),
                        "Per-project static file serving must stay open in production");
            } finally {
                http.stop(0);
            }
        }
    }
}
