package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    // ---- Mode 1: Build-only ----------------------------------------

    @Nested
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
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false);
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
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false);
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
        @DisplayName("portal page (dev) contains Theme selector and Reload button")
        void devPortalPage_hasThemeAndReload() throws Exception {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false);
            HttpServer http = ps.start();
            try {
                String html = httpGet("http://localhost:" + http.getAddress().getPort() + "/");
                assertTrue(html.contains("id=\"theme-select\""),
                        "Dev portal page must render Theme selector element");
                assertTrue(html.contains("id=\"reload-btn\""),
                        "Dev portal page must render Reload button element");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("portal page (dev) shows project count in header")
        void devPortalPage_showsProjectCount() throws Exception {
            Path proj1 = createProject("proj1");
            Path proj2 = createProject("proj2");
            for (Path p : List.of(proj1, proj2)) {
                Main.build(p.resolve("docs"), p.resolve("static-html"), false);
            }
            PortalServer ps = new PortalServer(tempDir, List.of(proj1, proj2), 0, false);
            HttpServer http = ps.start();
            try {
                String html = httpGet("http://localhost:" + http.getAddress().getPort() + "/");
                assertTrue(html.contains("2 project(s)"),
                        "Portal header must show detected project count");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("portal page (production) has no Build button, no Theme, no Reload")
        void productionPortalPage_hidesDevControls() throws Exception {
            Path proj = createProject("proj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, true);
            HttpServer http = ps.start();
            try {
                String html = httpGet("http://localhost:" + http.getAddress().getPort() + "/");
                assertFalse(html.contains("btn-build"),
                        "Production portal must not contain Build button");
                // CSS has "select#theme-select"; check HTML attribute is absent
                assertFalse(html.contains("id=\"theme-select\""),
                        "Production portal must not render Theme selector element");
                assertFalse(html.contains("id=\"reload-btn\""),
                        "Production portal must not render Reload button element");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("project name link opens in new tab (target=_blank)")
        void portalPage_projectLink_opensInNewTab() throws Exception {
            Path proj = createProject("myproj");
            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);
            PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, false);
            HttpServer http = ps.start();
            try {
                String html = httpGet("http://localhost:" + http.getAddress().getPort() + "/");
                // Link must have target="_blank" rel="noopener noreferrer"
                assertTrue(html.contains("target=\"_blank\""),
                        "Project name link must open in a new tab");
                assertTrue(html.contains("rel=\"noopener noreferrer\""),
                        "Project name link must include rel=noopener noreferrer");
            } finally {
                http.stop(0);
            }
        }

        @Test
        @DisplayName("reload API adds only new projects, skips existing ones")
        void reloadApi_addsOnlyNewProjects() throws Exception {
            Path proj1 = createProject("existing");
            Main.build(proj1.resolve("docs"), proj1.resolve("static-html"), false);
            Main.reindex(proj1.resolve("docs"), proj1.resolve("search-index"));

            PortalServer ps = new PortalServer(tempDir, List.of(proj1), 0, false);
            HttpServer http = ps.start();
            int port = http.getAddress().getPort();

            // Add a new project to tempDir after server startup
            Path proj2 = createProject("newproject");

            try {
                var client = HttpClient.newHttpClient();
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/reload"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                String response = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
                assertTrue(response.contains("\"added\":1"),
                        "Reload should report 1 newly added project");
                assertTrue(response.contains("\"total\":2"),
                        "Reload should report total of 2 projects after adding new one");
            } finally {
                http.stop(0);
            }
        }
    }
}
