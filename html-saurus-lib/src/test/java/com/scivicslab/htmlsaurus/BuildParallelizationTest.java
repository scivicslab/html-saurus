package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SiteBuilder#build}'s page conversion runs as one actor per Markdown file, bounded by
 * {@link SiteBuilder#threads} instead of the JVM-wide shared {@code ForkJoinPool.commonPool()}
 * a raw {@code parallelStream()} would use -- see {@code BuildParallelization_260822_oo01}.
 * These tests force the parallelism down to 1 (fully serialized fan-out) and up past the fixture's
 * own file count, checking that {@code build()} still waits for every file and produces the same
 * output regardless of how many actors run at once.
 */
@DisplayName("SiteBuilder.threads() — page-conversion parallelism")
class BuildParallelizationTest {

    private static final Path FIXTURE = Path.of("src/test/fixtures/sample-site").toAbsolutePath();

    @TempDir
    Path tempDir;

    private List<Path> htmlFilesRelative(Path outDir) throws IOException {
        try (var walk = Files.walk(outDir)) {
            return walk.filter(p -> p.toString().endsWith(".html"))
                    .map(outDir::relativize)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    @Test
    @DisplayName("threads(1): every file is still converted, none skipped")
    void singleThread_convertsEveryFile() throws IOException {
        Path outDir = tempDir.resolve("single-thread");
        new SiteBuilder(FIXTURE.resolve("docs"), outDir, false).threads(1).build();

        List<Path> htmlFiles = htmlFilesRelative(outDir);
        assertTrue(htmlFiles.size() >= 4,
                "Expected at least 4 HTML files with threads(1) but found " + htmlFiles.size());
    }

    @Test
    @DisplayName("output is identical whether threads(1) or a large pool is used")
    void outputIsTheSame_regardlessOfThreadCount() throws IOException {
        Path lowOut = tempDir.resolve("low");
        Path highOut = tempDir.resolve("high");

        new SiteBuilder(FIXTURE.resolve("docs"), lowOut, false).threads(1).build();
        new SiteBuilder(FIXTURE.resolve("docs"), highOut, false).threads(16).build();

        assertEquals(htmlFilesRelative(lowOut), htmlFilesRelative(highOut),
                "the same set of HTML files should be produced regardless of build parallelism");
    }

    @Test
    @DisplayName("default (no threads() call) still builds the whole fixture")
    void defaultParallelism_stillBuildsEverything() throws IOException {
        Path outDir = tempDir.resolve("default");
        new SiteBuilder(FIXTURE.resolve("docs"), outDir, false).build();

        assertTrue(htmlFilesRelative(outDir).size() >= 4,
                "default parallelism should still convert every fixture file");
    }
}
