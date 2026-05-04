package com.scivicslab.htmlsaurus;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.*;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies SearchIndexer behaviour as specified in
 * HtmlSaurusSearchIndex_260401_oo01 and SearchIndexI18n_260401_oo01.
 *
 * <p>Rules under test:
 * <ul>
 *   <li>path field uses clean URLs (numeric prefixes stripped).</li>
 *   <li>path field uses production-mode clean URLs when {@code production=true}.</li>
 *   <li>path field includes locale prefix for non-Japanese locales.</li>
 *   <li>Japanese locale uses JapaneseAnalyzer (index is searchable in Japanese).</li>
 *   <li>English locale uses StandardAnalyzer (index is searchable in English).</li>
 *   <li>title and summary fields are stored.</li>
 * </ul>
 */
class SearchIndexerTest {

    @TempDir
    Path tempDir;

    private Path createDocsDir(String name) throws IOException {
        Path docsDir = tempDir.resolve(name);
        Files.createDirectories(docsDir);
        return docsDir;
    }

    private void writeDoc(Path docsDir, String relPath, String title, String body) throws IOException {
        Path file = docsDir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntitle: " + title + "\n---\n\n# " + title + "\n\n" + body);
    }

    /** Returns all stored "path" field values from the index. */
    private List<String> readPaths(Path indexDir) throws IOException {
        List<String> paths = new ArrayList<>();
        try (var dir = new NIOFSDirectory(indexDir);
             var reader = DirectoryReader.open(dir)) {
            var sf = reader.storedFields();
            for (int i = 0; i < reader.maxDoc(); i++) {
                paths.add(sf.document(i).get("path"));
            }
        }
        return paths;
    }

    // ---- Path field -----------------------------------------------------

    @Nested
    @DisplayName("path field: numeric prefix stripping")
    class PathFieldPrefixStripping {

        @Test
        @DisplayName("numeric prefix stripped from path in dev mode")
        void numericPrefix_stripped_devMode() throws IOException {
            Path docsDir = createDocsDir("docs");
            writeDoc(docsDir, "010_intro/010_intro.md", "Intro", "Hello.");
            Path indexDir = tempDir.resolve("index");

            new SearchIndexer(docsDir, indexDir, "ja", false).index();

            List<String> paths = readPaths(indexDir);
            assertEquals(1, paths.size());
            assertEquals("/intro.html", paths.get(0),
                    "Numeric prefix must be stripped; path must be /intro.html in dev mode");
        }

        @Test
        @DisplayName("numeric prefix stripped from path in production mode")
        void numericPrefix_stripped_productionMode() throws IOException {
            Path docsDir = createDocsDir("docs");
            writeDoc(docsDir, "010_intro/010_intro.md", "Intro", "Hello.");
            Path indexDir = tempDir.resolve("index");

            new SearchIndexer(docsDir, indexDir, "ja", true).index();

            List<String> paths = readPaths(indexDir);
            assertEquals(1, paths.size());
            assertEquals("/intro/", paths.get(0),
                    "Production mode path must be /intro/ (clean URL)");
        }

        @Test
        @DisplayName("flat file: path is /page.html in dev mode")
        void flatFile_devMode() throws IOException {
            Path docsDir = createDocsDir("docs");
            writeDoc(docsDir, "page.md", "Page", "Content.");
            Path indexDir = tempDir.resolve("index");

            new SearchIndexer(docsDir, indexDir, "ja", false).index();

            List<String> paths = readPaths(indexDir);
            assertEquals(1, paths.size());
            assertEquals("/page.html", paths.get(0));
        }

        @Test
        @DisplayName("flat file: path is /page/ in production mode")
        void flatFile_productionMode() throws IOException {
            Path docsDir = createDocsDir("docs");
            writeDoc(docsDir, "page.md", "Page", "Content.");
            Path indexDir = tempDir.resolve("index");

            new SearchIndexer(docsDir, indexDir, "ja", true).index();

            List<String> paths = readPaths(indexDir);
            assertEquals(1, paths.size());
            assertEquals("/page/", paths.get(0));
        }
    }

    // ---- Locale prefix --------------------------------------------------

    @Nested
    @DisplayName("path field: locale prefix")
    class PathFieldLocalePrefix {

