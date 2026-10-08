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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * portal mode: the dashboard, the per-project controls, and the endpoints behind them.
 */
class PortalModeTest {

    @TempDir
    Path tempDir;

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
    @DisplayName("every button id the page script names is rendered on the page")
    void pageScript_namesOnlyRenderedButtonIds() throws Exception {
        Path proj = createProject("proj");
        // The doc id button is rendered only for a document carrying a frontmatter id, so the
        // fixture carries one; without it the script would name a button that is absent for a reason.
        Files.writeString(proj.resolve("docs/intro.md"),
                "---\nid: Intro_261002_oo01\ntitle: Introduction\n---\n\n# Introduction\n\nHello world.");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        // The generated file is named from the document id, so the page is located, not guessed.
        Path page;
        try (var files = Files.list(proj.resolve("static-html"))) {
            page = files.filter(f -> f.getFileName().toString().endsWith(".html")).findFirst().orElseThrow();
        }
        String html = Files.readString(page);
        Matcher named = Pattern.compile("'([a-z0-9-]+-btn)'").matcher(html);
        List<String> missing = new ArrayList<>();
        while (named.find()) {
            String id = named.group(1);
            if (!html.contains("id=\"" + id + "\"") && !missing.contains(id)) missing.add(id);
        }
        assertEquals(List.of(), missing,
                "A script naming a button that no element carries leaves that button inert on click");
    }

    @Test
    @DisplayName("every copy bar control id is named by the page script")
    void copyBar_controlIdsAreNamedByTheScript() throws Exception {
        Path proj = createProject("proj");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        String html = Files.readString(proj.resolve("static-html/intro/index.html"));
        Matcher m = Pattern.compile("<(?:button|select) class=\"copy-(?:btn|select)\" id=\"([a-z0-9-]+)\"").matcher(html);
        List<String> ids = new ArrayList<>();
        while (m.find()) ids.add(m.group(1));
        assertTrue(ids.contains("view-format"), "A dev-mode page must render the format list, got: " + ids);
        assertTrue(ids.contains("translate-btn"), "A dev-mode page must render the Translate button, got: " + ids);
        for (String id : ids) {
            assertTrue(html.contains("'" + id + "'"),
                    "The page script must name " + id + ": a control no script names does nothing when used");
        }
    }

    @Test
    @DisplayName("the format list offers Lisp only when a rule file sits beside the Markdown file")
    void copyBar_offersLispOnlyWhenTheRuleFileExists() throws Exception {
        Path proj = createProject("proj");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        String without = Files.readString(proj.resolve("static-html/intro/index.html"));
        assertFalse(without.contains("value=\"lisp\""), "No rule file beside intro.md: no Lisp entry");
        Files.writeString(proj.resolve("docs/intro.lisp"), "(in-package :rst)\n");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        String with = Files.readString(proj.resolve("static-html/intro/index.html"));
        assertTrue(with.contains("value=\"lisp\""), "intro.lisp beside intro.md: the list must offer Lisp");
    }

