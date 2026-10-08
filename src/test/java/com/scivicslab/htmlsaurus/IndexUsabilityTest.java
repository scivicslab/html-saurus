package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The portal rebuilds a project's search index when it cannot use the one on disk. Before, it
 * asked only whether {@code search-index/} existed, which is not the same question: a Lucene
 * major-version upgrade leaves every existing index in place but unreadable (Lucene 10 rejects a
 * Lucene 9 index with {@code IndexFormatTooOldException}), and an interrupted write leaves a
 * corrupt one. Both answered "present", so the portal kept them and every query threw.
 */
class IndexUsabilityTest {

    @TempDir
    Path tempDir;

    /** Builds a real index from one Markdown file and returns its directory. */
    private Path buildIndex(String name) throws IOException {
        Path docs = tempDir.resolve(name + "-docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("intro.md"),
                "---\ntitle: Introduction\nid: intro\n---\n\n# Introduction\n\nHello world.\n");
        Path index = tempDir.resolve(name + "-index");
        Main.reindex(docs, index, "ja", true);
        return index;
    }

    @Test
    @DisplayName("A freshly built index is usable")
    void freshIndexIsUsable() throws Exception {
        assertTrue(SearchIndexer.isUsableIndex(buildIndex("fresh")));
    }

    @Test
    @DisplayName("A missing directory is not usable")
    void missingDirectoryIsNotUsable() {
        assertFalse(SearchIndexer.isUsableIndex(tempDir.resolve("never-created")));
    }

    @Test
    @DisplayName("An empty directory is not usable")
    void emptyDirectoryIsNotUsable() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("empty"));
        assertFalse(SearchIndexer.isUsableIndex(dir),
                "the directory exists, which is exactly the case the presence check got wrong");
    }

    @Test
    @DisplayName("A directory whose segments file this Lucene cannot read is not usable")
    void unreadableSegmentsIsNotUsable() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("foreign"));
        // Stands in for an index written by an older Lucene: the directory and its segments file
        // are there, but this build cannot decode them.
        Files.write(dir.resolve("segments_1"), new byte[] {0x3f, (byte) 0xd7, 0x6c, 0x17, 0x00});
        assertFalse(SearchIndexer.isUsableIndex(dir));
    }

    @Test
    @DisplayName("A truncated segments file makes the index unusable")
    void truncatedIndexIsNotUsable() throws Exception {
        Path dir = buildIndex("truncated");
        try (var entries = Files.list(dir)) {
            Path segments = entries.filter(p -> p.getFileName().toString().startsWith("segments"))
                    .findFirst().orElseThrow();
            Files.write(segments, new byte[0]);
        }
        assertFalse(SearchIndexer.isUsableIndex(dir));
    }

    @Test
    @DisplayName("A broken locale index makes the whole project's index unusable")
    void brokenLocaleIndexIsNotUsable() throws Exception {
        Path dir = buildIndex("with-locale");
        Path en = Files.createDirectories(dir.resolve("en"));
        Files.write(en.resolve("segments_1"), new byte[] {0x00});
        assertFalse(SearchIndexer.isUsableIndex(dir),
                "search-index/en/ is served as its own searcher, so it has to be checked too");
    }

    @Test
    @DisplayName("A sound locale index leaves the project's index usable")
    void soundLocaleIndexStaysUsable() throws Exception {
        Path dir = buildIndex("ja-and-en");
        Path enDocs = tempDir.resolve("en-docs");
        Files.createDirectories(enDocs);
        Files.writeString(enDocs.resolve("intro.md"),
                "---\ntitle: Introduction\nid: intro\n---\n\n# Introduction\n\nHello world.\n");
        Main.reindex(enDocs, dir.resolve("en"), "en", true);
        assertTrue(SearchIndexer.isUsableIndex(dir));
    }
}
