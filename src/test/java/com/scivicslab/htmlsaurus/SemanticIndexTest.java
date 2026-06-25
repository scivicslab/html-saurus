package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SemanticIndex} query-to-doc search and neighbour serving.
 * Vectors are written with {@link SemanticIndexer#writeVectors} to a temp project
 * and loaded back — no embedding server is involved.
 */
class SemanticIndexTest {

    /** Writes a 3-document vector cache (orthonormal vectors) into {@code root/doc_test}. */
    private static Path buildProject(Path root) throws IOException {
        Path proj = root.resolve("doc_test");
        Path vfile = proj.resolve(SemanticIndexer.EMBED_DIR).resolve(SemanticIndexer.VECTORS_FILE);
        var a = new SemanticIndexer.DocVec("/a.html", "A", "ja", "sa", "idA", "/src/a.md", new float[]{1f, 0f, 0f});
        var b = new SemanticIndexer.DocVec("/b.html", "B", "ja", "sb", "idB", "/src/b.md", new float[]{0f, 1f, 0f});
        var c = new SemanticIndexer.DocVec("/c.html", "C", "ja", "sc", "idC", "/src/c.md", new float[]{0f, 0f, 1f});
        SemanticIndexer.writeVectors(vfile, List.of(a, b, c));
        return proj;
    }

    @Test
    void search_ranksDocumentsByCosineToQuery(@TempDir Path root) throws IOException {
        Path proj = buildProject(root);
        SemanticIndex idx = SemanticIndex.load(List.of(proj), 5);
        assertEquals(3, idx.size());

        // Query closest to A, then B.
        List<Map<String, String>> hits = idx.search(new float[]{0.9f, 0.1f, 0f}, 2,
                (projectName, path) -> path);
        assertEquals(2, hits.size());
        assertEquals("/a.html", hits.get(0).get("path"));
        assertEquals("/b.html", hits.get(1).get("path"));
    }

    @Test
    void search_nullQueryVector_returnsEmpty(@TempDir Path root) throws IOException {
        SemanticIndex idx = SemanticIndex.load(List.of(buildProject(root)), 5);
        assertTrue(idx.search(null, 5, (projectName, path) -> path).isEmpty());
    }

    @Test
    void servedMap_keysUseTheGivenUrlScheme(@TempDir Path root) throws IOException {
        Path proj = buildProject(root);
        SemanticIndex idx = SemanticIndex.load(List.of(proj), 2);
        // Portal scheme: project-prefixed.
        var portal = idx.servedMap((projectName, path) -> "/" + projectName + path);
        assertTrue(portal.containsKey("/doc_test/a.html"));
        // Single scheme: bare path.
        var single = idx.servedMap((projectName, path) -> path);
        assertTrue(single.containsKey("/a.html"));
        // Each document has neighbours (the other two, capped at topK=2).
        assertEquals(2, single.get("/a.html").size());
    }
}