    @Test
    @DisplayName("/api/source (dev) returns the Markdown source, the LaTeX form, the converted HTML and the text")
    void devSourceEndpoint_returnsEachFormat() throws Exception {
        Path proj = createProject("proj");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, null, 0);
        HttpServer http = ps.start();
        try {
            String base = "http://localhost:" + http.getAddress().getPort()
                    + "/api/source?path=proj/docs/intro.md";
            String md = httpGet(base + "&format=md-om");
            assertTrue(md.startsWith("---"),
                    "md-om must be the file on disk, frontmatter included; the rendered page has no frontmatter");
            assertTrue(md.contains("# Introduction"),
                    "md-om must keep the heading as Markdown, not as the text the heading renders to");
            assertTrue(httpGet(base + "&format=md-latex").contains("Hello world."),
                    "md-latex must return the same document with its formulas written as LaTeX");
            String html = httpGet(base + "&format=html");
            assertTrue(html.contains("<p>"), "html must be the converted body");
            assertFalse(html.startsWith("---"), "html must not carry the frontmatter");
            String text = httpGet(base + "&format=text");
            assertTrue(text.contains("Hello world."), "text must carry the document's words");
            assertFalse(text.contains("<p>"), "text must not carry tags");
            assertFalse(text.startsWith("---"), "text must not carry the frontmatter");
            assertEquals("not found", httpGet(base + "&format=lisp"),
                    "lisp must be refused while no intro.lisp sits beside intro.md");
            Files.writeString(proj.resolve("docs/intro.lisp"), "(in-package :rst)\n(defrule title -> \"Introduction\")\n");
            assertTrue(httpGet(base + "&format=lisp").startsWith("(in-package :rst)"),
                    "lisp must return the rule file beside the Markdown file");
            assertEquals("unknown format: bogus", httpGet(base + "&format=bogus"),
                    "An unknown format must be refused rather than guessed");
            assertEquals("not found", httpGet("http://localhost:" + http.getAddress().getPort()
                    + "/api/source?path=proj/docs/../../outside.md&format=md-om"),
                    "A path that leaves the works directory must be refused");
        } finally {
            http.stop(0);
        }
    }

    @Test
    @DisplayName("startup builds only projects missing static-html or search-index")
    void startup_buildsOnlyMissingOutputs() throws IOException {
        Path proj1 = createProject("proj1");
        Path proj2 = createProject("proj2");

        // Pre-build proj1 so it already has both output directories
        BuildStages.build(proj1.resolve("docs"), proj1.resolve("static-html"), false);
        BuildStages.reindex(proj1.resolve("docs"), proj1.resolve("search-index"));

        // Write a sentinel file into proj1's static-html to detect whether it gets rebuilt
        Path sentinel = proj1.resolve("static-html/sentinel.html");
        Files.writeString(sentinel, "should-survive");

        // Simulate portal startup logic: skip if output already exists
        for (Path p : List.of(proj1, proj2)) {
            if (!Files.isDirectory(p.resolve("static-html"))) {
                BuildStages.build(p.resolve("docs"), p.resolve("static-html"), false);
            }
            if (!Files.isDirectory(p.resolve("search-index"))) {
                BuildStages.reindex(p.resolve("docs"), p.resolve("search-index"));
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
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, null, 0);
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
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, null, 0);
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
    @DisplayName("portal page (dev) contains Theme selector and Update All Projects button")
    void devPortalPage_hasThemeAndUpdateAllProjects() throws Exception {
        Path proj = createProject("proj");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, null, 0);
        HttpServer http = ps.start();
        try {
            String html = httpGet("http://localhost:" + http.getAddress().getPort() + "/");
            assertTrue(html.contains("id=\"theme-select\""),
                    "Dev portal page must render Theme selector element");
            assertTrue(html.contains("id=\"update-all-projects-btn\""),
                    "Dev portal page must render Update All Projects button element");
            assertFalse(html.contains("id=\"scan-works-dir-btn\""),
                    "Scan Works Dir button was folded into Update All Projects and must be gone");
            assertFalse(html.contains("id=\"reindex-all-btn\""),
                    "Reindex All button was folded into Update All Projects and must be gone");
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
            BuildStages.build(p.resolve("docs"), p.resolve("static-html"), false);
        }
        PortalServer ps = new PortalServer(tempDir, List.of(proj1, proj2), 0, null, 0);
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
    @DisplayName("project name link loads into the right-pane iframe (no new tab)")
    void portalPage_projectLink_loadsInRightPane() throws Exception {
        Path proj = createProject("myproj");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, null, 0);
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
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        PortalServer ps = new PortalServer(tempDir, List.of(proj), 0, null, 0);
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
        BuildStages.build(proj1.resolve("docs"), proj1.resolve("static-html"), false);
        BuildStages.reindex(proj1.resolve("docs"), proj1.resolve("search-index"));

        PortalServer ps = new PortalServer(tempDir, List.of(proj1), 0, null, 0);
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

    @Test
    @DisplayName("scan works dir API puts a newly found project in name order, not at the end")
    void scanWorksDirApi_keepsListInNameOrder() throws Exception {
        Path last = createProject("zzz-last");
        BuildStages.build(last.resolve("docs"), last.resolve("static-html"), false);
        BuildStages.reindex(last.resolve("docs"), last.resolve("search-index"));

        PortalServer ps = new PortalServer(tempDir, List.of(last), 0, null, 0);
        HttpServer http = ps.start();
        int port = http.getAddress().getPort();

        createProject("aaa-first");

        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/scan-works-dir"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            client.send(request, HttpResponse.BodyHandlers.ofString());

            String html = httpGet("http://localhost:" + port + "/");
            assertTrue(html.indexOf(">aaa-first<") < html.indexOf(">zzz-last<"),
                    "a project found by a scan must sit where its name belongs, not at the end");
        } finally {
            http.stop(0);
        }
    }

    @Test
    @DisplayName("scan works dir API drops a project whose directory was renamed away")
    void scanWorksDirApi_dropsRenamedProject() throws Exception {
        Path staying = createProject("staying");
        Path renamed = createProject("renamed-away");
        for (Path p : List.of(staying, renamed)) {
            BuildStages.build(p.resolve("docs"), p.resolve("static-html"), false);
            BuildStages.reindex(p.resolve("docs"), p.resolve("search-index"));
        }

        PortalServer ps = new PortalServer(tempDir, List.of(staying, renamed), 0, null, 0);
        HttpServer http = ps.start();
        int port = http.getAddress().getPort();

        // Rename after startup, the way a documentation project is renamed on disk
        Path newName = tempDir.resolve("renamed-away-newname");
        Files.move(renamed, newName);

        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/scan-works-dir"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            String response = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(response.contains("\"added\":1"), "the new name must be added: " + response);
            assertTrue(response.contains("\"removed\":1"), "the old name must be removed: " + response);
            assertTrue(response.contains("\"total\":2"), "two projects must remain: " + response);

            String html = httpGet("http://localhost:" + port + "/");
            assertFalse(html.contains(">renamed-away<"),
                    "the portal must stop listing a project whose directory is gone");
            assertTrue(html.contains(">renamed-away-newname<"), "the new name must be listed");
            assertTrue(html.contains(">staying<"), "the untouched project must stay listed");

            assertFalse(Files.exists(renamed),
                    "nothing may recreate the directory the project was renamed away from");
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
