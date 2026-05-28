package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders complete HTML pages from converted Markdown content, sidebar, and TOC.
 * Produces self-contained 3-column layout: navbar, sidebar, main content, right-side TOC.
 */
class PageRenderer {

    private static final String PAGE_CSS = loadResource("page.css");

    // Top navbar: show this many primary sections inline; the rest collapse into a "More" dropdown.
    private static final int NAV_PRIMARY_ITEMS = 5;

    private static final Pattern NUM_PREFIX = Pattern.compile("^(\\d+)_(.*)$");

    private static final Pattern HEADING_PATTERN = Pattern.compile(
        "<h([23])\\s[^>]*id=\"([^\"]+)\"[^>]*>(.*?)</h[23]>", Pattern.DOTALL);

    private static final String RSS_ICON =
        "<svg width=\"13\" height=\"13\" viewBox=\"0 0 10 10\" fill=\"white\" xmlns=\"http://www.w3.org/2000/svg\">"
        + "<circle cx=\"1.5\" cy=\"8.5\" r=\"1.5\"/>"
        + "<path d=\"M0 5.5a4.5 4.5 0 0 1 4.5 4.5H3A3 3 0 0 0 0 7z\"/>"
        + "<path d=\"M0 2a8 8 0 0 1 8 8H6.5A6.5 6.5 0 0 0 0 3.5z\"/>"
        + "</svg>";

    private static final String JSON_ICON =
        "<span style=\"font-family:monospace;font-size:0.85em;letter-spacing:-0.05em;font-weight:900\">{}</span>";

    private final boolean production;
    private final String siteName;
    private final String customCss;
    private final String customHeader;
    private final String customFooter;
    private final String customTocFooter;
    private final String faviconDataUrl;
    private final String logoDataUrl;
    private final String logoAlt;
    private final String currentLocale;
    private final String defaultLocale;
    private final List<String> allLocales;
    private final String siteUrl;

    PageRenderer(boolean production, String siteName,
                 String customCss, String customHeader, String customFooter, String customTocFooter,
                 String faviconDataUrl, String logoDataUrl, String logoAlt,
                 String currentLocale, String defaultLocale, List<String> allLocales,
                 String siteUrl) {
        this.production = production;
        this.siteName = siteName;
        this.customCss = customCss;
        this.customHeader = customHeader;
        this.customFooter = customFooter;
        this.customTocFooter = customTocFooter;
        this.faviconDataUrl = faviconDataUrl;
        this.logoDataUrl = logoDataUrl;
        this.logoAlt = logoAlt;
        this.currentLocale = currentLocale;
        this.defaultLocale = defaultLocale;
        this.allLocales = allLocales != null ? allLocales : List.of();
        this.siteUrl = siteUrl;
    }

