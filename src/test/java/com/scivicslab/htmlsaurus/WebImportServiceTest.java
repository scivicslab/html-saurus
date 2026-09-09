package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WebImportService}: pure jsoup extraction and document assembly. Every image
 * download goes through a stub {@link WebImportService.Fetcher}, so no test here touches the network.
 */
class WebImportServiceTest {

    private static final String PAGE = """
        <html>
          <head>
            <title>Fallback Title</title>
            <meta property="og:title" content="The Real Title">
            <meta property="og:image" content="/poster.png">
          </head>
          <body>
            <nav><a href="/">Home</a></nav>
            <article>
              <h1>Chapter One</h1>
              <p>First paragraph.</p>
              <figure><img src="/pictures/diagram.png"><figcaption>A diagram</figcaption></figure>
              <h2>A section</h2>
              <p>Second paragraph, see <a href="/policy">the policy</a>.</p>
            </article>
            <div class="sharedaddy sd-sharing-enabled"><h3>Share this post:</h3></div>
            <div class="sharedaddy sd-block sd-like"><h3>Like this:</h3></div>
            <footer>Copyright someone</footer>
          </body>
        </html>
        """;

    /** Answers every image request with the same bytes and content type, recording what was asked for. */
    private static final class StubFetcher implements WebImportService.Fetcher {
        final List<String> requested = new ArrayList<>();
        private final String contentType;
        private final byte[] bytes;

        StubFetcher(String contentType, byte[] bytes) {
            this.contentType = contentType;
            this.bytes = bytes;
        }

        @Override
        public WebImportService.Download get(String url) {
            requested.add(url);
            return new WebImportService.Download(bytes, contentType);
        }
    }

