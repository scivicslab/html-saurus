package com.scivicslab.htmlsaurus;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Stage A of the embedding-based related-docs feature: builds, per project, the
 * document-vector cache used at serve time by {@link SemanticIndex}.
 *
 * <p>Mirrors the per-project layout of the Lucene {@code search-index/}: each
 * project gets its own {@code search-embedding/vectors.bin} holding one pooled,
 * L2-normalized vector per document. The neural network (multilingual-e5-large)
 * is NOT run in-process — each document's text is sent over HTTP to the shared
 * embedding server (see {@link EmbeddingClient}). Each document is split into
 * ~512-token chunks, every chunk is embedded, and the chunk vectors are
 * mean-pooled into one document vector.
 *
 * <p>The expensive embedding work is cached per project and only redone when a
 * project's vectors are missing or older than its {@code search-index/} (the
 * same "rebuild if stale" rule the search index itself follows). Cross-project
 * nearest-neighbour computation is cheap (cosine only) and is done in memory at
 * serve time by {@link SemanticIndex}, so there is no global, easily-stale
 * neighbours file.
 */
final class SemanticIndexer {

    private static final Logger logger = Logger.getLogger(SemanticIndexer.class.getName());

    /** Approximate characters per chunk; ~512 e5 tokens for mixed JA/EN text. */
    static final int CHUNK_CHARS = 1000;
    /** Length of the stored summary snippet shown under each related result. */
    static final int SUMMARY_CHARS = 250;

    /** Sub-directory (under a project dir) holding the vector cache. */
    static final String EMBED_DIR = "search-embedding";
    /** Vector cache file name within {@link #EMBED_DIR}. */
    static final String VECTORS_FILE = "vectors.bin";
    private static final int MAGIC = 0x53454d56;   // "SEMV"
    private static final int VERSION = 2;   // bumped: vectors now carry docId + srcPath

    private SemanticIndexer() {}

    /** One document with its (project-relative) path, title, locale, summary, and pooled vector. */
    record DocVec(String path, String title, String locale, String summary, String docId, String srcPath, float[] vec) {}

    /**
     * Ensures every project's {@code search-embedding/vectors.bin} is present and
     * not older than its {@code search-index/}; (re)builds the stale ones.
     *
     * @param projectDirs Docusaurus project root directories
     * @param embed       client for the shared embedding server
     */
    static void ensureVectors(List<Path> projectDirs, EmbeddingClient embed) {
        for (Path projectDir : projectDirs) {
            try {
                if (isStale(projectDir)) {
                    buildProjectVectors(projectDir, embed);
                }
            } catch (Exception e) {
                logger.warning("SemanticIndexer: vector build failed for "
                        + projectDir.getFileName() + ": " + e.getMessage());
            }
        }
    }

    /** True if the vector cache is missing or older than the project's search index. */
    private static boolean isStale(Path projectDir) {
        Path vectors = projectDir.resolve(EMBED_DIR).resolve(VECTORS_FILE);
        Path searchIndex = projectDir.resolve("search-index");
        if (!Files.exists(vectors)) {
            return Files.isDirectory(searchIndex);   // need building only if there is an index
        }
        try {
            long vecTime = Files.getLastModifiedTime(vectors).toMillis();
            return newestMTime(searchIndex) > vecTime;
        } catch (IOException e) {
            return true;
        }
    }

