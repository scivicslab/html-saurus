package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * The one HTML document a PDF export prints: a cover, a table of contents and the body of every planned page, taken
 * from the pages html-saurus has already built under {@code static-html/} (ExportToPdf_261119_oo01).
 *
 * <p>A page's body is its {@code <main>} without the copy bar, the source path, the last-updated line and the links to
 * the previous and next page. Its headings move down by the depth of the document, its ids get a prefix of their own
 * so two documents' "problem-definition" do not collide, its images point at the files they were built from, and a
 * link to another document of the same PDF becomes a link inside the PDF.</p>
 */
final class PdfExportHtml {

    private PdfExportHtml() {}

    /** The theme a printed page uses: the light one, whatever the reader's browser chose. */
    static final String THEME = "light-clean";

    /**
     * The body of every part, read from the built pages once, so that the combined HTML can be written again with other
     * page numbers in its table of contents without reading the site again.
     *
     * @param plan   what the PDF holds
     * @param source where the chosen directory is, shown on the cover, for example {@code doc_AASG1/docs/AAS-G1}
     * @param bodies the body of each part in the order of {@code plan.parts()}; {@code null} for a part with no page
     */
    record Prepared(PdfExportPlan plan, String source, List<String> bodies) {

    /**
     * @param pages the page each part starts on, by part number; empty on the first printing, when the table of
     *              contents shows {@code 000}
     * @return the combined HTML: a cover, the table of contents, and the parts
     */
    String render(Map<Integer, Integer> pages) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"ja\" data-theme=\"").append(THEME).append("\">\n<head>\n<meta charset=\"UTF-8\">\n");
        sb.append("<title>").append(esc(plan.title())).append("</title>\n");
        sb.append("<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Noto+Sans+JP:wght@400;500;700&display=swap\">\n");
        // The tags that draw formulas and diagrams are the page's own, so print and screen draw them the same way.
        sb.append(PageRenderer.KATEX_STYLESHEET);
        sb.append("<style>\n").append(pageCss()).append("\n</style>\n");
        sb.append("<style>\n").append(HttpUtils.themeVariables()).append(HttpUtils.docThemeMapping()).append("\n</style>\n");
        sb.append("<style>\n").append(PRINT_CSS).append("\n</style>\n");
        sb.append(PageScripts.diagramAndMathScripts(""));
        sb.append("</head>\n<body>\n");

        // Cover
        sb.append("<section class=\"hs-cover\"><div class=\"hs-cover-title\">").append(esc(plan.title()))
          .append("</div><div class=\"hs-cover-source\">").append(esc(source)).append("</div><div class=\"hs-cover-count\">")
          .append(plan.documentCount()).append(plan.documentCount() == 1 ? " document" : " documents").append("</div></section>\n");

        // Table of contents: one line per part, the same lines on both printings so the pages do not move.
        sb.append("<nav class=\"hs-toc hs-break\"><div class=\"hs-toc-title\">Contents</div>\n");
        for (PdfExportPlan.Part p : plan.parts()) {
            Integer page = pages.get(p.number());
            sb.append("<div class=\"hs-toc-line hs-toc-l").append(Math.min(p.level(), 6)).append(p.folder() ? " hs-toc-folder" : "")
              .append("\"><a href=\"#").append(p.anchor()).append("\">").append(esc(p.title())).append("</a><span class=\"hs-toc-page\">")
              .append(page == null ? "000" : page.toString()).append("</span></div>\n");
        }
        sb.append("</nav>\n");

