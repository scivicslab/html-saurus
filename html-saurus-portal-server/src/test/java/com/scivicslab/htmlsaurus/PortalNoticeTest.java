package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The notice that the starter of the portal leaves for the reader, at the top of the portal page. */
class PortalNoticeTest {

    @TempDir
    Path tempDir;

    @Test
    void eachLineIsAParagraphAndBackticksMakeCode() {
        String html = PortalNotice.render("No folder matches `~/works/doc_*`.\n\nStart Documents again with another pattern.\n");
        assertTrue(html.contains("<p>No folder matches <code>~/works/doc_*</code>.</p>"), html);
        assertTrue(html.contains("<p>Start Documents again with another pattern.</p>"), html);
        assertEquals(2, html.split("<p>", -1).length - 1, "the blank line is not a paragraph");
        assertTrue(html.contains("id=\"portal-notice\""));
    }

    @Test
    void theFileCannotAddMarkup() {
        String html = PortalNotice.render("<script>alert(1)</script> `<b>x</b>` & \"q\"");
        assertFalse(html.contains("<script>"), html);
        assertFalse(html.contains("<b>"), html);
        assertTrue(html.contains("&lt;script&gt;") && html.contains("<code>&lt;b&gt;x&lt;/b&gt;</code>"), html);
    }

    @Test
    void blankOrMissingIsNothing() throws IOException {
        assertEquals("", PortalNotice.render(""));
        assertEquals("", PortalNotice.render(" \n\t\n"));
        assertEquals("", PortalNotice.html(null));
        assertEquals("", PortalNotice.html(tempDir.resolve("absent.txt")));
        assertEquals("", PortalNotice.html(tempDir), "a directory is not a notice");
        Path blank = tempDir.resolve("blank.txt");
        Files.writeString(blank, "\n");
        assertEquals("", PortalNotice.html(blank));
    }

    @Test
    void onlyTheStartOfALargeFileIsRead() throws IOException {
        Path big = tempDir.resolve("big.txt");
        Files.writeString(big, "first\n" + "x".repeat(PortalNotice.MAX_BYTES * 3));
        String html = PortalNotice.html(big);
        assertTrue(html.contains("<p>first</p>"));
        assertTrue(html.length() < PortalNotice.MAX_BYTES + 400, "length " + html.length());
    }

    private Path project(String name) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(dir.resolve("docs/intro.md"), "---\ntitle: Introduction\n---\n\n# Introduction\n\nHello.");
        BuildStages.build(dir.resolve("docs"), dir.resolve("static-html"), false);
        BuildStages.reindex(dir.resolve("docs"), dir.resolve("search-index"));
        return dir;
    }

    private static String get(HttpServer http, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + http.getAddress().getPort() + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void thePortalPageShowsTheNoticeAboveTheTabs_onlyWhenThereIsOne() throws Exception {
        Path proj = project("proj");
        Path notice = tempDir.resolve("notice.txt");
        Files.writeString(notice, "No folder matches `~/works/doc_*`. Start Documents again with another pattern.\n");
        HttpServer with = new PortalServer(tempDir, List.of(proj), 0, null, 0, BasePath.none(), notice).start();
        try {
            String page = get(with, "/");
            int at = page.indexOf("id=\"portal-notice\"");
            assertTrue(at > 0, "the notice is on the page");
            assertTrue(page.contains("<code>~/works/doc_*</code>"));
            assertTrue(at < page.indexOf("class=\"tab-bar\""), "above the tabs, in the sidebar that is always shown");
            assertTrue(page.indexOf("<aside id=\"portal-sidebar\">") < at);
        } finally {
            with.stop(0);
        }
        HttpServer without = new PortalServer(tempDir, List.of(proj), 0, null, 0, BasePath.none(), null).start();
        try {
            assertFalse(get(without, "/").contains("id=\"portal-notice\""));
        } finally {
            without.stop(0);
        }
    }
}
