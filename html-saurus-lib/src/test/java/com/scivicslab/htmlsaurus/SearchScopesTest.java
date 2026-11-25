package com.scivicslab.htmlsaurus;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Search scopes: named path patterns that narrow a search, plus one scope for everything the others
 * leave out, so that the scopes together cover every page.
 *
 * <p>The tests index three pages and count how many a choice of scopes selects.
 */
@Tag("S1")
class SearchScopesTest {

    private static final StandardAnalyzer BASE = new StandardAnalyzer();
    private static final String BOOKS = "path_idx:\\/proj\\/Books\\/*";
    private static final String CHAPTER9 = "path_idx:\\/proj\\/Books\\/Chapter09\\/*";

    @TempDir
    Path tempDir;

    private Path index;

    @BeforeEach
    void indexThreePages() throws IOException {
        Path proj = tempDir.resolve("proj");
        Path docs = proj.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(proj.resolve("docusaurus.config.js"), "module.exports = {};");
        page(docs.resolve("Books/Chapter08"), "TheLispReader");
        page(docs.resolve("Books/Chapter09"), "Macros");
        page(docs.resolve("Notes"), "Daily");
        index = proj.resolve("search-index");
        new SearchIndexer(docs, index, "ja", false).index();
    }

    private void page(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".md"), "---\nid: " + name + "\ntitle: " + name + "\n---\n\nbody of " + name + "\n");
    }

    /** @return how many pages the filter selects; every page when there is no filter */
    private int count(Query filter) throws IOException {
        try (var dir = new NIOFSDirectory(index); var reader = DirectoryReader.open(dir)) {
            var searcher = new IndexSearcher(reader);
            return filter == null ? reader.numDocs() : searcher.count(filter);
        }
    }

    private SearchScopes books() throws ParseException {
        return SearchScopes.empty().add("Books", BOOKS, BASE);
    }

    @Test
    void aTickedScopeSelectsItsPages() throws Exception {
        var scopes = books().add("Notes", "path_idx:\\/proj\\/Notes\\/*", BASE);
        assertEquals(2, count(scopes.filter(List.of("c1"), BASE)));
        assertEquals(1, count(scopes.filter(List.of("c2"), BASE)));
    }

    @Test
    void scopesMayOverlapAndAPageInTwoIsFoundOnce() throws Exception {
        var scopes = books().add("Chapter 9", CHAPTER9, BASE);
        assertEquals(2, count(scopes.filter(List.of("c1", "c2"), BASE)), "Chapter 9 is inside Books");
    }

    @Test
    void theRestIsEverythingNoStoredScopeSelects() throws Exception {
        var scopes = books();
        assertEquals(1, count(scopes.filter(List.of(SearchScopes.REST_ID), BASE)), "only Notes is outside Books");
        assertEquals(3, count(scopes.filter(List.of("c1", SearchScopes.REST_ID), BASE)));
    }

    @Test
    void theStoredScopesAndTheRestCoverEveryPageExactlyOnce() throws Exception {
        var scopes = books().add("Notes", "path_idx:\\/proj\\/Notes\\/*", BASE);
        int sum = count(scopes.filter(List.of("c1"), BASE))
                + count(scopes.filter(List.of("c2"), BASE))
                + count(scopes.filter(List.of(SearchScopes.REST_ID), BASE));
        assertEquals(3, sum);
    }

    @Test
    void withNoStoredScopeTheRestIsEveryPage() throws Exception {
        assertEquals(3, count(SearchScopes.empty().filter(List.of(SearchScopes.REST_ID), BASE)));
    }

    @Test
    void nothingTickedMeansNoFilter() throws Exception {
        assertNull(books().filter(List.of(), BASE));
        assertNull(books().filter(null, BASE));
        assertNull(books().filter(Set.of("gone"), BASE), "an id that names no scope selects nothing to narrow by");
    }

    @Test
    void aQueryThatCannotBeParsedIsRefused() {
        assertThrows(ParseException.class, () -> SearchScopes.empty().add("Broken", "path_idx:(", BASE));
        assertThrows(IllegalArgumentException.class, () -> SearchScopes.empty().add(" ", BOOKS, BASE));
        assertThrows(IllegalArgumentException.class, () -> SearchScopes.empty().add("Books", "  ", BASE));
    }

    @Test
    void removingAScopeKeepsTheOthersAndTheirIds() throws Exception {
        var scopes = books().add("Notes", "path_idx:\\/proj\\/Notes\\/*", BASE).remove("c1");
        assertEquals(List.of("c2"), scopes.categories().stream().map(SearchScopes.Category::id).toList());
        assertEquals("c3", scopes.add("Again", BOOKS, BASE).categories().get(1).id(), "an id is not reused");
    }

    @Test
    void theScopesSurviveTheFileIncludingJapaneseNamesAndEscapes() throws Exception {
        Path file = tempDir.resolve("state/search-scopes.json");
        var scopes = SearchScopes.empty()
                .add("文書 \"A\"", "path_idx:\\/proj\\/Books\\/*", BASE);
        scopes.save(file);
        var back = SearchScopes.load(file);
        assertEquals(scopes.categories(), back.categories());
        assertEquals("文書 \"A\"", back.categories().get(0).name());
    }

    @Test
    void aMissingFileIsNoScopesButADamagedFileIsNotReplaced() throws Exception {
        Path file = tempDir.resolve("scopes.json");
        assertTrue(SearchScopes.load(file).categories().isEmpty());
        Files.writeString(file, "{not json");
        assertThrows(IOException.class, () -> SearchScopes.load(file));
        assertEquals("{not json", Files.readString(file), "reading did not touch the file");
    }

    @Test
    void theJsonTheApiReturnsNamesTheRestToo() throws Exception {
        String json = books().toJson();
        assertTrue(json.contains("\"categories\":[{\"id\":\"c1\",\"name\":\"Books\""), json);
        assertTrue(json.contains("\"rest\":{\"id\":\"rest\",\"name\":\"" + SearchScopes.REST_NAME + "\"}"), json);
    }
}
