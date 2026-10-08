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

    @Test
    void theIndexedPathIsThePageThatWasWritten() throws IOException {
        for (boolean production : new boolean[] {false, true}) {
            Path proj = createProject();
            Path dir = proj.resolve("docs/Books/Chapter08");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("TheLispReader.md"),
                    "---\nid: TheLispReader\ntitle: The Lisp Reader\n---\n\nreadtable macro characters\n");

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), production);
            String path = indexedPath(proj, production);

            assertEquals("/Books/Chapter08/TheLispReader/", path,
                    "production=" + production + ": the stored path");
            Path onDisk = proj.resolve("static-html" + path + "index.html");
            assertTrue(Files.isRegularFile(onDisk),
                    "production=" + production + ": " + path + " must lead to a page, found none at " + onDisk);
        }
    }
}
