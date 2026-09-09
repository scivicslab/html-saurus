package com.scivicslab.htmlsaurus;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a web page into Markdown: fetches the page, extracts its readable article content, and
 * downloads the images that content references so the imported document stands on its own.
 * Extraction is pragmatic rather than a full readability engine — boilerplate elements are removed,
 * a semantic main container is preferred over the whole body, and headings, paragraphs and images
 * are emitted in document order.
 *
 * <p>{@link #extract} is pure parsing — no network — so it can be unit tested against a fixed HTML
 * string; {@link #fetch} is the one method that goes out to the network, for the page itself and
 * then for each image the extracted content names.
 *
 * <p>Images are downloaded rather than left as remote {@code <img src>} URLs, matching what
 * {@link PdfImportService} and {@link WordImportService} do: an imported document keeps its images
 * as real files alongside the {@code .md}, so it does not decay when the source site changes.
 */
final class WebImportService {

    private WebImportService() {}

    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; html-saurus/1.0; +https://example.invalid/bot)";
    private static final int TIMEOUT_MS = 15_000;
    /** Refuse an image this large rather than write it into a docs/ directory. */
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    /**
     * What is removed before the article is read. The structural half is generic ({@code nav},
     * {@code footer}, ...); the class-named half is the share/like/related widgets a blog platform
     * appends after the article body — Jetpack's {@code .sharedaddy} blocks, which is what put a
     * contentless {@code ### Share this post:} at the end of an imported NCBI Insights page.
     */
    private static final String BOILERPLATE_SELECTOR =
            "script, style, noscript, nav, header, footer, aside, form, "
            + ".nav, .menu, .sidebar, .comments, #comments, "
            + ".sharedaddy, .sd-block, .sd-sharing, .jp-relatedposts, "
            + ".social-share, .share-buttons, .addtoany_share_save_container, "
            + ".entry-meta, .post-navigation";

    /** One downloaded file: its bytes and the {@code Content-Type} the server stated. */
    record Download(byte[] bytes, String contentType) {}

    /** Fetches one URL as bytes. Separated out so {@link #fetch} can be driven without a network. */
    interface Fetcher {
        Download get(String url) throws IOException;
    }

    /**
     * What parsing one page yields: its title, the body Markdown, and the images that body
     * references. Each image is keyed by the placeholder name used in the Markdown
     * ({@code img1}, {@code img2}, ...) and valued by the absolute URL to download it from —
     * the real extension is only known once the server answers, so {@link #fetch} renames.
     */
    record Extracted(String title, String markdown, Map<String, String> imageUrls) {}

    /** A finished import: the Markdown to write, and the image files to write beside it. */
    record Result(String title, String markdown, Map<String, byte[]> images) {}

    /**
     * Parses one already-fetched page. {@code baseUri} is the URL the HTML came from, used to
     * resolve relative {@code <img src>} values to absolute URLs.
     *
     * <p>Boilerplate containers are dropped, but {@code figure} and {@code figcaption} are kept
     * (unlike a text-only extractor would): in an article they hold the illustrations, which is
     * exactly what this import is asked to bring across.
     */
    static Extracted extract(String html, String baseUri) {
        Document doc = Jsoup.parse(html, baseUri);

        String title = firstNonBlank(metaContent(doc, "og:title"), doc.title());
        if (title.isBlank()) {
            title = "untitled";
        }
        String ogImage = metaUrl(doc, "og:image");

        doc.select(BOILERPLATE_SELECTOR).remove();
        Element root = firstPresent(doc, "article", "main", "[role=main]");
        if (root == null) {
            root = doc.body();
        }
        if (root == null) {
            return new Extracted(title.strip(), "", Map.of());
        }

        // Headings, paragraphs and images in document order: a Markdown document in html-saurus is
        // navigated by its headings (they become the page's table of contents), so the structure
        // the page states is worth keeping rather than flattening to a run of paragraphs.
        Map<String, String> imageUrls = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        for (Element el : root.select("h1, h2, h3, h4, h5, h6, p, img")) {
            if (el.normalName().equals("img")) {
                String src = el.absUrl("src");
                if (src.isBlank() || src.startsWith("data:") || imageUrls.containsValue(src)) {
                    continue;
                }
                String name = "img" + (imageUrls.size() + 1);
                imageUrls.put(name, src);
                append(body, "![](" + name + ")");
                continue;
            }
            String text = inlineMarkdown(el);
            if (text.isEmpty()) {
                continue;
            }
            int level = headingLevel(el.normalName());
            append(body, level == 0 ? text : "#".repeat(level) + " " + text);
        }

        String markdown = body.toString();
        if (markdown.isBlank()) {
            // No paragraph or heading markup at all: keep the container's text rather than
            // importing an empty document.
            markdown = root.text().strip();
        }
        // The poster image is the page's own answer to "what does this article look like", so it
        // is worth keeping when the article body itself named no image.
        if (imageUrls.isEmpty() && !ogImage.isBlank()) {
            imageUrls.put("img1", ogImage);
            markdown = "![](img1)\n\n" + markdown;
        }
        return new Extracted(title.strip(), markdown, imageUrls);
    }

    /**
     * Fetches {@code url}, extracts its article content, and downloads every image that content
     * references. An image that cannot be downloaded is dropped from the Markdown rather than left
     * as a broken reference; a page that cannot be fetched at all throws.
     *
     * @param titleOverride used instead of the page's own title when non-blank
     */
    static Result fetch(String url, String titleOverride) throws IOException {
        Document page = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .get();
        return fetch(page.location() == null || page.location().isBlank() ? url : page.location(),
                page.outerHtml(), titleOverride, WebImportService::download);
    }

    /**
     * The network-free half of {@link #fetch(String, String)}: given the page's HTML, resolve its
     * images through {@code fetcher} and assemble the document.
     */
    static Result fetch(String url, String html, String titleOverride, Fetcher fetcher) {
        Extracted extracted = extract(html, url);
        String title = (titleOverride == null || titleOverride.isBlank())
                ? extracted.title() : titleOverride.strip();

        String markdown = extracted.markdown();
        Map<String, byte[]> images = new LinkedHashMap<>();
        for (var entry : extracted.imageUrls().entrySet()) {
            String placeholder = entry.getKey();
            Download got;
            try {
                got = fetcher.get(entry.getValue());
            } catch (Exception e) {
                System.err.println("Web import: skipping image " + entry.getValue()
                        + " (" + e.getMessage() + ")");
                markdown = removeImageReference(markdown, placeholder);
                continue;
            }
            if (got == null || got.bytes().length == 0 || got.bytes().length > MAX_IMAGE_BYTES) {
                markdown = removeImageReference(markdown, placeholder);
                continue;
            }
            String named = placeholder + extensionFor(got.contentType(), entry.getValue());
            images.put(named, got.bytes());
            markdown = markdown.replace("(" + placeholder + ")", "(" + named + ")");
        }
        return new Result(title, assembleDocument(markdown, url, title), images);
    }

    /** Joins the body with YAML frontmatter naming the page this document was imported from. */
    static String assembleDocument(String bodyMarkdown, String sourceUrl, String title) {
        return "---\n"
            + "title: " + yamlQuote(title) + "\n"
            + "source_url: " + yamlQuote(sourceUrl) + "\n"
            + "---\n\n"
            + bodyMarkdown;
    }

    /**
     * A directory- and URL-safe stem derived from a document title, for the Web and Video imports —
     * neither has a source filename to take one from. Letters and digits (including Japanese, which
     * this project's own docs directories already use) are kept; every other run of characters
     * becomes a single {@code -}.
     */
    static String titleToStem(String title) {
        if (title == null) {
            return "untitled";
        }
        StringBuilder sb = new StringBuilder();
        boolean pendingDash = false;
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingDash && sb.length() > 0) sb.append('-');
                pendingDash = false;
                sb.append(c);
            } else {
                pendingDash = true;
            }
        }
        String stem = sb.toString();
        if (stem.isEmpty()) {
            return "untitled";
        }
        // Long enough to stay readable, short enough to keep the whole path within filesystem limits.
        return stem.length() > 80 ? stem.substring(0, 80) : stem;
    }

    /** Drops one {@code ![](name)} reference, and the blank line it sat on, from the body. */
    private static String removeImageReference(String markdown, String placeholder) {
        return markdown.replace("![](" + placeholder + ")\n\n", "")
                       .replace("![](" + placeholder + ")", "");
    }

    /**
     * The file extension to save a downloaded image under: what the server said it is, else what
     * the URL path ends in, else {@code .img} — a wrong extension would be a worse lie than a
     * neutral one.
     */
    static String extensionFor(String contentType, String url) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        int semi = type.indexOf(';');
        if (semi >= 0) type = type.substring(0, semi).strip();
        switch (type) {
            case "image/jpeg", "image/jpg" -> { return ".jpg"; }
            case "image/png" -> { return ".png"; }
            case "image/gif" -> { return ".gif"; }
            case "image/webp" -> { return ".webp"; }
            case "image/svg+xml" -> { return ".svg"; }
            case "image/avif" -> { return ".avif"; }
            case "image/bmp" -> { return ".bmp"; }
            default -> { /* fall through to the URL */ }
        }
        String path = url == null ? "" : url;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int dot = path.lastIndexOf('.');
        if (dot > 0 && dot > path.lastIndexOf('/')) {
            String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.matches("[a-z0-9]{1,5}")) {
                return "." + ext;
            }
        }
        return ".img";
    }

    /** Fetches one image with the same client settings the page itself was fetched with. */
    private static Download download(String url) throws IOException {
        var resp = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .ignoreContentType(true)
                .maxBodySize(MAX_IMAGE_BYTES)
                .execute();
        return new Download(resp.bodyAsBytes(), resp.contentType());
    }

    /**
     * One block element's text as inline Markdown, keeping its links: {@code <a href>} becomes
     * {@code [text](url)} with the URL resolved against the page. Every other inline element
     * contributes its text only.
     *
     * <p>Links are kept because an imported page is read as a reference document, where "consult
     * the NIH Genomic Data Sharing Policy" without the URL has lost the thing it was pointing at.
     * (The extractor this was ported from dropped them, but it fed a phrase list, not a document.)
     *
     * <p>{@code <img>} contributes nothing here: an image is emitted as its own block, by the
     * caller, so that it can be downloaded and renamed.
     */
    static String inlineMarkdown(Element block) {
        StringBuilder sb = new StringBuilder();
        appendInline(block, sb);
        return collapseWhitespace(sb.toString());
    }

    private static void appendInline(Node node, StringBuilder sb) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode text) {
                sb.append(text.text());
            } else if (child instanceof Element el) {
                switch (el.normalName()) {
                    case "img" -> { /* emitted as its own block, with the file downloaded */ }
                    case "br" -> sb.append(' ');
                    case "a" -> appendLink(el, sb);
                    default -> appendInline(el, sb);
                }
            }
        }
    }

    /** {@code [text](url)}, or the bare text when the anchor names no usable destination. */
    private static void appendLink(Element anchor, StringBuilder sb) {
        StringBuilder inner = new StringBuilder();
        appendInline(anchor, inner);
        String text = collapseWhitespace(inner.toString());
        if (text.isEmpty()) {
            return;
        }
        // Tested on the raw attribute, not the resolved one: absUrl() turns "#top" into the page's
        // own URL plus a fragment, which would make an in-page jump look like a real destination.
        String raw = anchor.attr("href").strip();
        String href = anchor.absUrl("href");
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("javascript:") || href.isBlank()) {
            sb.append(text);
            return;
        }
        sb.append('[').append(text.replace("[", "\\[").replace("]", "\\]"))
          .append("](").append(markdownUrl(href)).append(')');
    }

    /** A URL safe inside {@code (...)}: angle-bracketed when it holds a space or a parenthesis. */
    private static String markdownUrl(String href) {
        boolean needsBrackets = href.indexOf(' ') >= 0 || href.indexOf('(') >= 0 || href.indexOf(')') >= 0;
        return needsBrackets ? "<" + href + ">" : href;
    }

    private static String collapseWhitespace(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    private static void append(StringBuilder body, String block) {
        if (body.length() > 0) body.append("\n\n");
        body.append(block);
    }

    /** 1..6 for {@code h1}..{@code h6}, 0 for anything else. */
    private static int headingLevel(String tagName) {
        if (tagName.length() == 2 && tagName.charAt(0) == 'h') {
            int level = tagName.charAt(1) - '0';
            if (level >= 1 && level <= 6) return level;
        }
        return 0;
    }

    /** The verbatim content of {@code <meta property="og:...">} (or {@code name=}), or empty. */
    private static String metaContent(Document doc, String property) {
        Element el = metaEl(doc, property);
        return el == null ? "" : el.attr("content");
    }

    /** The content of an {@code og:*} meta resolved to an absolute URL, or empty. */
    private static String metaUrl(Document doc, String property) {
        Element el = metaEl(doc, property);
        if (el == null) {
            return "";
        }
        String abs = el.absUrl("content");
        return abs.isBlank() ? el.attr("content") : abs;
    }

    private static Element metaEl(Document doc, String property) {
        Element el = doc.selectFirst("meta[property=" + property + "]");
        return el != null ? el : doc.selectFirst("meta[name=" + property + "]");
    }

    private static Element firstPresent(Document doc, String... selectors) {
        for (String sel : selectors) {
            Elements found = doc.select(sel);
            if (!found.isEmpty()) {
                return found.first();
            }
        }
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }

    private static String yamlQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
