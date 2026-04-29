package com.scivicslab.htmlsaurus;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Long-lived Lucene search helper that keeps the DirectoryReader open between requests.
 *
 * <p>Uses {@link DirectoryReader#openIfChanged} to cheaply pick up index updates without
 * reopening the underlying directory on every query. The analyzer is created once and reused.
 * All public methods are {@code synchronized} so the instance is safe to share across threads.
 */
class LuceneSearcher implements Closeable {

    record Hit(String title, String path, String summary) {}

    private final Path indexDir;
    private final Analyzer analyzer = new PerFieldAnalyzerWrapper(
            new JapaneseAnalyzer(),
            Map.of("doc_id_idx", SearchIndexer.underscoreAnalyzer(),
                   "path_tokens", SearchIndexer.underscoreAnalyzer()));
    private NIOFSDirectory directory;
    private DirectoryReader reader;

    LuceneSearcher(Path indexDir) {
        this.indexDir = indexDir;
    }

    /**
     * Searches the index and returns up to {@code maxHits} results.
     * Returns an empty list if the query is blank or the index does not yet exist.
     */
    synchronized List<Hit> search(String queryStr, int maxHits,
                                  String[] fields, Map<String, Float> boosts) throws Exception {
        if (queryStr.isBlank()) return List.of();
        ensureOpen();
        refreshReader();

        var searcher = new IndexSearcher(reader);
        var parser = new MultiFieldQueryParser(fields, analyzer, boosts);
        parser.setDefaultOperator(MultiFieldQueryParser.AND_OPERATOR);
        var q = parser.parse(MultiFieldQueryParser.escape(queryStr));
        var hits = searcher.search(q, maxHits);
        var stored = searcher.storedFields();

        List<Hit> results = new ArrayList<>();
        for (var hit : hits.scoreDocs) {
            var doc = stored.document(hit.doc);
            results.add(new Hit(
                doc.get("title")   != null ? doc.get("title")   : "",
                doc.get("path")    != null ? doc.get("path")    : "",
                doc.get("summary") != null ? doc.get("summary") : ""
            ));
        }
        return results;
    }

    /** Returns {@code true} if the index directory exists and can be opened. */
    synchronized boolean isAvailable() {
        try {
            ensureOpen();
            return reader != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        try { if (directory != null) directory.close(); } catch (IOException ignored) {}
        analyzer.close();
    }

    private void ensureOpen() throws IOException {
        if (reader == null) {
            directory = new NIOFSDirectory(indexDir);
            reader = DirectoryReader.open(directory);
        }
    }

    private void refreshReader() throws IOException {
        DirectoryReader newReader = DirectoryReader.openIfChanged(reader);
        if (newReader != null) {
            reader.close();
            reader = newReader;
        }
    }
}
