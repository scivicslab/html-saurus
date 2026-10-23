package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Which external addresses a built site links to, and from which pages. */
@Tag("S1")
class ExternalLinksTest {

    @TempDir
    Path site;

    private void page(String dir, String body) throws IOException {
        Path d = site.resolve(dir);
        Files.createDirectories(d);
        Files.writeString(d.resolve("index.html"), "<html><body>" + body + "</body></html>");
    }

    @Test
    void collectsHttpAndHttpsAnchorsOnly() throws IOException {
        page("a", "<a href=\"https://x.example/p\">1</a><a href=\"http://y.example/\">2</a>"
                + "<a href=\"/local/\">3</a><a href=\"mailto:a@b.c\">4</a><img src=\"https://cdn.example/i.png\">"
                + "<link href=\"https://fonts.example/css\">");
        Map<String, List<String>> found = ExternalLinks.collect(site);
        assertEquals(List.of("https://x.example/p", "http://y.example/"), List.copyOf(found.keySet()));
    }

    @Test
    void anAddressLinkedFromManyPagesIsListedOnceWithAllItsPages() throws IOException {
        page("a", "<a href=\"https://x.example/p\">1</a><a href=\"https://x.example/p\">again</a>");
        page("b", "<a href=\"https://x.example/p#section\">2</a>");
        Map<String, List<String>> found = ExternalLinks.collect(site);
        assertEquals(1, found.size());
        assertEquals(List.of("/a/", "/b/"), found.get("https://x.example/p"));
    }

    @Test
    void entitiesInTheAddressAreDecoded() throws IOException {
        page("a", "<a href=\"https://x.example/q?a=1&amp;b=2\">1</a>");
        assertEquals(List.of("https://x.example/q?a=1&b=2"), List.copyOf(ExternalLinks.collect(site).keySet()));
    }

    @Test
    void theSitesOwnAddressesAreLeftOutBecauseTheyAreInternalLinks() throws IOException {
        page("a", "<a href=\"https://site.example/guides/\">own</a><a href=\"https://other.example/x\">other</a>");
        assertEquals(List.of("https://other.example/x"),
                List.copyOf(ExternalLinks.collect(site, "site.example").keySet()));
        assertEquals(2, ExternalLinks.collect(site).size());
    }

    @Test
    void theOwnHostComesFromTheUrlInTheConfig() throws IOException {
        Path repo = site.resolveSibling("cfg_repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("docusaurus.config.ts"), "const config = {\n  url: 'https://Site.Example/',\n};");
        assertEquals("site.example", ExternalLinks.ownHostOf(repo));
        assertNull(ExternalLinks.ownHostOf(site));
    }

    @Test
    void addressesCommentedOutAboveTheRealOneAreNotTheSitesAddress() throws IOException {
        Path repo = site.resolveSibling("cfg_commented");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("docusaurus.config.ts"),
                "const config = {\n  //url: 'http://localhost',\n  //url: 'http://192.168.11.99',\n"
                        + "  url: 'https://real.example',\n};");
        assertEquals("real.example", ExternalLinks.ownHostOf(repo));
    }
}
