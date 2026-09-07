package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a search puts first.
 *
 * <p>The weights in {@link LuceneQueryBuilder#BOOSTS} decide the order. The title counts ten,
 * above the document identifier and the path at five, so a page whose title is the search term
 * comes before a page that merely lives in a directory of that name.
 *
 * <p>A unit test: it builds a small index in a temporary directory and reads it back. Nothing
 * outside the process is touched.
 */
@Tag("S1.05")
class SearchRankingTest {

    @TempDir
    Path tempDir;

    /** Writes one page with the given title and body, the way SearchIndexer expects to find it. */
    private void writeDoc(Path docsDir, String relPath, String title, String body) throws IOException {
        Path file = docsDir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntitle: " + title + "\n---\n\n# " + title + "\n\n" + body);
    }

    @Test
    @DisplayName("a page titled Widget beats a page that only sits in a directory named widget")
    void titleOutranksThePathAndTheIdentifier() throws Exception {
        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(docsDir);
        // Titled Widget, with nothing in its directory or filename saying widget.
        writeDoc(docsDir, "010_overview/010_overview.md", "Widget", "A short page about nothing much.");
        // Titled something else, but its directory and filename both say widget, which feeds
        // path_tokens and doc_id_idx — the two fields that used to outweigh the title.
        writeDoc(docsDir, "020_widget/020_widget.md", "Release notes", "A short page about nothing much.");

        Path indexDir = tempDir.resolve("index");
        new SearchIndexer(docsDir, indexDir, "en", false).index();

        List<LuceneSearcher.Hit> hits;
        try (var searcher = new LuceneSearcher(indexDir, "en")) {
            hits = searcher.search("widget", 10, LuceneQueryBuilder.fields(), LuceneQueryBuilder.BOOSTS);
        }

        assertEquals(2, hits.size(), "both pages match the query");
        assertEquals("Widget", hits.get(0).title(),
                "the page titled Widget must come first; at a title weight of three it came second");
        assertTrue(hits.get(0).score() > hits.get(1).score(), "and must score higher, not merely tie");
    }

    @Test
    @DisplayName("the weight reaches the query expression")
    void theQueryExpressionCarriesTheWeight() {
        String expr = LuceneQueryBuilder.build(
                LuceneQueryBuilder.fields(), LuceneQueryBuilder.BOOSTS, "widget");

        assertTrue(expr.contains("title_idx:(widget)^10.0"), "actual expression: " + expr);
        assertTrue(expr.contains("body:(widget)") && !expr.contains("body:(widget)^"),
                "a body match counts one, and a weight of one is left off: " + expr);
    }

    @Test
    @DisplayName("each caller gets its own array of field names")
    void fieldsAreHandedOutAsACopy() {
        String[] first = LuceneQueryBuilder.fields();
        String[] second = LuceneQueryBuilder.fields();

        assertNotSame(first, second, "callers pass this straight into Lucene; they must not share one");
        first[0] = "clobbered";
        assertEquals("title_idx", LuceneQueryBuilder.fields()[0],
                "writing into one caller's array must not change what the next caller gets");
    }
}