        @Test
        @DisplayName("English locale path prefixed with /en/ in dev mode")
        void enLocale_pathPrefixed_devMode() throws IOException {
            Path docsDir = createDocsDir("docs");
            writeDoc(docsDir, "intro.md", "Introduction", "Hello.");
            Path indexDir = tempDir.resolve("index");

            new SearchIndexer(docsDir, indexDir, "en", false).index();

            List<String> paths = readPaths(indexDir);
            assertEquals(1, paths.size());
            assertEquals("/en/intro.html", paths.get(0),
                    "English locale path must be prefixed with /en/");
        }

        @Test
        @DisplayName("English locale path prefixed with /en/ in production mode")
        void enLocale_pathPrefixed_productionMode() throws IOException {
            Path docsDir = createDocsDir("docs");
            writeDoc(docsDir, "intro.md", "Introduction", "Hello.");
            Path indexDir = tempDir.resolve("index");

            new SearchIndexer(docsDir, indexDir, "en", true).index();

            List<String> paths = readPaths(indexDir);
            assertEquals(1, paths.size());
            assertEquals("/en/intro/", paths.get(0),
                    "English locale production path must be /en/intro/");
        }

        @Test
        @DisplayName("Japanese locale path has no prefix")
        void jaLocale_noPrefix() throws IOException {
            Path docsDir = createDocsDir("docs");
            writeDoc(docsDir, "intro.md", "イントロ", "コンテンツ。");
            Path indexDir = tempDir.resolve("index");

            new SearchIndexer(docsDir, indexDir, "ja", false).index();

            List<String> paths = readPaths(indexDir);
            assertEquals(1, paths.size());
            assertEquals("/intro.html", paths.get(0),
                    "Japanese locale path must not have a locale prefix");
        }

        @Test
        @DisplayName("null locale (default) path has no prefix")
        void nullLocale_noPrefix() throws IOException {
            Path docsDir = createDocsDir("docs");
            writeDoc(docsDir, "intro.md", "Intro", "Content.");
            Path indexDir = tempDir.resolve("index");

            new SearchIndexer(docsDir, indexDir).index();

            List<String> paths = readPaths(indexDir);
            assertEquals(1, paths.size());
            assertFalse(paths.get(0).contains("/en/") || paths.get(0).startsWith("/ja/"),
                    "null locale must not add any locale prefix");
        }
    }

    // ---- Stored field values --------------------------------------------

    @Nested
    @DisplayName("stored field values: title and body")
    class StoredFieldValues {

        @Test
        @DisplayName("title field is stored from frontmatter")
        void titleField_storedFromFrontmatter() throws IOException {
            Path docsDir = createDocsDir("docs");
            writeDoc(docsDir, "page.md", "My Title", "Body text here.");
            Path indexDir = tempDir.resolve("index");

            new SearchIndexer(docsDir, indexDir, "ja", false).index();

            try (var dir = new NIOFSDirectory(indexDir);
                 var reader = DirectoryReader.open(dir)) {
                var sf = reader.storedFields();
                var doc = sf.document(0);
                assertEquals("My Title", doc.get("title"),
                        "title field must be stored from frontmatter");
            }
        }

        @Test
        @DisplayName("body field stores full text (used for snippet generation and MoreLikeThis)")
        void bodyField_storedFullText() throws IOException {
            Path docsDir = createDocsDir("docs");
            String longBody = "word ".repeat(100); // 500 chars
            writeDoc(docsDir, "page.md", "Title", longBody);
            Path indexDir = tempDir.resolve("index");

            new SearchIndexer(docsDir, indexDir, "ja", false).index();

            try (var dir = new NIOFSDirectory(indexDir);
                 var reader = DirectoryReader.open(dir)) {
                var sf = reader.storedFields();
                var doc = sf.document(0);
                String body = doc.get("body");
                assertNotNull(body, "body field must be stored");
                assertTrue(body.length() >= 490, "body must contain full text");
            }
        }
    }

    // ---- cleanRelPath utility -------------------------------------------

    @Nested
    @DisplayName("SiteBuilder.cleanRelPath utility")
    class CleanRelPathUtility {

        @Test
        @DisplayName("strips numeric prefix from each path segment")
        void stripsNumericPrefixAllSegments() {
            assertEquals("section/page",
                    SiteBuilder.cleanRelPath("010_section/020_page"),
                    "All segments must have numeric prefix stripped");
        }

        @Test
        @DisplayName("leaves segments without numeric prefix unchanged")
        void noPrefix_unchanged() {
            assertEquals("intro",
                    SiteBuilder.cleanRelPath("intro"),
                    "Segment without numeric prefix must be unchanged");
        }
    }
}
