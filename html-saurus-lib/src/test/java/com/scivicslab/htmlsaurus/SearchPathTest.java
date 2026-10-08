package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The path a search hit carries leads to the page that was written.
 *
 * <p>The indexer kept its own copy of the rule that turns a Markdown path into a URL, and that copy
 * still appended {@code .html} outside production after the page writer had moved to one layout for
 * every mode. A search for a word in such a page offered a link that answered 404.
 */
@Tag("S1")
class SearchPathTest {

    @TempDir
    Path tempDir;

    private Path createProject() throws IOException {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve("docs"));
        Files.writeString(projectDir.resolve("docusaurus.config.js"), "module.exports = {};");
        return projectDir;
    }

    /** @return the single path the index stores */
    private String indexedPath(Path proj, boolean production) throws IOException {
        Path index = proj.resolve("search-index");
        new SearchIndexer(proj.resolve("docs"), index, "ja", production).index();
        try (var dir = new org.apache.lucene.store.NIOFSDirectory(index);
             var reader = org.apache.lucene.index.DirectoryReader.open(dir)) {
            assertEquals(1, reader.maxDoc(), "one page, one entry");
            return reader.storedFields().document(0).get("path");
        }
    }

    /**
     * The default locale is written at the root, whatever it is. The indexer asked whether the
     * locale was Japanese instead of whether it was the project's default, so every page of a
     * project whose default is English was indexed under {@code /en/} while its pages sit at the
     * root. Every hit in such a project led to a 404.
     */
    @Test
    void theDefaultLocaleIsNotPrefixedEvenWhenItIsNotJapanese() throws IOException {
        Path proj = createProject();
        Files.writeString(proj.resolve("docusaurus.config.js"),
                "module.exports = { i18n: { defaultLocale: 'en', locales: ['en'] } };");
        Path dir = proj.resolve("docs/ai-tools");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mcp-gateway.md"),
                "---\ntitle: MCP Gateway\n---\n\nemacsclient bridge\n");

        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        BuildStages.reindexAll(proj, false);

        try (var d = new org.apache.lucene.store.NIOFSDirectory(proj.resolve("search-index"));
             var reader = org.apache.lucene.index.DirectoryReader.open(d)) {
            assertEquals(1, reader.maxDoc());
            String path = reader.storedFields().document(0).get("path");
            assertEquals("/ai-tools/mcp-gateway/", path, "the default locale is not prefixed");
            assertTrue(Files.isRegularFile(proj.resolve("static-html" + path + "index.html")),
                    path + " must lead to a page");
        }
    }

    @Test
    void theIndexedPathIsThePageThatWasWritten() throws IOException {
        for (boolean production : new boolean[] {false, true}) {
            Path proj = createProject();
            Path dir = proj.resolve("docs/Books/Chapter08");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("TheLispReader.md"),
                    "---\nid: TheLispReader\ntitle: The Lisp Reader\n---\n\nreadtable macro characters\n");

            BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), production);
            String path = indexedPath(proj, production);

            assertEquals("/Books/Chapter08/TheLispReader/", path,
                    "production=" + production + ": the stored path");
            Path onDisk = proj.resolve("static-html" + path + "index.html");
            assertTrue(Files.isRegularFile(onDisk),
                    "production=" + production + ": " + path + " must lead to a page, found none at " + onDisk);
        }
    }
}