    /** Newest last-modified time (ms) of any file under {@code dir}, or 0 if none. */
    private static long newestMTime(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0; }
                    })
                    .max().orElse(0);
        } catch (IOException e) {
            return Long.MAX_VALUE;   // unknown -> treat as newer (force rebuild)
        }
    }

    /** Embeds all documents of one project and writes its {@code vectors.bin}. */
    private static void buildProjectVectors(Path projectDir, EmbeddingClient embed) throws IOException {
        List<RawDoc> raw = new ArrayList<>();
        collectProject(projectDir, raw);
        if (raw.isEmpty()) {
            return;   // nothing indexed yet for this project
        }
        List<DocVec> docs = new ArrayList<>(raw.size());
        int failed = 0;
        for (RawDoc d : raw) {
            float[] vec = embedDocument(d.body, embed);
            if (vec == null) {
                failed++;
                continue;
            }
            // Prefer the authored frontmatter description as the displayed summary; fall back to a
            // body snippet when there is none.
            String summary = (d.description() != null && !d.description().isBlank())
                    ? d.description() : summarize(d.body);
            docs.add(new DocVec(d.path, d.title, d.locale, summary, d.docId(), d.srcPath(), vec));
        }
        if (docs.isEmpty()) {
            // Every embedding failed (likely the server is down): do NOT write an empty
            // file, so the cache stays "stale" and is retried on the next run.
            logger.warning("SemanticIndexer: " + projectDir.getFileName()
                    + " produced no vectors (all " + raw.size() + " embeddings failed); not written");
            return;
        }
        writeVectors(projectDir.resolve(EMBED_DIR).resolve(VECTORS_FILE), docs);
        logger.info("SemanticIndexer: " + projectDir.getFileName() + " wrote " + docs.size()
                + " vectors" + (failed > 0 ? " (" + failed + " failed)" : ""));
    }

    /** Source fields read out of one Lucene document. */
    private record RawDoc(String path, String title, String body, String locale, String description,
                          String docId, String srcPath) {}

    /** Reads every locale index of one project and appends its documents to {@code out}. */
    private static void collectProject(Path projectDir, List<RawDoc> out) throws IOException {
        String[] i18n = Main.readI18nConfig(projectDir);
        String defaultLocale = (i18n.length > 0 && !i18n[0].isBlank()) ? i18n[0] : "ja";
        Path baseIndex = projectDir.resolve("search-index");
        if (!Files.isDirectory(baseIndex)) {
            return;
        }
        collectIndex(baseIndex, defaultLocale, out);
        try (var stream = Files.list(baseIndex)) {
            for (Path sub : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(sub)) {
                    collectIndex(sub, sub.getFileName().toString(), out);
                }
            }
        }
    }

    /** Reads stored path/title/body from one Lucene index directory. */
    private static void collectIndex(Path indexDir, String locale, List<RawDoc> out) {
        if (!Files.isDirectory(indexDir)) {
            return;
        }
        try (org.apache.lucene.store.Directory dir = new NIOFSDirectory(indexDir)) {
            if (!DirectoryReader.indexExists(dir)) {
                return;
            }
            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                StoredFields stored = reader.storedFields();
                for (int i = 0; i < reader.maxDoc(); i++) {
                    var doc = stored.document(i);
                    String path = doc.get("path");
                    String body = doc.get("body");
                    if (path == null || path.isBlank() || body == null || body.isBlank()) {
                        continue;
                    }
                    String title = doc.get("title") != null ? doc.get("title") : "";
                    String description = doc.get("description") != null ? doc.get("description") : "";
                    String docId = doc.get("doc_id") != null ? doc.get("doc_id") : "";
                    String srcPath = doc.get("src_path") != null ? doc.get("src_path") : "";
                    out.add(new RawDoc(path, title, body, locale, description, docId, srcPath));
                }
            }
        } catch (IOException e) {
            logger.warning("SemanticIndexer: cannot read index " + indexDir + ": " + e.getMessage());
        }
    }

    /**
     * Embeds one document: split its text into chunks, embed all chunks in one
     * request, and mean-pool the chunk vectors into a single L2-normalized vector.
     *
     * @return the pooled document vector, or {@code null} if embedding failed
     */
    private static float[] embedDocument(String body, EmbeddingClient embed) {
        List<String> chunks = chunk(body);
        if (chunks.isEmpty()) {
            return null;
        }
        List<float[]> vecs = embed.embedBatch(chunks);
        if (vecs == null || vecs.isEmpty()) {
            return null;
        }
        return meanPool(vecs);
    }

    /** Splits text into ~{@link #CHUNK_CHARS}-character chunks on whitespace boundaries. */
    static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String t = text.strip();
        int n = t.length();
        int start = 0;
        while (start < n) {
            int end = Math.min(start + CHUNK_CHARS, n);
            if (end < n) {
                int ws = t.lastIndexOf(' ', end);
                if (ws > start + CHUNK_CHARS / 2) {
                    end = ws;
                }
            }
            String piece = t.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            start = end;
        }
        return chunks;
    }

    /** Mean-pools L2-normalized chunk vectors and re-normalizes to unit length. */
    static float[] meanPool(List<float[]> vecs) {
        if (vecs == null || vecs.isEmpty()) {
            return null;
        }
        int dim = vecs.get(0).length;
        double[] acc = new double[dim];
        for (float[] v : vecs) {
            if (v.length != dim) {
                return null;
            }
            for (int i = 0; i < dim; i++) {
                acc[i] += v[i];
            }
        }
        double norm = 0;
        for (int i = 0; i < dim; i++) {
            acc[i] /= vecs.size();
            norm += acc[i] * acc[i];
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            norm = 1;
        }
        float[] out = new float[dim];
        for (int i = 0; i < dim; i++) {
            out[i] = (float) (acc[i] / norm);
        }
        return out;
    }

    /** First {@link #SUMMARY_CHARS} characters of the body, ellipsized. */
    private static String summarize(String body) {
        String b = body.strip();
        return b.length() > SUMMARY_CHARS ? b.substring(0, SUMMARY_CHARS) + "…" : b;
    }

    // ---- Binary vector cache I/O --------------------------------

    /** Writes the document vectors of one project to {@code file} (creating parents). */
    static void writeVectors(Path file, List<DocVec> docs) throws IOException {
        Files.createDirectories(file.getParent());
        int dim = docs.isEmpty() ? 0 : docs.get(0).vec().length;
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(dim);
            out.writeInt(docs.size());
            for (DocVec d : docs) {
                out.writeUTF(d.path());
                out.writeUTF(d.title());
                out.writeUTF(d.locale());
                out.writeUTF(d.summary());
                out.writeUTF(d.docId());
                out.writeUTF(d.srcPath());
                for (float f : d.vec()) {
                    out.writeFloat(f);
                }
            }
        }
    }

    /**
     * Reads a project's vector cache, or returns {@code null} if the file is
     * missing or not a valid cache (wrong magic/version).
     */
    static List<DocVec> readVectors(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                logger.warning("SemanticIndexer: bad vector cache header in " + file);
                return null;
            }
            int dim = in.readInt();
            int count = in.readInt();
            List<DocVec> docs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String path = in.readUTF();
                String title = in.readUTF();
                String locale = in.readUTF();
                String summary = in.readUTF();
                String docId = in.readUTF();
                String srcPath = in.readUTF();
                float[] v = new float[dim];
                for (int j = 0; j < dim; j++) {
                    v[j] = in.readFloat();
                }
                docs.add(new DocVec(path, title, locale, summary, docId, srcPath, v));
            }
            return docs;
        } catch (IOException e) {
            logger.warning("SemanticIndexer: cannot read vector cache " + file + ": " + e.getMessage());
            return null;
        }
    }
}