        // Body
        PdfExportPlan.Part previous = null;
        List<PdfExportPlan.Part> parts = plan.parts();
        for (int index = 0; index < parts.size(); index++) {
            PdfExportPlan.Part p = parts.get(index);
            // A directory's heading and the part right after it (its first document or first subdirectory) share a page;
            // every other part starts a new one.
            boolean sharesPage = previous != null && previous.folder() && previous.href() == null && p.level() == previous.level() + 1;
            int h = Math.min(p.level(), 6);
            sb.append("<section class=\"hs-part").append(sharesPage ? "" : " hs-break").append("\" id=\"").append(p.anchor()).append("\">\n");
            sb.append("<h").append(h).append(" class=\"hs-part-title").append(p.folder() ? " hs-folder-title" : "").append("\">")
              .append(esc(p.title())).append("</h").append(h).append(">\n");
            // Wrapped in <main> so the page's own "main ..." styles apply as they do on screen.
            if (bodies.get(index) != null) sb.append("<main class=\"hs-main\">\n").append(bodies.get(index)).append("</main>\n");
            sb.append("</section>\n");
            previous = p;
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }
    }

    /**
     * Reads the body of every part of a plan from the built pages.
     *
     * @param plan      what the PDF holds
     * @param source    where the chosen directory is, shown on the cover
     * @param staticDir the doc project's {@code static-html/}
     * @param imagesDirectory where the images the pages show are copied to; the combined HTML points at the copies, so a
     *                        rebuild of the site while Chrome prints does not take the images away
     */
    static Prepared prepare(PdfExportPlan plan, String source, Path staticDir, Path imagesDirectory) throws IOException {
        Map<String, String> anchorOfHref = new HashMap<>();
        for (PdfExportPlan.Part p : plan.parts()) {
            if (p.href() != null) anchorOfHref.putIfAbsent(normalize(p.href()), p.anchor());
        }
        List<String> bodies = new ArrayList<>();
        for (PdfExportPlan.Part p : plan.parts()) bodies.add(p.href() == null ? null : bodyOfPage(staticDir, p, anchorOfHref, imagesDirectory));
        return new Prepared(plan, source, bodies);
    }

    /** @return the combined HTML of a plan, read from the built pages and written with the given page numbers */
    static String renderCombinedHtml(PdfExportPlan plan, String source, Path staticDir, Path imagesDirectory, Map<Integer, Integer> pages) throws IOException {
        return prepare(plan, source, staticDir, imagesDirectory).render(pages);
    }

    /**
     * One page's body, ready to sit under its part's heading: only what a document is made of stays (see {@link #SAFE_ELEMENTS}),
     * and the images are copies in {@code imagesDirectory}.
     */
    static String bodyOfPage(Path staticDir, PdfExportPlan.Part part, Map<String, String> anchorOfHref, Path imagesDirectory) throws IOException {
        Path file = builtPageFile(staticDir, part.href());
        if (!Files.isRegularFile(file)) {
            return "<p class=\"hs-missing\">This page has not been built: " + esc(part.href()) + "</p>\n";
        }
        Document doc = Jsoup.parse(file.toFile(), StandardCharsets.UTF_8.name());
        // The page's text is kept as it is: pretty-printing turns the line breaks of a Mermaid diagram into spaces.
        doc.outputSettings().prettyPrint(false);
        Element main = doc.selectFirst("main");
        if (main == null) return "";
        main.select(".copy-bar, .source-footer, .page-last-updated, .page-nav, script").remove();
        Element h1 = main.selectFirst("h1");
        if (h1 != null) h1.remove();

        String idPrefix = "d" + part.number() + "-";
        for (Element e : main.select("[id]")) e.attr("id", idPrefix + e.id());

        // The document's title is at its part's level, so its own h2 goes one level below that.
        int shift = part.level() - 1;
        for (Element e : main.select("h2, h3, h4, h5, h6")) {
            int level = Math.min(6, Integer.parseInt(e.tagName().substring(1)) + shift);
            e.tagName("h" + level);
        }

        URI pageUri = file.toUri();
        for (Element img : main.select("img[src], source[src]")) {
            String url = siteImageUrl(staticDir, pageUri, img.attr("src"));
            if (url != null && url.startsWith("file:")) url = copyOfSiteImage(staticDir, Path.of(URI.create(url)), imagesDirectory);
            if (url == null) img.remove();
            else img.attr("src", url);
        }
        for (Element a : main.select("a[href]")) {
            String href = a.attr("href");
            if (href.startsWith("#")) {
                a.attr("href", "#" + idPrefix + href.substring(1));
            } else if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
                // stays as it is
            } else {
                String target = targetPath(part.href(), href);
                String anchor = target == null ? null : anchorOfHref.get(target);
                if (anchor != null) a.attr("href", "#" + anchor);
                else a.unwrap();
            }
        }
        for (Element e : main.getAllElements()) {
            if (e.hasAttr("style") && !isHarmlessStyle(e.attr("style"))) e.removeAttr("style");
            if (e.tagName().equals("input") && !"checkbox".equalsIgnoreCase(e.attr("type"))) e.remove();
        }
        // What is allowed is listed; whatever else a page carries is dropped, text and all for script and style.
        return Jsoup.clean(main.html(), "", SAFELIST, new Document.OutputSettings().prettyPrint(false)) + "\n";
    }

    /** The elements a document's page may keep in a PDF. Nothing here loads another file but {@code img}. */
    static final List<String> SAFE_ELEMENTS = List.of("a", "abbr", "article", "aside", "b", "bdi", "bdo", "blockquote", "br", "caption", "cite",
            "code", "col", "colgroup", "dd", "del", "details", "dfn", "div", "dl", "dt", "em", "figcaption", "figure", "footer", "h1", "h2",
            "h3", "h4", "h5", "h6", "header", "hr", "i", "img", "input", "ins", "kbd", "li", "mark", "ol", "p", "pre", "q", "rp", "rt", "ruby",
            "s", "samp", "section", "small", "span", "strong", "sub", "summary", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "time",
            "tr", "u", "ul", "var", "wbr");

    private static final org.jsoup.safety.Safelist SAFELIST = documentSafelist();

    private static org.jsoup.safety.Safelist documentSafelist() {
        org.jsoup.safety.Safelist list = new org.jsoup.safety.Safelist();
        list.addTags(SAFE_ELEMENTS.toArray(new String[0]));
        list.addAttributes(":all", "class", "id", "title", "lang", "dir", "style");
        list.addAttributes("a", "href");
        list.addProtocols("a", "href", "http", "https", "mailto", "#");
        list.addAttributes("img", "src", "alt", "width", "height");
        list.addProtocols("img", "src", "http", "https", "data", "file");
        list.addAttributes("td", "colspan", "rowspan", "align", "valign");
        list.addAttributes("th", "colspan", "rowspan", "scope", "align", "valign");
        list.addAttributes("col", "span");
        list.addAttributes("colgroup", "span");
        list.addAttributes("ol", "start", "type", "reversed");
        list.addAttributes("li", "value");
        list.addAttributes("details", "open");
        list.addAttributes("time", "datetime");
        list.addAttributes("input", "type", "checked", "disabled");
        list.preserveRelativeLinks(true);
        return list;
    }

    /** @return false for a style that could load a file ({@code url()}, {@code image-set()}, {@code @import}) or hide one in an escape */
    static boolean isHarmlessStyle(String style) {
        String squashed = style.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        return !(squashed.contains("url(") || squashed.contains("image-set(") || squashed.contains("@import")
                || squashed.contains("expression(") || squashed.contains("\\") || squashed.contains("/*"));
    }

    /**
     * Copies an image of the site into the images directory, keeping its place under {@code static-html/}.
     *
     * @return the {@code file:} URL of the copy; {@code null} when the file cannot be copied (it is not there)
     */
    private static String copyOfSiteImage(Path staticDir, Path image, Path imagesDirectory) {
        try {
            Path copy = imagesDirectory.resolve(staticDir.toAbsolutePath().normalize().relativize(image));
            Files.createDirectories(copy.getParent());
            Files.copy(image, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return copy.toUri().toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Decides what an image's {@code src} becomes in the combined HTML, which is printed from a file URL.
     *
     * @return the {@code src} for the combined HTML: a web or {@code data:} URL as it is, a file of the site as a {@code file:}
     *         URL; {@code null} when the image is another kind of URL, or a file outside {@code static-html/}
     */
    static String siteImageUrl(Path staticDir, URI pageUri, String src) {
        String trimmed = src.strip();
        if (trimmed.startsWith("data:") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed;
        if (trimmed.startsWith("//")) return null;
        try {
            URI parsed = new URI(trimmed.replace(" ", "%20"));
            if (parsed.getScheme() != null) return null;
            Path root = staticDir.toAbsolutePath().normalize();
            Path target = trimmed.startsWith("/") ? root.resolve(parsed.getPath().substring(1))
                    : Path.of(pageUri.resolve(new URI(null, null, parsed.getPath(), null)));
            target = target.toAbsolutePath().normalize();
            return target.startsWith(root) ? target.toUri().toString() : null;
        } catch (java.net.URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return null;
        }
    }

    /** How many math expressions {@link #mathExpressions} returns at most. */
    static final int MATH_SAMPLES = 5;

    /**
     * Finds math the page's script is expected to turn into formulas: text between {@code $} or {@code $$} outside code.
     * If one of them is still in the printed PDF as it was written, the script did not run.
     *
     * @param html the combined HTML
     * @return at most {@value #MATH_SAMPLES} expressions, each with its delimiters
     */
    static List<String> mathExpressions(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("pre, code, script, style").remove();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\$\\$[^$]+?\\$\\$|\\$[^$\\s][^$]{0,78}?\\$").matcher(doc.body().wholeText());
        List<String> found = new ArrayList<>();
        while (found.size() < MATH_SAMPLES && matcher.find()) found.add(matcher.group());
        return found;
    }

    /** @return the built file of a page: {@code static-html/<href>/index.html} */
    static Path builtPageFile(Path staticDir, String href) {
        String rel = href.replaceAll("^/+", "");
        return rel.isEmpty() ? staticDir.resolve("index.html") : staticDir.resolve(rel).resolve("index.html");
    }

    /** @return the site path a relative link of the page {@code pageHref} points at, without its fragment */
    static String targetPath(String pageHref, String link) {
        try {
            String noFragment = link.contains("#") ? link.substring(0, link.indexOf('#')) : link;
            if (noFragment.isEmpty()) return null;
            URI resolved = URI.create("http://site" + pageHref).resolve(noFragment);
            return resolved.getPath() == null ? null : normalize(resolved.getPath());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalize(String path) {
        String p = path.endsWith("index.html") ? path.substring(0, path.length() - "index.html".length()) : path;
        return p.endsWith("/") ? p : p + "/";
    }

    private static String pageCss() throws IOException {
        try (InputStream in = PageRenderer.class.getResourceAsStream("page.css")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** What printing needs on top of a page's own CSS. */
    static final String PRINT_CSS = """
        @page { size: A4; margin: 18mm 16mm 20mm 16mm; }
        html, body { background: #fff !important; color: #1f2328 !important; }
        body { display: block !important; margin: 0 !important; padding: 0 !important; max-width: none !important;
               font-family: 'Noto Sans JP', 'Noto Sans CJK JP', sans-serif; }
        .hs-break { break-before: page; }
        main.hs-main { display: block !important; margin: 0 !important; padding: 0 !important; max-width: none !important;
                       width: auto !important; grid-column: auto !important; }
        .hs-part { margin: 0; padding: 0; }
        .hs-cover { padding-top: 35vh; text-align: center; }
        .hs-cover-title { font-size: 28pt; font-weight: 700; margin-bottom: 18pt; }
        .hs-cover-source { font-size: 11pt; color: #57606a; font-family: monospace; margin-bottom: 6pt; }
        .hs-cover-count { font-size: 11pt; color: #57606a; }
        .hs-toc-title { font-size: 20pt; font-weight: 700; margin-bottom: 14pt; }
        .hs-toc-line { display: flex; align-items: baseline; font-size: 10.5pt; line-height: 1.7; }
        .hs-toc-line a { color: inherit !important; text-decoration: none; }
        .hs-toc-line::after { content: ""; }
        .hs-toc-line a { flex: 0 1 auto; }
        .hs-toc-page { margin-left: auto; padding-left: 12pt; min-width: 4em; text-align: right; font-variant-numeric: tabular-nums; }
        .hs-toc-folder { font-weight: 700; margin-top: 4pt; }
        .hs-toc-l2 { padding-left: 1.5em; } .hs-toc-l3 { padding-left: 3em; } .hs-toc-l4 { padding-left: 4.5em; }
        .hs-toc-l5 { padding-left: 6em; } .hs-toc-l6 { padding-left: 7.5em; }
        .hs-part-title { border-bottom: 1px solid #d0d7de; padding-bottom: 4pt; }
        pre, pre code { white-space: pre-wrap !important; word-break: break-all; overflow: visible !important; }
        table { break-inside: auto; } tr, img, pre, .katex-display { break-inside: avoid; }
        img { max-width: 100% !important; height: auto; }
        .hs-missing { color: #cf222e; }
        """;
}
