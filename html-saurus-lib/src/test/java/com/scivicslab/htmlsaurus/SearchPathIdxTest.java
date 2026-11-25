package com.scivicslab.htmlsaurus;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code path_idx} holds a page's served path (project name, then the page's path) as one string, so
 * that a search scope can be written as a path pattern in Lucene query syntax, e.g.
 * {@code path_idx:\/proj\/Books\/Chapter08\/*}. It is the path the portal shows in every search result.
 *
 * <p>The field was indexed, but the searcher's analyzer table did not list it. A query term on it was
 * then split like ordinary text, and a path never matched as a whole. These tests parse each
 * expression with the searcher's own analyzer, as a scope would be, and count the pages it selects.
 *
 * <p>The slash is escaped ({@code \/}) because an unescaped slash opens a regular expression in the
 * classic query syntax.
 */
@Tag("S1")
class SearchPathIdxTest {

    @TempDir
    Path tempDir;

    private Path index;

    @BeforeEach
    void indexThreePages() throws IOException {
        Path proj = tempDir.resolve("proj");
        Path docs = proj.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(proj.resolve("docusaurus.config.js"), "module.exports = {};");
        page(docs.resolve("Books/Chapter08"), "TheLispReader", "readtable macro characters");
        page(docs.resolve("Books/Chapter09"), "Macros", "defmacro expansion");
        page(docs.resolve("Notes"), "Daily", "shopping list");
        index = proj.resolve("search-index");
        new SearchIndexer(docs, index, "ja", false).index();
    }

    private void page(Path dir, String name, String body) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".md"),
                "---\nid: " + name + "\ntitle: " + name + "\n---\n\n" + body + "\n");
    }

    /** @return how many pages the expression selects */
    private int count(String expr) throws Exception {
        var searcher = new LuceneSearcher(index, "ja");
        var parser = new QueryParser("body", searcher.getAnalyzer());
        parser.setDefaultOperator(QueryParser.Operator.AND);
        try (var dir = new NIOFSDirectory(index);
             var reader = DirectoryReader.open(dir)) {
            return new IndexSearcher(reader).count(parser.parse(expr));
        }
    }

    @Test
    void aDirectoryPrefixSelectsThePagesUnderIt() throws Exception {
        assertEquals(2, count("path_idx:\\/proj\\/Books\\/*"));
        assertEquals(1, count("path_idx:\\/proj\\/Books\\/Chapter08\\/*"));
        assertEquals(1, count("path_idx:\\/proj\\/Notes\\/*"));
    }

    @Test
    void aWholePathSelectsThatPage() throws Exception {
        assertEquals(1, count("path_idx:\\/proj\\/Books\\/Chapter08\\/TheLispReader\\/"));
        assertEquals(0, count("path_idx:\\/proj\\/Books\\/Chapter08\\/Macros\\/"));
    }

    @Test
    void patternsCombineWithOrAndNot() throws Exception {
        assertEquals(2, count("path_idx:\\/proj\\/Notes\\/* OR path_idx:\\/proj\\/Books\\/Chapter09\\/*"));
        assertEquals(1, count("path_idx:\\/proj\\/Books\\/* AND NOT path_idx:\\/proj\\/Books\\/Chapter09\\/*"));
        // "everything outside" must start from all pages: a bare NOT selects nothing in Lucene.
        assertEquals(0, count("NOT path_idx:\\/proj\\/Books\\/*"), "a bare NOT matches nothing");
        assertEquals(1, count("*:* AND NOT path_idx:\\/proj\\/Books\\/*"));
    }

    @Test
    void thePathIsOneStringNotWords() throws Exception {
        assertEquals(0, count("path_idx:Books"), "a path segment alone is not the path");
        assertEquals(0, count("path_idx:\\/proj\\/books\\/*"), "the match is case-sensitive, like the path");
    }
}