    private static StubFetcher png() {
        return new StubFetcher("image/png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
    }

    @Test
    void extract_prefersOgTitleOverTheDocumentTitle() {
        WebImportService.Extracted got = WebImportService.extract(PAGE, "https://example.invalid/a");
        assertEquals("The Real Title", got.title());
    }

    @Test
    void extract_keepsHeadingsAndParagraphsInDocumentOrder() {
        WebImportService.Extracted got = WebImportService.extract(PAGE, "https://example.invalid/a");
        int chapter = got.markdown().indexOf("# Chapter One");
        int first = got.markdown().indexOf("First paragraph.");
        int section = got.markdown().indexOf("## A section");
        int second = got.markdown().indexOf("Second paragraph,");
        assertTrue(chapter >= 0 && first > chapter && section > first && second > section,
                "expected heading/paragraph order to survive extraction, got:\n" + got.markdown());
    }

    @Test
    void extract_dropsNavigationAndFooterBoilerplate() {
        WebImportService.Extracted got = WebImportService.extract(PAGE, "https://example.invalid/a");
        assertFalse(got.markdown().contains("Copyright someone"));
        assertFalse(got.markdown().contains("Home"));
    }

    @Test
    void extract_dropsBlogShareAndLikeWidgets() {
        WebImportService.Extracted got = WebImportService.extract(PAGE, "https://example.invalid/a");
        assertFalse(got.markdown().contains("Share this post"),
                "a Jetpack .sharedaddy block leaves a contentless heading behind:\n" + got.markdown());
        assertFalse(got.markdown().contains("Like this"), got.markdown());
    }

    @Test
    void extract_keepsLinksAsMarkdownWithAbsoluteUrls() {
        WebImportService.Extracted got = WebImportService.extract(PAGE, "https://example.invalid/a/b");
        assertTrue(got.markdown().contains("[the policy](https://example.invalid/policy)"),
                "an anchor must survive as a Markdown link:\n" + got.markdown());
    }

    @Test
    void inlineMarkdown_dropsTheAnchorButKeepsTheTextWhenThereIsNoDestination() {
        String html = "<html><body><article><p>See <a>this</a> and "
                + "<a href=\"#top\">that</a>.</p></article></body></html>";
        WebImportService.Extracted got = WebImportService.extract(html, "https://example.invalid/a");
        assertEquals("See this and that.", got.markdown());
    }

    @Test
    void inlineMarkdown_escapesBracketsInLinkTextAndBracketsUrlsHoldingSpaces() {
        String html = "<html><body><article><p><a href=\"/a b\">x [y] z</a></p></article></body></html>";
        WebImportService.Extracted got = WebImportService.extract(html, "https://example.invalid/");
        assertEquals("[x \\[y\\] z](<https://example.invalid/a b>)", got.markdown());
    }

    @Test
    void inlineMarkdown_keepsTextOfOtherInlineElements() {
        String html = "<html><body><article><p>a <strong>bold</strong> and <em>italic</em> word</p>"
                + "</article></body></html>";
        WebImportService.Extracted got = WebImportService.extract(html, "https://example.invalid/a");
        assertEquals("a bold and italic word", got.markdown());
    }

    @Test
    void extract_keepsArticleImagesAndResolvesThemAgainstTheBaseUrl() {
        WebImportService.Extracted got = WebImportService.extract(PAGE, "https://example.invalid/a/b");
        assertEquals(1, got.imageUrls().size());
        assertEquals("https://example.invalid/pictures/diagram.png", got.imageUrls().get("img1"));
        assertTrue(got.markdown().contains("![](img1)"));
    }

    @Test
    void extract_usesOgImageOnlyWhenTheArticleNamesNoImage() {
        String noImages = """
            <html><head><meta property="og:image" content="https://example.invalid/poster.png"></head>
            <body><article><p>Just words.</p></article></body></html>
            """;
        WebImportService.Extracted got = WebImportService.extract(noImages, "https://example.invalid/a");
        assertEquals(1, got.imageUrls().size());
        assertEquals("https://example.invalid/poster.png", got.imageUrls().get("img1"));
        assertTrue(got.markdown().startsWith("![](img1)"));
    }

    @Test
    void extract_skipsDataUriImages() {
        String inline = """
            <html><body><article><p>Words.</p>
            <img src="data:image/gif;base64,R0lGOD"></article></body></html>
            """;
        WebImportService.Extracted got = WebImportService.extract(inline, "https://example.invalid/a");
        assertTrue(got.imageUrls().isEmpty());
    }

    @Test
    void extract_fallsBackToTheContainerTextWhenThereIsNoParagraphMarkup() {
        String bare = "<html><body><article>Loose text with no markup.</article></body></html>";
        WebImportService.Extracted got = WebImportService.extract(bare, "https://example.invalid/a");
        assertEquals("Loose text with no markup.", got.markdown());
    }

    @Test
    void fetch_downloadsEachImageAndRenamesItByContentType() {
        StubFetcher fetcher = png();
        WebImportService.Result result = WebImportService.fetch(
                "https://example.invalid/a", PAGE, null, fetcher);

        assertEquals(List.of("https://example.invalid/pictures/diagram.png"), fetcher.requested);
        assertEquals(1, result.images().size());
        assertTrue(result.images().containsKey("img1.png"),
                "expected img1.png, got " + result.images().keySet());
        assertTrue(result.markdown().contains("![](img1.png)"));
    }

    @Test
    void fetch_dropsTheReferenceWhenAnImageCannotBeDownloaded() {
        WebImportService.Fetcher failing = url -> { throw new IOException("404"); };
        WebImportService.Result result = WebImportService.fetch(
                "https://example.invalid/a", PAGE, null, failing);

        assertTrue(result.images().isEmpty());
        assertFalse(result.markdown().contains("![]("),
                "a failed image must leave no reference behind:\n" + result.markdown());
        assertTrue(result.markdown().contains("First paragraph."));
    }

    @Test
    void fetch_writesFrontmatterNamingTheSourceUrlAndTitle() {
        WebImportService.Result result = WebImportService.fetch(
                "https://example.invalid/a", PAGE, null, png());
        String md = result.markdown();
        assertTrue(md.startsWith("---\n"), md.substring(0, Math.min(40, md.length())));
        assertTrue(md.contains("title: \"The Real Title\""), md);
        assertTrue(md.contains("source_url: \"https://example.invalid/a\""), md);
    }

    @Test
    void fetch_titleOverrideWinsOverThePagesOwnTitle() {
        WebImportService.Result result = WebImportService.fetch(
                "https://example.invalid/a", PAGE, "My Own Name", png());
        assertEquals("My Own Name", result.title());
        assertTrue(result.markdown().contains("title: \"My Own Name\""));
    }

    @Test
    void extensionFor_prefersTheServersContentTypeThenTheUrl() {
        assertEquals(".jpg", WebImportService.extensionFor("image/jpeg", "https://x.invalid/a"));
        assertEquals(".png", WebImportService.extensionFor("image/png; charset=binary", "https://x.invalid/a"));
        assertEquals(".webp", WebImportService.extensionFor("application/octet-stream", "https://x.invalid/a.webp?v=2"));
        assertEquals(".img", WebImportService.extensionFor(null, "https://x.invalid/image"));
    }

    @Test
    void titleToStem_keepsLettersAndDigitsAndCollapsesEverythingElse() {
        assertEquals("Hello-World", WebImportService.titleToStem("Hello, World!"));
        assertEquals("日本語のタイトル", WebImportService.titleToStem("日本語のタイトル"));
        assertEquals("untitled", WebImportService.titleToStem("!!!"));
        assertEquals("untitled", WebImportService.titleToStem(null));
        assertEquals(80, WebImportService.titleToStem("a".repeat(200)).length());
    }

    @Test
    void assembleDocument_escapesQuotesInTheTitle() {
        String md = WebImportService.assembleDocument("body", "https://x.invalid/a", "a \"quoted\" title");
        assertTrue(md.contains("title: \"a \\\"quoted\\\" title\""), md);
    }

    @Test
    void fetch_keepsJapaneseTitleAndBody() {
        String japanese = """
            <html><head><title>日本語のページ</title></head>
            <body><article><p>本文です。</p></article></body></html>
            """;
        WebImportService.Result result = WebImportService.fetch(
                "https://example.invalid/a", japanese, null, png());
        assertEquals("日本語のページ", result.title());
        assertTrue(result.markdown().contains("本文です。"));
    }
}
