package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Markdown conversion rules handled by {@link MarkdownConverter},
 * in particular Docusaurus-style admonitions.
 */
class MarkdownConverterTest {

    @TempDir
    Path tempDir;

    /** Builds a minimal single-page project and returns the rendered HTML. */
    private String buildPage(String markdownBody) throws IOException {
        Path proj = tempDir.resolve("proj");
        Path docs = proj.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("page.md"), markdownBody);
        Path out = proj.resolve("static-html");
        new SiteBuilder(docs, out).build();
        return Files.readString(out.resolve("page.html"));
    }

    @Nested
    @DisplayName("Admonitions")
    class Admonitions {

        @Test
        @DisplayName(":::danger with bracket title renders admonition block")
        void bracketTitle_rendersAdmonition() throws IOException {
            String html = buildPage(":::danger[注意]\n内容\n:::\n");

            assertTrue(html.contains("admonition-danger") || html.contains("admonition admonition-danger"),
                "Bracket-title admonition must render with danger class");
            assertTrue(html.contains("注意"),
                "Custom bracket title must appear in rendered HTML");
            assertTrue(html.contains("内容"),
                "Admonition body must appear in rendered HTML");
        }

        @Test
        @DisplayName(":::danger with space-separated title renders admonition block")
        void spaceSeparatedTitle_rendersAdmonition() throws IOException {
            String html = buildPage(":::danger これは古いドキュメントです\n本ドキュメントは旧版です。\n:::\n");

            assertTrue(html.contains("admonition-danger") || html.contains("admonition admonition-danger"),
                "Space-separated-title admonition must render with danger class");
            assertTrue(html.contains("これは古いドキュメントです"),
                "Space-separated title must appear in rendered HTML");
            assertTrue(html.contains("本ドキュメントは旧版です"),
                "Admonition body must appear in rendered HTML");
        }

        @Test
        @DisplayName(":::note without title uses default title")
        void noTitle_usesDefaultTitle() throws IOException {
            String html = buildPage(":::note\n本文\n:::\n");

            assertTrue(html.contains("admonition-note") || html.contains("admonition admonition-note"),
                "No-title admonition must render with note class");
            assertTrue(html.contains("Note"),
                "Default title 'Note' must appear when no custom title is given");
        }

        @Test
        @DisplayName("raw HTML table in markdown is rendered as HTML, not escaped")
        void rawHtmlTable_renderedAsHtml() throws IOException {
            String html = buildPage("<table><tr><td>セル</td></tr></table>\n");

            assertTrue(html.contains("<table>"),
                "Raw HTML <table> in Markdown must be rendered as HTML, not escaped as &lt;table&gt;");
            assertTrue(html.contains("<td>セル</td>"),
                "Raw HTML table cell content must be rendered as HTML");
            assertFalse(html.contains("&lt;table&gt;"),
                "Raw HTML must not be escaped to entity form");
        }

        @Test
        @DisplayName("HTML table with blank lines inside cells renders as table, not code block")
        void htmlTableWithBlankLines_notCodeBlock() throws IOException {
            String md =
                "<table>\n<tbody>\n<tr>\n<td>\n\nセルの内容<br />\n\n</td>\n</tr>\n</tbody>\n</table>\n";
            String html = buildPage(md);

            assertTrue(html.contains("<td>"),
                "Table cell must render as <td>, not be split by blank lines");
            assertFalse(html.contains("<pre>"),
                "Indented HTML inside table must not be treated as a code block");
            assertTrue(html.contains("セルの内容"),
                "Cell text must appear in rendered output");
        }

        @Test
        @DisplayName(":::danger inside fenced code block is not processed")
        void insideFencedCode_notProcessed() throws IOException {
            String html = buildPage("```\n:::danger タイトル\n本文\n:::\n```\n");

            assertFalse(html.contains("admonition-danger"),
                "Admonition syntax inside fenced code block must not be processed");
        }
    }
}
