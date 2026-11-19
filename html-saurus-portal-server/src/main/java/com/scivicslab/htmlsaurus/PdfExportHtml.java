package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * @param plan     what the PDF holds
     * @param source   where the chosen directory is, shown on the cover, for example {@code doc_AASG1/docs/AAS-G1}
     * @param staticDir the doc project's {@code static-html/}
     * @param pages    the page each part starts on, by part number; empty on the first printing, when the table of
     *                 contents shows {@code 000}
     */
    static String renderCombinedHtml(PdfExportPlan plan, String source, Path staticDir, Map<Integer, Integer> pages) throws IOException {
        Map<String, String> anchorOfHref = new HashMap<>();
        for (PdfExportPlan.Part p : plan.parts()) {
            if (p.href() != null) anchorOfHref.putIfAbsent(normalize(p.href()), p.anchor());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"ja\" data-theme=\"").append(THEME).append("\">\n<head>\n<meta charset=\"UTF-8\">\n");
        sb.append("<title>").append(esc(plan.title())).append("</title>\n");
        sb.append("""
            <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Sans+JP:wght@400;500;700&display=swap">
            <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
            """);
        sb.append("<style>\n").append(pageCss()).append("\n</style>\n");
        sb.append("<style>\n").append(HttpUtils.themeVariables()).append(HttpUtils.docThemeMapping()).append("\n</style>\n");
        sb.append("<style>\n").append(PRINT_CSS).append("\n</style>\n");
        sb.append("""
            <script src="https://cdn.jsdelivr.net/npm/mermaid@11.13.0/dist/mermaid.min.js"></script>
            <script>mermaid.initialize({startOnLoad: true});</script>
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"
              onload="renderMathInElement(document.body, {delimiters: [
                {left:'$$',right:'$$',display:true},{left:'$',right:'$',display:false}]});"></script>
            </head>
            <body>
            """);

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
        for (PdfExportPlan.Part p : plan.parts()) {
            // A directory's heading and the part right after it (its first document or first subdirectory) share a page;
            // every other part starts a new one.
            boolean sharesPage = previous != null && previous.folder() && previous.href() == null && p.level() == previous.level() + 1;
            int h = Math.min(p.level(), 6);
            sb.append("<section class=\"hs-part").append(sharesPage ? "" : " hs-break").append("\" id=\"").append(p.anchor()).append("\">\n");
            sb.append("<h").append(h).append(" class=\"hs-part-title").append(p.folder() ? " hs-folder-title" : "").append("\">")
              .append(esc(p.title())).append("<span class=\"hs-marker\">").append(p.marker()).append("</span></h").append(h).append(">\n");
            // Wrapped in <main> so the page's own "main ..." styles apply as they do on screen.
            if (p.href() != null) sb.append("<main class=\"hs-main\">\n").append(bodyOfPage(staticDir, p, anchorOfHref)).append("</main>\n");
            sb.append("</section>\n");
            previous = p;
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    /** One page's body, ready to sit under its part's heading. */
    static String bodyOfPage(Path staticDir, PdfExportPlan.Part part, Map<String, String> anchorOfHref) throws IOException {
        Path file = builtPageFile(staticDir, part.href());
        if (!Files.isRegularFile(file)) {
            return "<p class=\"hs-missing\">This page has not been built: " + esc(part.href()) + "</p>\n";
        }
        Document doc = Jsoup.parse(file.toFile(), StandardCharsets.UTF_8.name());
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
            String src = img.attr("src");
            if (src.startsWith("data:") || src.startsWith("http://") || src.startsWith("https://")) continue;
            try {
                Path target = src.startsWith("/") ? staticDir.resolve(src.substring(1))
                        : Path.of(pageUri.resolve(src)).normalize();
                img.attr("src", target.toUri().toString());
            } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
                // A src that is not a file path stays as it is.
            }
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
        return main.html() + "\n";
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
            return normalize(resolved.getPath());
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
        .hs-marker { font-size: 2px; color: #fff; }
        .hs-cover { padding-top: 35vh; text-align: center; }
        .hs-cover-title { font-size: 28pt; font-weight: 700; margin-bottom: 18pt; }
        .hs-cover-source { font-size: 11pt; color: #57606a; font-family: monospace; margin-bottom: 6pt; }
        .hs-cover-count { font-size: 11pt; color: #57606a; }
        .hs-toc-title { font-size: 20pt; font-weight: 700; margin-bottom: 14pt; }
        .hs-toc-line { display: flex; align-items: baseline; font-size: 10.5pt; line-height: 1.7; }
        .hs-toc-line a { color: inherit !important; text-decoration: none; }
        .hs-toc-line::after { content: ""; }
        .hs-toc-line a { flex: 0 1 auto; }
        .hs-toc-page { margin-left: auto; padding-left: 12pt; font-variant-numeric: tabular-nums; }
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
