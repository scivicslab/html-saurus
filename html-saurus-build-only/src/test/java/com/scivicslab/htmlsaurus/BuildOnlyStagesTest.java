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
 * build-only mode: each stage writes what it is supposed to write, and the stages that were not
 * asked for leave nothing behind.
 */
class BuildOnlyStagesTest {

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
    @DisplayName("build creates static-html directory")
    void build_createsStaticHtmlDir() throws IOException {
        Path proj = createProject("proj");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        assertTrue(Files.isDirectory(proj.resolve("static-html")),
                "static-html/ must be created by build");
    }

    @Test
    @DisplayName("build does not create search-index")
    void build_doesNotCreateSearchIndex() throws IOException {
        Path proj = createProject("proj");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
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

        BuildStages.build(proj.resolve("docs"), outDir, false);

        assertFalse(Files.exists(staleFile),
                "Stale file must be removed when SiteBuilder cleans output dir before rebuild");
    }

    @Test
    @DisplayName("build generates intro.html from intro.md")
    void build_generatesHtmlFromMarkdown() throws IOException {
        Path proj = createProject("proj");
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        assertTrue(Files.exists(proj.resolve("static-html/intro/index.html")),
                "intro.md must produce intro/index.html");
    }
}
