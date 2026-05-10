package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies build-only mode (no --serve, no --portal-mode) against the sample-site fixture.
 *
 * <p>The sample-site fixture under src/test/fixtures/sample-site/ contains:
 * <ul>
 *   <li>docs/ with numeric-prefix directories and a flat intro.md</li>
 *   <li>blog/ with a flat .md post AND a subdirectory-based post (index.md inside dated dir)</li>
 * </ul>
 *
 * <p>The subdirectory blog post pattern was the regression introduced by commit d845aad.
 * This test ensures it is discovered and rendered correctly.
 */
@DisplayName("Build-only mode tests (sample-site fixture)")
class BuildOnlyModeTest {

    private static final Path FIXTURE = Path.of("src/test/fixtures/sample-site").toAbsolutePath();

    @TempDir
    Path tempDir;

    /**
     * Runs build-only mode with output redirected to a temp directory so the fixture
     * source is never modified.
     */
    private Path runBuild() throws IOException {
        Path outDir = tempDir.resolve("static-html");
        Main.build(FIXTURE.resolve("docs"), outDir, false);
        return outDir;
    }

    @Test
    @DisplayName("static-html directory is created")
    void staticHtmlDirectoryCreated() throws IOException {
        Path outDir = runBuild();
        assertTrue(Files.isDirectory(outDir), "static-html/ should be created by build");
    }

    @Test
    @DisplayName("intro.md is rendered as intro.html")
    void introPageRendered() throws IOException {
        Path outDir = runBuild();
        List<Path> htmlFiles = Files.walk(outDir)
                .filter(p -> p.toString().endsWith(".html"))
                .collect(Collectors.toList());
        assertTrue(htmlFiles.stream().anyMatch(p -> p.toString().contains("intro")),
                "intro.md should produce an HTML file");
    }

    @Test
    @DisplayName("numeric-prefix doc (010_concepts/010_Overview) is rendered")
    void numericPrefixDocRendered() throws IOException {
        Path outDir = runBuild();
        List<Path> htmlFiles = Files.walk(outDir)
                .filter(p -> p.toString().endsWith(".html"))
                .collect(Collectors.toList());
        assertTrue(htmlFiles.stream().anyMatch(p -> p.toString().contains("Overview")),
                "010_concepts/010_Overview/010_Overview.md should produce an HTML file");
    }

    @Test
    @DisplayName("flat blog post (2025-12-21-welcome.md) is rendered")
    void flatBlogPostRendered() throws IOException {
        Path outDir = runBuild();
        // blog output lands in blog/ subdirectory of static-html
        Path blogDir = outDir.resolve("blog");
        assertTrue(Files.isDirectory(blogDir), "blog/ directory should be created");
        List<Path> blogFiles = Files.walk(blogDir)
                .filter(p -> p.toString().endsWith(".html"))
                .collect(Collectors.toList());
        assertTrue(blogFiles.stream().anyMatch(p -> p.toString().contains("welcome")),
                "2025-12-21-welcome.md should produce an HTML file");
    }

    @Test
    @DisplayName("subdirectory blog post (2026-04-05-sample-post/index.md) is rendered")
    void subdirectoryBlogPostRendered() throws IOException {
        Path outDir = runBuild();
        Path blogDir = outDir.resolve("blog");
        assertTrue(Files.isDirectory(blogDir), "blog/ directory should be created");
        List<Path> blogFiles = Files.walk(blogDir)
                .filter(p -> p.toString().endsWith(".html"))
                .collect(Collectors.toList());
        assertTrue(blogFiles.stream().anyMatch(p -> p.toString().contains("sample-post")),
                "2026-04-05-sample-post/index.md should produce an HTML file — " +
                "failure here indicates subdirectory blog post discovery is broken");
    }

    @Test
    @DisplayName("build produces at least 4 HTML files (intro + overview + 2 blog posts)")
    void minimumPageCount() throws IOException {
        Path outDir = runBuild();
        long count = Files.walk(outDir)
                .filter(p -> p.toString().endsWith(".html"))
                .count();
        assertTrue(count >= 4,
                "Expected at least 4 HTML files but found " + count);
    }
}
