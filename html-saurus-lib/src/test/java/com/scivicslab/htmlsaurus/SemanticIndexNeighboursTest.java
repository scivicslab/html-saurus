package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the neighbour lists {@link SemanticIndex#load} computes, against a serial reference written in this test, so a change
 * in how the work is spread over threads cannot change which neighbours a document gets or in what order.
 */
class SemanticIndexNeighboursTest {

    private static final int DOCS = 240;
    private static final int TOP_K = 5;
    private static final String[] LOCALES = {"ja", "en", "fr"};

    private static float[] unit(Random rnd, int dim) {
        float[] v = new float[dim];
        double norm = 0;
        for (int i = 0; i < dim; i++) {
            v[i] = (float) rnd.nextGaussian();
            norm += v[i] * v[i];
        }
        float inv = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < dim; i++) {
            v[i] *= inv;
        }
        return v;
    }

    private static List<SemanticIndexer.DocVec> corpus() {
        Random rnd = new Random(20261128L);
        List<SemanticIndexer.DocVec> docs = new ArrayList<>();
        for (int i = 0; i < DOCS; i++) {
            // Every 10th document repeats the vector of the one before it, so equal scores exist and their order is pinned too.
            float[] vec = (i % 10 == 9) ? docs.get(i - 1).vec() : unit(rnd, 16);
            docs.add(new SemanticIndexer.DocVec("/d" + i + ".html", "T" + i, LOCALES[i % LOCALES.length],
                    "s" + i, "id" + i, "/src/d" + i + ".md", vec));
        }
        return docs;
    }

    /** The neighbours of {@code i}: same locale, not itself, best cosine first, the earlier document first on a tie. */
    private static List<String> referenceNeighbours(List<SemanticIndexer.DocVec> docs, int i) {
        List<Integer> candidates = new ArrayList<>();
        for (int j = 0; j < docs.size(); j++) {
            if (j != i && docs.get(j).locale().equals(docs.get(i).locale())) {
                candidates.add(j);
            }
        }
        candidates.sort(Comparator.comparingDouble(
                (Integer j) -> EmbeddingClient.cosine(docs.get(i).vec(), docs.get(j).vec())).reversed());
        return candidates.stream().limit(TOP_K).map(j -> docs.get(j).docId()).toList();
    }

    @Test
    void everyDocumentGetsTheSameNeighboursAsTheSerialReference(@TempDir Path root) throws IOException {
        List<SemanticIndexer.DocVec> docs = corpus();
        Path proj = root.resolve("doc_n");
        SemanticIndexer.writeVectors(proj.resolve(SemanticIndexer.EMBED_DIR).resolve(SemanticIndexer.VECTORS_FILE), docs);

        SemanticIndex idx = SemanticIndex.load(List.of(proj), TOP_K);
        assertEquals(DOCS, idx.size());
        Map<String, List<Map<String, String>>> served = idx.servedMap((project, path) -> path);

        for (int i = 0; i < DOCS; i++) {
            List<String> got = served.get(docs.get(i).path()).stream().map(h -> h.get("id")).toList();
            assertEquals(referenceNeighbours(docs, i), got, "neighbours of " + docs.get(i).docId());
        }
    }

    @Test
    void neighboursStayInTheDocumentsOwnLocaleAndNeverIncludeTheDocument(@TempDir Path root) throws IOException {
        List<SemanticIndexer.DocVec> docs = corpus();
        Path proj = root.resolve("doc_n");
        SemanticIndexer.writeVectors(proj.resolve(SemanticIndexer.EMBED_DIR).resolve(SemanticIndexer.VECTORS_FILE), docs);
        Map<String, List<Map<String, String>>> served =
                SemanticIndex.load(List.of(proj), TOP_K).servedMap((project, path) -> path);

        for (SemanticIndexer.DocVec d : docs) {
            List<Map<String, String>> hits = served.get(d.path());
            assertEquals(TOP_K, hits.size());
            for (Map<String, String> h : hits) {
                assertFalse(h.get("id").equals(d.docId()), "a document is not its own neighbour");
                int j = Integer.parseInt(h.get("id").substring(2));
                assertEquals(d.locale(), docs.get(j).locale());
            }
        }
    }

    @Test
    void aLocaleWithFewerDocumentsThanTopKListsAllOfThem(@TempDir Path root) throws IOException {
        Path proj = root.resolve("doc_few");
        var a = new SemanticIndexer.DocVec("/a.html", "A", "ja", "sa", "idA", "/a.md", new float[]{1f, 0f});
        var b = new SemanticIndexer.DocVec("/b.html", "B", "ja", "sb", "idB", "/b.md", new float[]{0.6f, 0.8f});
        var c = new SemanticIndexer.DocVec("/c.html", "C", "en", "sc", "idC", "/c.md", new float[]{0f, 1f});
        SemanticIndexer.writeVectors(proj.resolve(SemanticIndexer.EMBED_DIR).resolve(SemanticIndexer.VECTORS_FILE),
                List.of(a, b, c));
        Map<String, List<Map<String, String>>> served =
                SemanticIndex.load(List.of(proj), TOP_K).servedMap((project, path) -> path);

        assertEquals(List.of("idB"), served.get("/a.html").stream().map(h -> h.get("id")).toList());
        assertEquals(List.of("idA"), served.get("/b.html").stream().map(h -> h.get("id")).toList());
        assertTrue(served.get("/c.html").isEmpty(), "the only English document has no neighbour");
    }

    @Test
    void anEmptyCorpusLoadsWithoutNeighbours(@TempDir Path root) {
        SemanticIndex idx = SemanticIndex.load(List.of(root.resolve("doc_none")), TOP_K);
        assertEquals(0, idx.size());
        assertTrue(idx.servedMap((project, path) -> path).isEmpty());
    }
}
