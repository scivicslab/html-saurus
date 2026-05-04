package com.scivicslab.htmlsaurus;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Long-lived Lucene search helper that keeps the DirectoryReader open between requests.
 *
 * <p>Uses {@link DirectoryReader#openIfChanged} to cheaply pick up index updates without
 * reopening the underlying directory on every query. The analyzer is created once and reused.
 * All public methods are {@code synchronized} so the instance is safe to share across threads.
 */
class LuceneSearcher implements Closeable {

    private static final Logger logger = Logger.getLogger(LuceneSearcher.class.getName());

    record Hit(String title, String path, String summary, float score) {
        Hit(String title, String path, String summary) { this(title, path, summary, 0f); }
    }

    private final Path indexDir;
    private final String locale;
    private final Analyzer baseAnalyzer;
    private final Analyzer analyzer;
    private NIOFSDirectory directory;
    private DirectoryReader reader;

    LuceneSearcher(Path indexDir) {
        this(indexDir, null);
    }

    LuceneSearcher(Path indexDir, String locale) {
        this.indexDir = indexDir;
        this.locale = locale;
        this.baseAnalyzer = isJapanese() ? new JapaneseAnalyzer() : new StandardAnalyzer();
        this.analyzer = new PerFieldAnalyzerWrapper(
                baseAnalyzer,
                Map.of("doc_id_idx", SearchIndexer.underscoreAnalyzer(),
                       "path_tokens", SearchIndexer.underscoreAnalyzer(),
                       "body_ng", SearchIndexer.shingleAnalyzer(new JapaneseAnalyzer())));
    }

    /** Returns true if this searcher uses a Japanese analyzer (locale is null or "ja"). */
    boolean isJapanese() {
        return locale == null || "ja".equals(locale);
    }

    /** Returns the locale string for this searcher (may be null for the default). */
    String getLocale() {
        return locale;
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
        var expr = LuceneQueryBuilder.build(fields, boosts, queryStr);
        logger.info("query expr: " + expr);
        var parser = new MultiFieldQueryParser(fields, analyzer, boosts);
        var q = parser.parse(expr);
        var hits = searcher.search(q, maxHits);
        var stored = searcher.storedFields();

        var uh = new UnifiedHighlighter(searcher, analyzer);
        uh.setMaxLength(400);
        String[] snippets = null;
        try {
            snippets = uh.highlight("body", q, hits);
        } catch (Exception e) {
            logger.warning("Highlight failed: " + e.getMessage());
        }

        List<Hit> results = new ArrayList<>();
        for (int i = 0; i < hits.scoreDocs.length; i++) {
            var hit = hits.scoreDocs[i];
            var doc = stored.document(hit.doc);
            String body = doc.get("body") != null ? doc.get("body") : "";
            String snippet = (snippets != null && snippets[i] != null)
                ? snippets[i]
                : (body.length() > 250 ? body.substring(0, 250) + "…" : body);
            results.add(new Hit(
                doc.get("title") != null ? doc.get("title") : "",
                doc.get("path")  != null ? doc.get("path")  : "",
                snippet,
                hit.score
            ));
        }
        return results;
    }

    /**
     * Returns documents similar to the one at {@code docPath} using MoreLikeThis (TF-IDF).
     * The source document is excluded from the results.
     */
    synchronized List<Hit> moreLikeThis(String docPath, int maxHits) throws Exception {
        if (docPath == null || docPath.isBlank()) return List.of();
        ensureOpen();
        refreshReader();

        var searcher = new IndexSearcher(reader);

        // path is a StoredField (not indexed), so find the doc by linear scan across leaf readers
        int docId = -1;
        outer:
        for (var ctx : reader.leaves()) {
            var leaf = ctx.reader();
            var bits = leaf.getLiveDocs();
            var leafStored = leaf.storedFields();
            for (int i = 0; i < leaf.maxDoc(); i++) {
                if (bits != null && !bits.get(i)) continue;
                if (docPath.equals(leafStored.document(i).get("path"))) {
                    docId = ctx.docBase + i;
                    break outer;
                }
            }
        }
        if (docId == -1) return List.of();

        var sourceDoc = searcher.storedFields().document(docId);
        String title  = sourceDoc.get("title") != null ? sourceDoc.get("title") : "";
        String body   = sourceDoc.get("body")  != null ? sourceDoc.get("body")  : "";
        String input  = title + "\n" + body;

        // Title query (boosted 5x): terms from source title matched against title_idx of targets.
        // Ensures documents sharing the same topic name (e.g. "Slurm") rank at the top.
        var mltTitle = new MoreLikeThis(reader);
        mltTitle.setAnalyzer(analyzer);
        mltTitle.setFieldNames(new String[]{"title_idx"});
        mltTitle.setMinTermFreq(1);
        mltTitle.setMinDocFreq(1);
        mltTitle.setMaxDocFreqPct(100);
        mltTitle.setMaxQueryTerms(20);
        var titleQuery = mltTitle.like("title_idx", new java.io.StringReader(title));

        // Body query: full body text against body and body_ng for broad topic similarity.
        var mltBody = new MoreLikeThis(reader);
        mltBody.setAnalyzer(analyzer);
        mltBody.setFieldNames(new String[]{"body", "body_ng"});
        mltBody.setMinTermFreq(1);
        mltBody.setMinDocFreq(1);
        mltBody.setMaxDocFreqPct(100);
        mltBody.setMaxQueryTerms(100);
        var bodyQuery = mltBody.like("body", new java.io.StringReader(input));

        var query = new BooleanQuery.Builder()
            .add(new BoostQuery(titleQuery, 5.0f), BooleanClause.Occur.SHOULD)
            .add(bodyQuery, BooleanClause.Occur.SHOULD)
            .build();

        var hits = searcher.search(query, (maxHits + 1) * 3);
        var stored = searcher.storedFields();

        List<Hit> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var hit : hits.scoreDocs) {
            if (hit.doc == docId) continue;
            var doc = stored.document(hit.doc);
            String path = doc.get("path") != null ? doc.get("path") : "";
            if (!seen.add(path)) continue; // deduplicate
            String b = doc.get("body") != null ? doc.get("body") : "";
            String snippet = b.length() > 250 ? b.substring(0, 250) + "…" : b;
            results.add(new Hit(
                doc.get("title") != null ? doc.get("title") : "",
                path,
                snippet
            ));
            if (results.size() >= maxHits) break;
        }
        return results;
    }

    /**
     * Returns documents similar to the given raw text using MoreLikeThis (TF-IDF).
     * Unlike {@link #moreLikeThis}, this method accepts arbitrary text rather than a document path.
     */
    synchronized List<Hit> moreLikeThisText(String text, int maxHits) throws Exception {
        if (text == null || text.isBlank()) return List.of();
        ensureOpen();
        refreshReader();

        var searcher = new IndexSearcher(reader);
        var mlt = new MoreLikeThis(reader);
        mlt.setAnalyzer(analyzer);
        mlt.setFieldNames(new String[]{"title_idx", "body", "body_ng"});
        mlt.setMinTermFreq(1);
        mlt.setMinDocFreq(1);
        mlt.setMaxDocFreqPct(100);
        mlt.setMaxQueryTerms(100);

        var query = mlt.like("body", new java.io.StringReader(text));
        var hits = searcher.search(query, maxHits);
        var stored = searcher.storedFields();

        List<Hit> results = new ArrayList<>();
        for (var hit : hits.scoreDocs) {
            var doc = stored.document(hit.doc);
            String b = doc.get("body") != null ? doc.get("body") : "";
            String snippet = b.length() > 250 ? b.substring(0, 250) + "…" : b;
            results.add(new Hit(
                doc.get("title") != null ? doc.get("title") : "",
                doc.get("path")  != null ? doc.get("path")  : "",
                snippet,
                hit.score
            ));
            if (results.size() >= maxHits) break;
        }
        return results;
    }

    /**
     * Returns the stored {@code body} field for the document matching the given path.
     * Performs a linear scan over all leaf readers. Returns an empty string if not found.
     */
    synchronized String getBodyForPath(String path) {
        if (path == null || path.isBlank()) return "";
        try {
            ensureOpen();
            refreshReader();
            for (var ctx : reader.leaves()) {
                var leaf = ctx.reader();
                var bits = leaf.getLiveDocs();
                var leafStored = leaf.storedFields();
                for (int i = 0; i < leaf.maxDoc(); i++) {
                    if (bits != null && !bits.get(i)) continue;
                    var doc = leafStored.document(i);
                    if (path.equals(doc.get("path"))) {
                        String body = doc.get("body");
                        return body != null ? body : "";
                    }
                }
            }
        } catch (IOException e) {
            logger.warning("getBodyForPath failed: " + e.getMessage());
        }
        return "";
    }

    /** Returns the current {@link DirectoryReader}, opening and refreshing it if needed. */
    synchronized DirectoryReader getReader() throws IOException {
        ensureOpen();
        refreshReader();
        return reader;
    }

    /** Returns the analyzer used by this searcher. */
    Analyzer getAnalyzer() { return analyzer; }

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