    /**
     * Renders a complete HTML page including header, sidebar, main content, right-side TOC,
     * copy buttons, theme switcher, search bar, and all supporting CSS/JS.
     */
    String renderPage(String title, String content, SiteNode root,
                      String prefix, String currentPath, String topSection,
                      String rawRelPath,
                      String prevHref, String prevLabel,
                      String nextHref, String nextLabel,
                      String lastUpdated) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            <!DOCTYPE html>
            <html lang="YADOC_LANG">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>%s</title>
              <link rel="icon" href="YADOC_FAVICON">
              <link rel="preconnect" href="https://fonts.googleapis.com">
              <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
              <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Sans+JP:wght@400;500;700&display=swap">
              <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css"
                integrity="sha384-nB0miv6/jRmo5UMMR1wu3Gz6NLsoTkbqJghGIsx//Rlm+ZU03BU6SQNC66uf4l5+"
                crossorigin="anonymous">
              <style>
            """.formatted(title));
        sb.append(PAGE_CSS);
        sb.append("              </style>\n");
        if (customCss != null) {
            sb.append("<style id=\"html-saurus-custom\">\n")
              .append(customCss)
              .append("\n</style>\n");
        }
        sb.append("""
              <script>(function(){
                var t=localStorage.getItem('md2html-theme');
                if(t&&t!=='default') document.documentElement.setAttribute('data-theme',t);
              })();</script>
            </head>
            <body>
            """);

        if (customHeader != null) sb.append(customHeader).append("\n");

        // Top navbar
        sb.append("<header>\n");
        sb.append("  <a class=\"site-title\" href=\"").append(prefix).append("\">");
        if (logoDataUrl != null) {
            sb.append("<img src=\"").append(logoDataUrl).append("\" alt=\"")
              .append(escapeHtml(logoAlt)).append("\" class=\"site-logo\">");
        }
        sb.append(escapeHtml(siteName)).append("</a>\n");
        sb.append("  <button class=\"menu-toggle\" id=\"menu-toggle\" aria-label=\"Toggle navigation\" aria-expanded=\"false\">\n");
        sb.append("    <svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\">\n");
        sb.append("      <line x1=\"3\" y1=\"6\" x2=\"21\" y2=\"6\"/><line x1=\"3\" y1=\"12\" x2=\"21\" y2=\"12\"/><line x1=\"3\" y1=\"18\" x2=\"21\" y2=\"18\"/>\n");
        sb.append("    </svg>\n");
        sb.append("  </button>\n");
        sb.append("  <nav class=\"top\">\n");
        List<SiteNode> dirSections = new ArrayList<>();
        for (SiteNode section : root.children()) {
            if (section.isDir()) dirSections.add(section);
        }
        int inlineCount = Math.min(NAV_PRIMARY_ITEMS, dirSections.size());
        for (int i = 0; i < inlineCount; i++) {
            SiteNode section = dirSections.get(i);
            boolean active = topSection != null &&
                topSection.equals("/" + dirNameForSection(section));
            String href = section.href() != null ? prefix + section.href().replaceFirst("^/", "") : "#";
            sb.append("    <a href=\"").append(href).append("\"")
              .append(active ? " class=\"active\"" : "").append(">")
              .append(escapeHtml(section.label())).append("</a>\n");
        }
        if (dirSections.size() > inlineCount) {
            // Overflow sections collapse into a "More" dropdown.
            StringBuilder menu = new StringBuilder();
            boolean moreActive = false;
            for (int i = inlineCount; i < dirSections.size(); i++) {
                SiteNode section = dirSections.get(i);
                boolean active = topSection != null &&
                    topSection.equals("/" + dirNameForSection(section));
                if (active) moreActive = true;
                String href = section.href() != null ? prefix + section.href().replaceFirst("^/", "") : "#";
                menu.append("      <a href=\"").append(href).append("\" class=\"nav-more-item")
                    .append(active ? " active" : "").append("\">")
                    .append(escapeHtml(section.label())).append("</a>\n");
            }
            sb.append("    <div class=\"nav-more-dropdown\">\n");
            sb.append("      <button class=\"nav-more-btn")
              .append(moreActive ? " active" : "")
              .append("\">More &#9662;</button>\n");
            sb.append("      <div class=\"nav-more-menu\">\n");
            sb.append(menu);
            sb.append("      </div>\n");
            sb.append("    </div>\n");
        }
        sb.append("  </nav>\n");
        if (!production) {
            sb.append("  <button id=\"rebuild-btn\" title=\"Rebuild this project\">&#x21BB; Rebuild</button>\n");
            sb.append("  <select id=\"theme-sel\">\n");
            sb.append("    <option value=\"default\">Default</option>\n");
            sb.append("    <option value=\"warm\">Warm</option>\n");
            sb.append("    <option value=\"blue\">Blue</option>\n");
            sb.append("    <option value=\"green\">Green</option>\n");
            sb.append("    <option value=\"red\">Red</option>\n");
            sb.append("  </select>\n");
        }
        boolean isNonDefaultLocale = currentLocale != null && !currentLocale.equals(defaultLocale);
        String siteRootPrefix = isNonDefaultLocale ? "../" + prefix : prefix;

        if (allLocales.size() > 1) {
            String basePath;
            if (currentLocale == null || currentLocale.equals(defaultLocale)) {
                basePath = currentPath;
            } else {
                String stripped = currentPath.replaceFirst("^/" + currentLocale + "(/|$)", "/");
                basePath = stripped.isEmpty() ? "/" : stripped;
            }

            String activeLabel = localeLabel(currentLocale != null ? currentLocale : defaultLocale);
            sb.append("  <div class=\"lang-dropdown\">\n");
            sb.append("    <button class=\"lang-btn\">").append(escapeHtml(activeLabel))
              .append(" &#9662;</button>\n");
            sb.append("    <div class=\"lang-menu\">\n");
            for (String locale : allLocales) {
                String targetPath = locale.equals(defaultLocale)
                    ? basePath
                    : "/" + locale + (basePath.startsWith("/") ? basePath : "/" + basePath);
                String lhref = siteRootPrefix + targetPath.replaceFirst("^/", "");
                boolean isCurrent = locale.equals(currentLocale)
                    || (currentLocale == null && locale.equals(defaultLocale));
                sb.append("      <a href=\"").append(lhref).append("\" class=\"lang-item")
                  .append(isCurrent ? " active" : "").append("\">")
                  .append(escapeHtml(localeLabel(locale))).append("</a>\n");
            }
            sb.append("    </div>\n  </div>\n");
        }
        sb.append("  <div id=\"search-wrap\">\n");
        sb.append("    <input id=\"search-input\" type=\"search\" placeholder=\"Search...\" autocomplete=\"off\">\n");
        sb.append("    <div id=\"search-results\"></div>\n");
        sb.append("  </div>\n");
        sb.append("</header>\n");
        sb.append("<div class=\"sidebar-overlay\" id=\"sidebar-overlay\"></div>\n");

        sb.append("<div class=\"content-wrap\">\n");

        sb.append("<nav class=\"side\" id=\"sidebar\">\n");
        sb.append("  <div class=\"mobile-top-nav\">\n");
        for (SiteNode section : root.children()) {
            if (!section.isDir()) continue;
            boolean active = topSection != null && topSection.equals("/" + dirNameForSection(section));
            String href = section.href() != null ? prefix + section.href().replaceFirst("^/", "") : "#";
            sb.append("    <a href=\"").append(href).append("\"")
              .append(active ? " class=\"mob-active\"" : "").append(">")
              .append(escapeHtml(section.label())).append("</a>\n");
        }
        if (allLocales.size() > 1) {
            sb.append("    <div class=\"mobile-lang\">\n");
            for (String locale : allLocales) {
                String basePath2 = (currentLocale == null || currentLocale.equals(defaultLocale))
                    ? currentPath
                    : currentPath.replaceFirst("^/" + currentLocale + "(/|$)", "/");
                if (basePath2.isEmpty()) basePath2 = "/";
                String targetPath = locale.equals(defaultLocale)
                    ? basePath2
                    : "/" + locale + (basePath2.startsWith("/") ? basePath2 : "/" + basePath2);
                String lhref = siteRootPrefix + targetPath.replaceFirst("^/", "");
                boolean isCurrent = locale.equals(currentLocale)
                    || (currentLocale == null && locale.equals(defaultLocale));
                sb.append("      <a href=\"").append(lhref).append("\" class=\"mob-lang-item")
                  .append(isCurrent ? " mob-active" : "").append("\">")
                  .append(escapeHtml(localeLabel(locale))).append("</a>\n");
            }
            sb.append("    </div>\n");
        }
        sb.append("  </div>\n");
        List<SiteNode> sidebarNodes = root.children();
        if (topSection != null) {
            for (SiteNode section : root.children()) {
                if (section.isDir() && topSection.equals("/" + dirNameForSection(section))) {
                    sidebarNodes = section.children();
                    break;
                }
            }
        }
        renderSidebar(sb, sidebarNodes, prefix, currentPath);
        sb.append("</nav>\n");

        sb.append("<main>\n<h1>").append(escapeHtml(title)).append("</h1>\n");
        if (!production) {
            String mdSourcePath = siteName + "/docs/" + rawRelPath;
            sb.append("<div class=\"copy-bar\">");
            sb.append("<button class=\"copy-btn\" id=\"copy-text-btn\" title=\"Copy as plain text\">&#x1F4CB; Text</button>");
            sb.append("<button class=\"copy-btn\" id=\"copy-md-btn\" title=\"Copy as Markdown\">&#x1F4DD; Markdown</button>");
            sb.append("<button class=\"copy-btn\" id=\"copy-path-btn\" data-path=\"").append(escapeHtml(mdSourcePath))
              .append("\" title=\"").append(escapeHtml(mdSourcePath)).append("\">&#x1F4C2; Path</button>");
            sb.append("</div>\n");
            sb.append(content);
            sb.append("<footer class=\"source-footer\">").append(escapeHtml(mdSourcePath)).append("</footer>\n");
        } else {
            sb.append(content);
        }

        if (lastUpdated != null) {
            sb.append("<div class=\"page-last-updated\">Last updated: ")
              .append(escapeHtml(lastUpdated)).append("</div>\n");
        }

        if (prevHref != null || nextHref != null) {
            sb.append("<nav class=\"page-nav\">\n");
            if (prevHref != null) {
                sb.append("  <a href=\"").append(prevHref).append("\" class=\"page-nav-prev\">\n");
                sb.append("    <span class=\"page-nav-label\">\u2190 Previous</span>\n");
                sb.append("    <span class=\"page-nav-title\">").append(escapeHtml(prevLabel)).append("</span>\n");
                sb.append("  </a>\n");
            }
            if (nextHref != null) {
                sb.append("  <a href=\"").append(nextHref).append("\" class=\"page-nav-next\">\n");
                sb.append("    <span class=\"page-nav-label\">Next \u2192</span>\n");
                sb.append("    <span class=\"page-nav-title\">").append(escapeHtml(nextLabel)).append("</span>\n");
                sb.append("  </a>\n");
            }
            sb.append("</nav>\n");
        }

        sb.append("</main>\n");

        sb.append(buildToc(content, buildTocFooter(prefix)));
        sb.append("</div>\n");

        sb.append(PageScripts.render());

        if (customFooter != null) sb.append(customFooter).append("\n");

        sb.append("</body></html>\n");

        String langAttr = (currentLocale != null) ? currentLocale
                        : (defaultLocale != null)  ? defaultLocale
                        : "ja";
        String faviconHref = faviconDataUrl != null ? faviconDataUrl : "data:,";
        return sb.toString()
            .replace("YADOC_SEARCH_URL", escapeJs(siteRootPrefix + "search"
                + (isNonDefaultLocale ? "?locale=" + currentLocale : "")))
            .replace("YADOC_BUILD_URL", escapeJs("/api/build/" + siteName))
            .replace("YADOC_PROJECT", escapeJs(siteName))
            .replace("YADOC_LANG", escapeHtml(langAttr))
            .replace("YADOC_FAVICON", faviconHref);
    }

    /** Recursively renders the left navigation sidebar as nested HTML lists with collapsible categories. */
    private void renderSidebar(StringBuilder sb, List<SiteNode> nodes, String prefix, String currentPath) {
        sb.append("<ul>\n");
        for (SiteNode node : nodes) {
            if (node.isDir()) {
                sb.append("<li>\n");
                sb.append("  <div class=\"cat-header\" data-cat=\"").append(escapeHtml(stableCatKey(node.href()))).append("\">");
                if (node.catLink() != null) {
                    String catAbsHref = prefix + node.catLink().replaceFirst("^/", "");
                    sb.append("<a href=\"").append(catAbsHref).append("\" class=\"cat-label\">")
                      .append(escapeHtml(node.label())).append("</a>");
                } else {
                    sb.append("<span class=\"cat-label\">").append(escapeHtml(node.label())).append("</span>");
                }
                sb.append("<span class=\"cat-arrow\">▶</span>")
                  .append("</div>\n");
                sb.append("  <div class=\"cat-children\">\n");
                renderSidebar(sb, node.children(), prefix, currentPath);
                sb.append("  </div>\n</li>\n");
            } else {
                boolean active = currentPath.equals(node.href());
                String href = prefix + node.href().replaceFirst("^/", "");
                sb.append("<li><a href=\"").append(href).append("\"")
                  .append(active ? " class=\"active\"" : "").append(">")
                  .append(escapeHtml(node.label())).append("</a></li>\n");
            }
        }
        sb.append("</ul>\n");
    }

    /**
     * Builds a right-side table of contents by extracting h2/h3 headings from the rendered HTML.
     */
    String buildToc(String contentHtml, String footer) {
        var matcher = HEADING_PATTERN.matcher(contentHtml);
        List<String[]> entries = new ArrayList<>();
        while (matcher.find()) {
            String level = matcher.group(1);
            String id = matcher.group(2);
            String text = matcher.group(3).replaceAll("<[^>]+>", "").trim();
            entries.add(new String[]{level, id, text});
        }
        if (entries.isEmpty() && footer.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<aside class=\"toc\">\n");
        if (!entries.isEmpty()) {
            sb.append("  <div class=\"toc-title\">On this page</div>\n");
            sb.append("  <ul>\n");
            for (String[] e : entries) {
                String cls = e[0].equals("3") ? " class=\"toc-h3\"" : "";
                sb.append("    <li").append(cls).append("><a href=\"#")
                  .append(escapeHtml(e[1])).append("\">")
                  .append(escapeHtml(e[2])).append("</a></li>\n");
            }
            sb.append("  </ul>\n");
        }
        if (!footer.isEmpty()) {
            sb.append(footer);
        }
        sb.append("</aside>\n");
        return sb.toString();
    }

    /**
     * Builds the HTML to inject at the bottom of the right-side TOC aside.
     * Uses custom toc footer if configured, otherwise auto-generates feed links if siteUrl is set.
     */
    String buildTocFooter(String prefix) {
        if (customTocFooter != null) return customTocFooter;
        if (siteUrl != null) {
            return "<div class=\"toc-feed-links\">\n"
                + "  <a href=\"" + prefix + "rss.xml\" class=\"feed-link rss-link\" title=\"RSS Feed\">"
                + RSS_ICON + " RSS</a>\n"
                + "  <a href=\"" + prefix + "feed.json\" class=\"feed-link json-link\" title=\"JSON Feed\">"
                + JSON_ICON + " JSON</a>\n"
                + "</div>\n";
        }
        return "";
    }

    // --- Static utility methods ---

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }

    /** Strips a leading numeric prefix (e.g., {@code 01_intro} → {@code intro}). */
    static String stripNumericPrefix(String name) {
        var m = NUM_PREFIX.matcher(name);
        return m.matches() ? m.group(2) : name;
    }

    /**
     * Strips numeric prefixes from every segment of a relative path.
     * E.g., {@code 010_history/020_intro/file.html} → {@code history/intro/file.html}
     */
    static String cleanRelPath(String relPath) {
        String[] segments = relPath.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(stripNumericPrefix(segments[i]));
        }
        return sb.toString();
    }

    static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * Derives a stable, mode-independent localStorage key for a sidebar category.
     */
    static String stableCatKey(String href) {
        if (href == null) return "";
        String k = href;
        if (k.startsWith("/")) k = k.substring(1);
        if (k.endsWith("/")) k = k.substring(0, k.length() - 1);
        if (k.endsWith(".html")) k = k.substring(0, k.length() - 5);
        return cleanRelPath(k);
    }

    /** Derives the directory-name segment for a top-level nav section from its href. */
    static String dirNameForSection(SiteNode section) {
        if (section.href() == null) return "";
        String h = section.href().replaceFirst("^/", "");
        int slash = h.indexOf('/');
        return slash >= 0 ? h.substring(0, slash) : h.replace(".html", "");
    }

    /** Returns a human-readable display label for a locale code. */
    static String localeLabel(String code) {
        if (code == null) return "";
        return switch (code) {
            case "ja" -> "日本語";
            case "en" -> "English";
            case "zh" -> "中文";
            case "ko" -> "한국어";
            case "fr" -> "Français";
            case "de" -> "Deutsch";
            case "es" -> "Español";
            default   -> code.toUpperCase();
        };
    }

    private static String loadResource(String name) {
        try (var is = PageRenderer.class.getResourceAsStream(name)) {
            if (is == null) throw new IllegalStateException("Missing resource: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
