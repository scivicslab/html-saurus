package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The left sidebar cuts long titles off (CSS ellipsis), so every entry, at every level, carries its
 * full title as a tooltip: the {@code title} attribute of the category links, the category labels and
 * the document links, escaped like the text.
 */
class SidebarTooltipTest {

    @TempDir
    Path tempDir;

    static final String LONG_TITLE =
            "A very long document title with \"quotes\", <angle brackets> & an ampersand that the sidebar cuts off";
    static final String CATEGORY_TITLE = "Category \"quoted\" with <tags> & more words than fit";

    private void writeDoc(Path docsDir, String relPath, String title) throws IOException {
        Path file = docsDir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntitle: " + title + "\n---\n\nContent.");
    }

    /** The {@code <nav class="side">} of a built page. */
    private static String sidebarOf(String html) {
        int start = html.indexOf("<nav class=\"side\"");
        int end = html.indexOf("</nav>", start);
        return html.substring(start, end);
    }

    @Test
    void everySidebarEntryCarriesItsFullTitleEscaped() throws IOException {
        Path proj = tempDir.resolve("proj");
        Path docs = proj.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(proj.resolve("docusaurus.config.js"), "module.exports = {};");
        // Under the top section "part": a category with a same-name page (its label is a link), a plain
        // category inside it (a label), and a document with a long title below that.
        writeDoc(docs, "010_part/010_intro.md", "Intro");
        writeDoc(docs, "010_part/020_sec/020_sec.md", CATEGORY_TITLE);
        writeDoc(docs, "010_part/020_sec/030_chapter/010_long.md", LONG_TITLE);
        writeDoc(docs, "010_part/020_sec/030_chapter/020_short.md", "Short");

        BuildStages.build(docs, proj.resolve("static-html"), false);

        String page = Files.readString(proj.resolve("static-html/part/sec/chapter/long/index.html"));
        String nav = sidebarOf(page);
        String escapedLong = "A very long document title with &quot;quotes&quot;, &lt;angle brackets&gt; &amp; an ampersand that the sidebar cuts off";
        assertTrue(nav.contains("title=\"" + escapedLong + "\""), "the document link carries its full title, escaped: " + nav);
        String escapedCategory = "Category &quot;quoted&quot; with &lt;tags&gt; &amp; more words than fit";
        assertTrue(nav.contains("title=\"" + escapedCategory + "\""), "the category link carries its full title: " + nav);
        assertFalse(nav.contains("title=\"A very long document title with \"quotes\""), "a raw quote never ends the attribute early");

        // Every entry, at every level, has a title equal to the text it shows.
        // The section links of the narrow-screen top bar are entries of the same sidebar, so they are counted too.
        Pattern entry = Pattern.compile("<(a|span)([^>]*class=\"[^\"]*cat-label[^\"]*\"[^>]*|[^>]*href=\"[^\"]*\"[^>]*)>([^<]*)</\\1>");
        Matcher m = entry.matcher(nav.substring(nav.indexOf("<div class=\"mobile-top-nav\">")));
        List<String> missing = new ArrayList<>();
        int entries = 0;
        while (m.find()) {
            entries++;
            if (!m.group(2).contains("title=\"" + m.group(3) + "\"")) missing.add(m.group(0));
        }
        assertTrue(entries >= 6, "the section link, the intro, the category link, the chapter label and two documents: " + entries);
        assertTrue(missing.isEmpty(), "entries without their full title as a tooltip: " + missing);
    }
}
