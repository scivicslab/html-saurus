package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure (no network) helpers of {@link SemanticIndexer}:
 * chunking and mean-pooling. Reading Lucene indexes and calling the embedding
 * server are integration concerns and are not exercised here.
 */
class SemanticIndexerTest {

    @Test
    void chunk_shortText_returnsSingleChunk() {
        List<String> chunks = SemanticIndexer.chunk("a short paragraph");
        assertEquals(1, chunks.size());
        assertEquals("a short paragraph", chunks.get(0));
    }

    @Test
    void chunk_longText_splitsIntoChunksUnderLimit() {
        // 2500 space-separated tokens, well over CHUNK_CHARS.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) sb.append("word ");
        List<String> chunks = SemanticIndexer.chunk(sb.toString());
        assertTrue(chunks.size() >= 2, "expected multiple chunks");
        for (String c : chunks) {
            assertTrue(c.length() <= SemanticIndexer.CHUNK_CHARS,
                    "chunk longer than CHUNK_CHARS: " + c.length());
        }
    }

    @Test
    void chunk_blankText_returnsEmpty() {
        assertTrue(SemanticIndexer.chunk("   ").isEmpty());
        assertTrue(SemanticIndexer.chunk(null).isEmpty());
    }

    @Test
    void meanPool_unitVectors_producesUnitLengthVector() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};
        float[] pooled = SemanticIndexer.meanPool(List.of(a, b));
        assertNotNull(pooled);
        double norm = Math.sqrt(pooled[0] * pooled[0] + pooled[1] * pooled[1]);
        assertEquals(1.0, norm, 1e-6);
        // Symmetric inputs -> equal components.
        assertEquals(pooled[0], pooled[1], 1e-6);
    }

    @Test
    void meanPool_inconsistentDimensions_returnsNull() {
        assertNull(SemanticIndexer.meanPool(List.of(new float[]{1f, 0f}, new float[]{1f})));
    }

    @Test
    void writeReadVectors_roundTrip_preservesAllFields(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(SemanticIndexer.EMBED_DIR).resolve(SemanticIndexer.VECTORS_FILE);
        var d1 = new SemanticIndexer.DocVec("/a.html", "Title A", "ja", "summary a", "idA", "/src/a.md",
                new float[]{0.1f, 0.2f, 0.3f});
        var d2 = new SemanticIndexer.DocVec("/b.html", "タイトルB", "en", "概要 b", "idB", "/src/b.md",
                new float[]{0.4f, 0.5f, 0.6f});
        SemanticIndexer.writeVectors(file, List.of(d1, d2));

        List<SemanticIndexer.DocVec> back = SemanticIndexer.readVectors(file);
        assertNotNull(back);
        assertEquals(2, back.size());
        assertEquals("/a.html", back.get(0).path());
        assertEquals("タイトルB", back.get(1).title());
        assertEquals("en", back.get(1).locale());
        assertEquals("概要 b", back.get(1).summary());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, back.get(0).vec(), 0f);
    }

    @Test
    void readVectors_missingFile_returnsNull() {
        assertNull(SemanticIndexer.readVectors(Path.of("/no/such/vectors.bin")));
    }
}
