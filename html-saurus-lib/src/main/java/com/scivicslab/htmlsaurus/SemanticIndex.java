package com.scivicslab.htmlsaurus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/**
 * Stage B of the embedding-based related-docs feature: the in-memory,
 * serve-time view. Loads every project's per-project vector cache (written by
 * {@link SemanticIndexer}) and computes each document's top-K nearest
 * neighbours by cosine similarity within the same locale.
 *
 * <p>Neighbour computation needs all documents' vectors together (a document's
 * nearest neighbour may live in another project), but it is cheap — cosine only,
 * no embedding calls — so it runs in memory at startup (parallelized across
 * documents) rather than from a persisted, easily-stale global file.
 *
 * <p>Documents keep their project-relative {@code path}; each server turns a
 * {@code (projectName, path)} pair into the URL it actually serves via the
 * {@code urlFn} passed to {@link #servedMap}. Portal mode prefixes the project
 * name; single-project mode does not.
 */
final class SemanticIndex {

    private static final Logger logger = Logger.getLogger(SemanticIndex.class.getName());

    /** A document plus the project it belongs to. */
    private record Entry(String projectName, SemanticIndexer.DocVec doc) {}

    private final List<Entry> entries;
    /** For each entry index, the indices of its top-K neighbours (best first). */
    private final int[][] neighbours;

    private SemanticIndex(List<Entry> entries, int[][] neighbours) {
        this.entries = entries;
        this.neighbours = neighbours;
    }

    /**
     * Loads all projects' vector caches and computes neighbours in memory.
     *
     * @param projectDirs project root directories to load vectors from
     * @param topK        number of neighbours to keep per document
     * @return a ready-to-serve index (possibly empty if no vectors exist)
     */
    static SemanticIndex load(List<Path> projectDirs, int topK) {
        long t0 = System.currentTimeMillis();
        List<Entry> entries = new ArrayList<>();
        for (Path projectDir : projectDirs) {
            Path file = projectDir.resolve(SemanticIndexer.EMBED_DIR).resolve(SemanticIndexer.VECTORS_FILE);
            List<SemanticIndexer.DocVec> docs = SemanticIndexer.readVectors(file);
            if (docs == null) {
                continue;
            }
            String projectName = projectDir.getFileName().toString();
            for (SemanticIndexer.DocVec d : docs) {
                entries.add(new Entry(projectName, d));
            }
        }
        int[][] neighbours = computeNeighbours(entries, topK);
        long ms = System.currentTimeMillis() - t0;
        logger.info("SemanticIndex: loaded " + entries.size() + " document vectors from "
                + projectDirs.size() + " project(s), neighbours computed in " + ms + "ms");
        return new SemanticIndex(entries, neighbours);
    }

    /** Computes top-K within-locale neighbours for every entry, in parallel. */
    private static int[][] computeNeighbours(List<Entry> entries, int topK) {
        int n = entries.size();
        int[][] result = new int[n][];
        if (n == 0) {
            return result;
        }
        IntStream.range(0, n).parallel().forEach(i -> {
            Entry ei = entries.get(i);
            float[] vi = ei.doc.vec();
            String localeI = ei.doc.locale();
            // Bounded top-K by score (parallel arrays kept sorted descending).
            double[] bestScore = new double[topK];
            int[] bestIdx = new int[topK];
            int size = 0;
            for (int j = 0; j < n; j++) {
                if (j == i) {
                    continue;
                }
                Entry ej = entries.get(j);
                if (!localeI.equals(ej.doc.locale())) {
                    continue;
                }
                double score = EmbeddingClient.cosine(vi, ej.doc.vec());
                if (size >= topK && score <= bestScore[size - 1]) {
                    continue;
                }
                int pos = size < topK ? size : size - 1;
                while (pos > 0 && bestScore[pos - 1] < score) {
                    bestScore[pos] = bestScore[pos - 1];
                    bestIdx[pos] = bestIdx[pos - 1];
                    pos--;
                }
                bestScore[pos] = score;
                bestIdx[pos] = j;
                if (size < topK) {
                    size++;
                }
            }
            result[i] = java.util.Arrays.copyOf(bestIdx, size);
        });
        return result;
    }

    /**
     * Builds the served map {@code servedPath -> [{path,title,summary}, ...]} using
     * the given URL renderer. Computed once by each server at startup.
     *
     * @param urlFn maps {@code (projectName, project-relative path)} to a served URL
     * @return ordered map keyed by each document's served URL
     */
    Map<String, List<Map<String, String>>> servedMap(BiFunction<String, String, String> urlFn) {
        Map<String, List<Map<String, String>>> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry ei = entries.get(i);
            String key = urlFn.apply(ei.projectName, ei.doc.path());
            List<Map<String, String>> hits = new ArrayList<>();
            for (int j : neighbours[i]) {
                Entry ej = entries.get(j);
                hits.add(Map.of(
                        "id", ej.doc.docId(),
                        "path", urlFn.apply(ej.projectName, ej.doc.path()),
                        "srcPath", ej.doc.srcPath(),
                        "title", ej.doc.title(),
                        "summary", ej.doc.summary()));
            }
            map.put(key, hits);
        }
        return map;
    }

    /**
     * Query-to-document semantic search (RAG-style retrieval): ranks all loaded
     * documents by cosine similarity to {@code queryVec} and returns the top
     * {@code topN}. Searches across all locales (the embedding model is
     * multilingual). Returns hits as {@code {path,title,summary}} maps with URLs
     * in the caller's scheme.
     *
     * @param queryVec the L2-normalized query embedding
     * @param topN     maximum number of results
     * @param urlFn    maps {@code (projectName, project-relative path)} to a served URL
     * @return ranked hit list (empty if the query vector is null or no documents exist)
     */
    List<Map<String, String>> search(float[] queryVec, int topN,
                                     BiFunction<String, String, String> urlFn) {
        if (queryVec == null || entries.isEmpty() || topN <= 0) {
            return List.of();
        }
        record Scored(int idx, double score) {}
        List<Scored> scored = new ArrayList<>(entries.size());
        for (int j = 0; j < entries.size(); j++) {
            scored.add(new Scored(j, EmbeddingClient.cosine(queryVec, entries.get(j).doc().vec())));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Map<String, String>> hits = new ArrayList<>(Math.min(topN, scored.size()));
        for (int k = 0; k < Math.min(topN, scored.size()); k++) {
            Entry e = entries.get(scored.get(k).idx());
            hits.add(Map.of(
                    "id", e.doc().docId(),
                    "path", urlFn.apply(e.projectName(), e.doc().path()),
                    "srcPath", e.doc().srcPath(),
                    "title", e.doc().title(),
                    "summary", e.doc().summary()));
        }
        return hits;
    }

    /** Number of documents loaded (for logging/diagnostics). */
    int size() {
        return entries.size();
    }
}
