package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Converts a Docusaurus-compatible {@code docs/} directory into a static HTML site.
 *
 * <p>Produces a self-contained set of HTML files with a left navigation sidebar,
 * top navbar, right-side table of contents, full-text search integration,
 * and copy-to-clipboard buttons (plain text and Markdown).
 *
 * <p>Supports CommonMark with GFM extensions (tables, strikethrough), heading anchors,
 * Docusaurus-style admonitions ({@code :::note}), Mermaid diagrams, KaTeX math,
 * and theme switching.
 */
public class SiteBuilder {

    private final Path docsDir;
    private final Path outDir;
    private final MarkdownConverter converter;
    private final NavTreeBuilder navBuilder;

    /** Page CSS loaded from {@code page.css} resource at class-load time. */
    private static final String PAGE_CSS = loadResource("page.css");

    /** Matches numeric prefix patterns like {@code 01_getting-started} for path utilities. */
    private static final Pattern NUM_PREFIX = Pattern.compile("^(\\d+)_(.*)$");

    private final String siteName;
    private final boolean production;
    /** Custom CSS loaded from {@code html-saurus.css} in the project root; null if absent. */
    private final String customCss;
    /** Custom header HTML injected at the top of {@code <body>}; null if absent. */
    private final String customHeader;
    /** Custom footer HTML injected before {@code </body>}; null if absent. */
    private final String customFooter;
    /** Data URL for the favicon, read from the project's static directory; null if not found. */
    private final String faviconDataUrl;
    /** Data URL for the navbar logo image; null if not configured. */
    private final String logoDataUrl;
    /** Alt text for the navbar logo image. */
    private final String logoAlt;
    /**
     * Locale code of this build (e.g., "ja", "en"), or null if i18n is not configured.
     * When equal to defaultLocale (or null), this is the default-language build served at the root path.
     */
    private final String currentLocale;
    /** Default locale from docusaurus.config, or null if i18n is not configured. */
    private final String defaultLocale;
    /** All available locale codes in display order (including currentLocale); empty if i18n is not configured. */
    private final List<String> allLocales;

    /**
     * Creates a SiteBuilder using the parent directory name as the site name (development mode, no i18n).
     *
     * @param docsDir source directory containing Markdown files
     * @param outDir  target directory for generated HTML
     */
    public SiteBuilder(Path docsDir, Path outDir) {
        this(docsDir, outDir, docsDir.getParent().getFileName().toString(), false, null, null, List.of());
    }

    /**
     * Creates a SiteBuilder with a production flag and no i18n.
     *
     * @param docsDir    source directory containing Markdown files
     * @param outDir     target directory for generated HTML
     * @param production {@code true} to omit copy buttons and source path footer
     */
    public SiteBuilder(Path docsDir, Path outDir, boolean production) {
        this(docsDir, outDir, docsDir.getParent().getFileName().toString(), production, null, null, List.of());
    }

    /**
     * Creates a SiteBuilder with locale information for the language switcher dropdown.
     *
     * @param docsDir       source directory containing Markdown files
     * @param outDir        target directory for generated HTML
     * @param production    {@code true} to omit copy buttons and source path footer
     * @param currentLocale locale code of this build (e.g., "ja"), or null if no i18n
     * @param defaultLocale default locale from docusaurus.config (e.g., "ja"), or null
     * @param allLocales    all available locale codes in display order; empty if no i18n
     */
    public SiteBuilder(Path docsDir, Path outDir, boolean production,
                       String currentLocale, String defaultLocale, List<String> allLocales) {
        this(docsDir, outDir, docsDir.getParent().getFileName().toString(),
             production, currentLocale, defaultLocale, allLocales);
    }

    /**
     * Full constructor.
     *
     * @param docsDir       source directory containing Markdown files
     * @param outDir        target directory for generated HTML
     * @param siteName      display name shown in the top navbar
     * @param production    {@code true} to omit copy buttons and source path footer
     * @param currentLocale locale code of this build (e.g., "ja"), or null if no i18n
     * @param defaultLocale default locale from docusaurus.config (e.g., "ja"), or null
     * @param allLocales    all available locale codes in display order; empty if no i18n
     */
    public SiteBuilder(Path docsDir, Path outDir, String siteName, boolean production,
                       String currentLocale, String defaultLocale, List<String> allLocales) {
        this.docsDir = docsDir;
        this.outDir = outDir;
        this.production = production;
        this.currentLocale = currentLocale;
        this.defaultLocale = defaultLocale;
        this.allLocales = allLocales != null ? allLocales : List.of();
        Path projectRoot = findProjectRoot(docsDir);
        // Read site name from i18n navbar.json or docusaurus.config; fall back to passed-in name
        String configSiteName = readSiteNameFromConfig(projectRoot, currentLocale);
        this.siteName = configSiteName != null ? configSiteName : siteName;
        this.customCss    = production ? readLocalized(projectRoot, "html-saurus.css",          currentLocale) : null;
        this.customHeader = production ? readLocalized(projectRoot, "html-saurus-header.html", currentLocale) : null;
        this.customFooter = production ? readLocalized(projectRoot, "html-saurus-footer.html", currentLocale) : null;
        this.faviconDataUrl = readFaviconDataUrl(projectRoot);
        String[] logoInfo = readLogoInfo(projectRoot);
        this.logoDataUrl = logoInfo[0];
        this.logoAlt     = logoInfo[1];
        this.converter = new MarkdownConverter();
        this.navBuilder = new NavTreeBuilder(docsDir, production, converter, currentLocale, defaultLocale);
    }

    /** Returns a human-readable display label for a locale code. */
    private static String localeLabel(String code) {
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

    /**
     * Builds the entire static site. Walks the docs directory, converts each Markdown file
     * to HTML, copies non-Markdown assets, and generates an {@code index.html} that redirects
     * to the first page.
     *
     * @throws IOException if file I/O fails
     */
    public void build() throws IOException {
        // Clean output directory before building, like Docusaurus yarn build does.
        if (Files.exists(outDir)) {
            Files.walkFileTree(outDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Files.createDirectories(outDir);

        SiteNode root = navBuilder.build();

        // Collect all source files; copy non-Markdown assets and create directories immediately.
        List<Path[]> mdFiles = new ArrayList<>(); // [mdFile, rel]
        Files.walkFileTree(docsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = docsDir.relativize(file);
                if (file.toString().endsWith(".md")) {
                    mdFiles.add(new Path[]{file, rel});
                } else {
                    Path dest = outDir.resolve(cleanRelPath(rel.toString().replace('\\', '/')));
                    Files.createDirectories(dest.getParent());
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(outDir.resolve(cleanRelPath(docsDir.relativize(dir).toString().replace('\\', '/'))));
                return FileVisitResult.CONTINUE;
            }
        });

        // Convert Markdown files in parallel; output directories already exist from above.
        mdFiles.parallelStream().forEach(pair -> {
            try {
                convertPage(pair[0], pair[1], root);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        // Generate index.html that redirects to the homepage.
        // A doc with "slug: /" in its frontmatter is the Docusaurus homepage; use its generated URL.
        // Fall back to the nav tree's first page when no such doc exists.
        String homeTarget = null;
        for (Path[] pair : mdFiles) {
            try {
                if (hasRootSlug(Files.readString(pair[0]))) {
                    homeTarget = hrefForRel(pair[1]);
                    break;
                }
            } catch (IOException ignored) {}
        }
        if (homeTarget == null) {
            String h = root.href();
            homeTarget = h != null ? (h.startsWith("/") ? h.substring(1) : h) : null;
        }
        String firstHref = homeTarget;
        if (firstHref != null && !firstHref.toLowerCase().startsWith("javascript:")) {
            String target = firstHref;
            String targetEsc = escapeHtml(target);
            String indexHtml = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<meta http-equiv=\"refresh\" content=\"0;url=" + targetEsc + "\">"
                + "<title>Redirecting...</title></head><body>"
                + "<p>Redirecting to <a href=\"" + targetEsc + "\">" + targetEsc + "</a>...</p>"
                + "</body></html>\n";
            Files.writeString(outDir.resolve("index.html"), indexHtml);
        }
    }

    /**
     * Converts a single Markdown file to a full HTML page with navbar, sidebar, and TOC.
     *
     * @param mdFile the source Markdown file
     * @param rel    relative path from the docs root
     * @param root   the site navigation tree
     * @throws IOException if file I/O fails
     */
    private void convertPage(Path mdFile, Path rel, SiteNode root) throws IOException {
        String source = Files.readString(mdFile);
        String[] fm = converter.parseFrontmatter(source);
        String title = fm[0].isBlank()
            ? stripNumericPrefix(stripExtension(mdFile.getFileName().toString())) : fm[0];
        String body = fm[1];
        String contentHtml = converter.convertMarkdown(body);

        // Detect same-name pattern: dir/dir.md (Docusaurus convention).
        // Covers both Pattern 1 (only .md, no subdirs) and Pattern 2 (same-name .md + subdirs).
        // In both cases the URL is based on the parent directory path, not the file path.
        boolean isSameName = false;
        if (rel.getNameCount() >= 2) {
            String fileBase = stripNumericPrefix(rel.getFileName().toString().replaceAll("\\.md$", ""));
            String parentBase = stripNumericPrefix(rel.getName(rel.getNameCount() - 2).toString());
            if (fileBase.equals(parentBase)) {
                isSameName = true;
            }
        }

        String cleanBase = isSameName
            ? cleanRelPath(rel.getParent().toString().replace('\\', '/'))
            : cleanRelPath(rel.toString().replace('\\', '/').replaceAll("\\.md$", ""));
        String relStr = production ? cleanBase + "/index.html" : cleanBase + ".html";
        Path outFile = outDir.resolve(relStr);
        Files.createDirectories(outFile.getParent());

        long depth = production ? (long) cleanBase.split("/").length
                                : (long) cleanBase.split("/").length - 1;
        String prefix = "../".repeat((int) depth);
        if (prefix.isEmpty()) prefix = "./";

        String currentPath = production ? "/" + cleanBase + "/" : "/" + cleanBase + ".html";

        // Determine which top-level section this page belongs to
        String topSection = rel.getNameCount() > 1
            ? "/" + stripNumericPrefix(rel.getName(0).toString()) : null;

        String rawRelPath = rel.toString().replace('\\', '/');
        String html = renderPage(title, contentHtml, root, prefix, currentPath, topSection, rawRelPath);
        Files.writeString(outFile, html);
        System.out.println("  " + outFile);
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

    /** Derives the directory-name segment for a top-level nav section from its href. */
    static String dirNameForSection(SiteNode section) {
        if (section.href() == null) return "";
        String h = section.href().replaceFirst("^/", "");
        int slash = h.indexOf('/');
        return slash >= 0 ? h.substring(0, slash) : h.replace(".html", "");
    }

    /**
     * Renders a complete HTML page including header, sidebar, main content, right-side TOC,
     * copy buttons, theme switcher, search bar, and all supporting CSS/JS.
     *
     * @param title       page title (shown in {@code <h1>} and {@code <title>})
     * @param content     HTML content rendered from Markdown
     * @param root        the site navigation tree
     * @param prefix      relative path prefix for resolving asset URLs (e.g., {@code "../"})
     * @param currentPath absolute URL path of the current page (for active-link highlighting)
     * @param topSection  the top-level section this page belongs to (for navbar highlighting)
     * @return the complete HTML string
     */
    private String renderPage(String title, String content, SiteNode root,
                               String prefix, String currentPath, String topSection,
                               String rawRelPath) {
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
        sb.append("  <a class=\"site-title\" href=\"").append(prefix).append("index.html\">");
        if (logoDataUrl != null) {
            sb.append("<img src=\"").append(logoDataUrl).append("\" alt=\"")
              .append(escapeHtml(logoAlt)).append("\" class=\"site-logo\">");
        }
        sb.append(escapeHtml(siteName)).append("</a>\n");
        sb.append("  <nav class=\"top\">\n");
        for (SiteNode section : root.children()) {
            if (!section.isDir()) continue; // skip top-level .md files like intro.md
            String sectionPath = "/" + section.label(); // not reliable; use href prefix instead
            // Determine if this section is active: currentPath starts with the section's subtree
            boolean active = topSection != null &&
                topSection.equals("/" + dirNameForSection(section));
            String href = section.href() != null ? prefix + section.href().replaceFirst("^/", "") : "#";
            sb.append("    <a href=\"").append(href).append("\"")
              .append(active ? " class=\"active\"" : "").append(">")
              .append(escapeHtml(section.label())).append("</a>\n");
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
        // Language switcher links and search must reach the SITE ROOT (above all locale dirs).
        // For the default locale, `prefix` already points to the site root.
        // For alternate locales, `prefix` points only to the locale root (e.g. /en/),
        // so we need one extra "../" to escape the locale directory.
        boolean isNonDefaultLocale = currentLocale != null && !currentLocale.equals(defaultLocale);
        String siteRootPrefix = isNonDefaultLocale ? "../" + prefix : prefix;

        if (allLocales.size() > 1) {
            // Compute the page path without the locale prefix (base path)
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
                String href = siteRootPrefix + targetPath.replaceFirst("^/", "");
                boolean isCurrent = locale.equals(currentLocale)
                    || (currentLocale == null && locale.equals(defaultLocale));
                sb.append("      <a href=\"").append(href).append("\" class=\"lang-item")
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

        // Content wrapper
        sb.append("<div class=\"content-wrap\">\n");

        // Sidebar: show only the active section's children, or all if no section
        sb.append("<nav class=\"side\">\n");
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

        // Main content
        sb.append("<main>\n<h1>").append(escapeHtml(title)).append("</h1>\n");
        if (!production) {
            String mdSourcePath = siteName + "/docs/" + rawRelPath;
            sb.append("<div class=\"copy-bar\">");
            sb.append("<button class=\"copy-btn\" id=\"copy-text-btn\" title=\"Copy as plain text\">&#x1F4CB; Text</button>");
            sb.append("<button class=\"copy-btn\" id=\"copy-md-btn\" title=\"Copy as Markdown\">&#x1F4DD; Markdown</button>");
            sb.append("<button class=\"copy-btn\" id=\"copy-path-btn\" data-path=\"").append(escapeHtml(mdSourcePath))
              .append("\" title=\"Copy file path\">&#x1F4C2; Path</button>");
            sb.append("</div>\n");
            sb.append(content);
            sb.append("<footer class=\"source-footer\">").append(escapeHtml(mdSourcePath)).append("</footer>\n");
        } else {
            sb.append(content);
        }
        sb.append("</main>\n");

        // Right-side TOC: extract h2/h3 headings from content HTML
        sb.append(buildToc(content));
        sb.append("</div>\n");

        sb.append(pageScripts());

        if (customFooter != null) sb.append(customFooter).append("\n");

        sb.append("</body></html>\n");

        String langAttr = (currentLocale != null) ? currentLocale
                        : (defaultLocale != null)  ? defaultLocale
                        : "ja";
        String faviconHref = faviconDataUrl != null ? faviconDataUrl : "data:,";
        return sb.toString()
            .replace("YADOC_SEARCH_URL", escapeJs(siteRootPrefix + "search"
                + (isNonDefaultLocale ? "?locale=" + currentLocale : "")))
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
                sb.append("  <div class=\"cat-header\" data-cat=\"").append(escapeHtml(node.href())).append("\">");
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

    /** Matches {@code <h2>} and {@code <h3>} elements with {@code id} attributes for TOC extraction. */
    private static final Pattern HEADING_PATTERN = Pattern.compile(
        "<h([23])\\s[^>]*id=\"([^\"]+)\"[^>]*>(.*?)</h[23]>", Pattern.DOTALL);

    /**
     * Builds a right-side table of contents by extracting h2/h3 headings from the rendered HTML.
     * Returns an empty string if no headings are found.
     *
     * @param contentHtml the rendered page content HTML
     * @return HTML string for the {@code <aside class="toc">} element
     */
    private String buildToc(String contentHtml) {
        var matcher = HEADING_PATTERN.matcher(contentHtml);
        List<String[]> entries = new ArrayList<>(); // [level, id, text]
        while (matcher.find()) {
            String level = matcher.group(1);
            String id = matcher.group(2);
            String text = matcher.group(3).replaceAll("<[^>]+>", "").trim(); // strip inner HTML tags
            entries.add(new String[]{level, id, text});
        }
        if (entries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<aside class=\"toc\">\n");
        sb.append("  <div class=\"toc-title\">On this page</div>\n");
        sb.append("  <ul>\n");
        for (String[] e : entries) {
            String cls = e[0].equals("3") ? " class=\"toc-h3\"" : "";
            sb.append("    <li").append(cls).append("><a href=\"#")
              .append(escapeHtml(e[1])).append("\">")
              .append(escapeHtml(e[2])).append("</a></li>\n");
        }
        sb.append("  </ul>\n");
        sb.append("</aside>\n");
        return sb.toString();
    }

    /**
     * Returns the inline JavaScript block embedded in each generated page.
     * Contains {@code YADOC_SEARCH_URL} and {@code YADOC_PROJECT} tokens that are
     * substituted by the caller before returning the final HTML.
     */
    private static String pageScripts() {
        return """
            <script src="https://cdn.jsdelivr.net/npm/mermaid@11.13.0/dist/mermaid.min.js"
              integrity="sha384-tI0sDqjGJcqrQ8e/XKiQGS+ee11v5knTNWx2goxMBxe4DO9U0uKlfxJtYB9ILZ4j"
              crossorigin="anonymous"></script>
            <script>mermaid.initialize({startOnLoad: true});</script>
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"
              integrity="sha384-7zkQWkzuo3B5mTepMUcHkMB5jZaolc2xDwL6VFqjFALcbeS9Ggm/Yr2r3Dy4lfFg"
              crossorigin="anonymous"></script>
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"
              integrity="sha384-43gviWU0YVjaDtb/GhzOouOXtZMP/7XUzwPTstBeZFe/+rCMvRwr4yROQP43s0Xk"
              crossorigin="anonymous"
              onload="renderMathInElement(document.body, {delimiters: [
                {left:'$$',right:'$$',display:true},{left:'$',right:'$',display:false}]});"></script>
            <script>
            document.querySelectorAll('.cat-header').forEach(function(header) {
              header.addEventListener('click', function() {
                var children = this.nextElementSibling;
                var arrow = this.querySelector('.cat-arrow');
                children.classList.toggle('open');
                var isOpen = children.classList.contains('open');
                arrow.textContent = isOpen ? '▼' : '▶';
                var key = header.dataset.cat;
                if (key) {
                  if (isOpen) localStorage.setItem('hs-cat:' + key, '1');
                  else localStorage.removeItem('hs-cat:' + key);
                }
              });
            });
            document.querySelectorAll('.cat-header[data-cat]').forEach(function(header) {
              if (localStorage.getItem('hs-cat:' + header.dataset.cat) === '1') {
                var children = header.nextElementSibling;
                var arrow = header.querySelector('.cat-arrow');
                children.classList.add('open');
                arrow.textContent = '▼';
              }
            });
            (function() {
              var sel = document.getElementById('theme-sel');
              if (!sel) return;
              var t = localStorage.getItem('md2html-theme') || 'default';
              sel.value = t;
              sel.addEventListener('change', function() {
                var val = this.value;
                localStorage.setItem('md2html-theme', val);
                if (val === 'default') document.documentElement.removeAttribute('data-theme');
                else document.documentElement.setAttribute('data-theme', val);
              });
            })();
            (function() {
              var input = document.getElementById('search-input');
              var results = document.getElementById('search-results');
              var timer = null;
              input.addEventListener('input', function() {
                clearTimeout(timer);
                var q = this.value.trim();
                if (!q) { results.classList.remove('open'); return; }
                timer = setTimeout(function() { doSearch(q); }, 300);
              });
              input.addEventListener('keydown', function(e) {
                if (e.key === 'Escape') { results.classList.remove('open'); input.blur(); }
              });
              document.addEventListener('click', function(e) {
                if (!document.getElementById('search-wrap').contains(e.target))
                  results.classList.remove('open');
              });
              (function() {
                var btn = document.getElementById('rebuild-btn');
                if (!btn) return;
                btn.addEventListener('click', function() {
                  btn.disabled = true; btn.textContent = 'Building\\u2026';
                  fetch('/api/build/YADOC_PROJECT', {method: 'POST'})
                    .then(function(r) { return r.json(); })
                    .then(function(j) {
                      btn.textContent = j.status === 'ok'
                        ? '\\u2713 Done (' + j.ms + 'ms)' : '\\u2717 Error';
                      setTimeout(function() {
                        btn.textContent = '\\u21BB Rebuild'; btn.disabled = false;
                      }, 3000);
                    })
                    .catch(function() {
                      btn.textContent = '\\u21BB Rebuild'; btn.disabled = false;
                    });
                });
              })();
              var SEARCH_URL = 'YADOC_SEARCH_URL';
              function doSearch(q) {
                var sep = SEARCH_URL.indexOf('?') >= 0 ? '&' : '?';
                fetch(SEARCH_URL + sep + 'q=' + encodeURIComponent(q))
                  .then(function(r) { return r.json(); })
                  .then(function(data) {
                    results.innerHTML = '';
                    if (!data.length) {
                      results.innerHTML = '<div class="sr-empty">No results.</div>';
                    } else {
                      data.forEach(function(item) {
                        var a = document.createElement('a');
                        a.className = 'sr-item'; a.href = item.path; a.target = '_blank';
                        a.innerHTML = '<div class="sr-title">' + esc(item.title) + '</div>' +
                                      '<div class="sr-breadcrumb">' + esc(breadcrumb(item.pagePath || item.path)) + '</div>' +
                                      '<div class="sr-summary">' + esc(item.summary) + '</div>';
                        results.appendChild(a);
                      });
                    }
                    results.classList.add('open');
                  })
                  .catch(function() {
                    results.innerHTML = '<div class="sr-empty">Search requires --serve mode.</div>';
                    results.classList.add('open');
                  });
              }
              function esc(s) { return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
              function breadcrumb(path) {
                var segs = (path||'').replace(/^\\//, '').split('/');
                return segs.slice(0, -1).map(function(seg) {
                  return seg.replace(/^\\d+_/, '');
                }).filter(function(s) { return s.length > 0; }).join(' \\u203a ');
              }
            })();
            // Copy buttons
            (function() {
              if (!document.getElementById('copy-text-btn')) return;
              function flash(btn, label) {
                btn.classList.add('copied');
                var orig = btn.innerHTML;
                btn.innerHTML = '&#x2713; Copied';
                setTimeout(function() { btn.classList.remove('copied'); btn.innerHTML = orig; }, 1500);
              }
              function getContent() {
                var main = document.querySelector('main');
                var clone = main.cloneNode(true);
                // Remove h1 and copy-bar from clone
                var h1 = clone.querySelector('h1');
                if (h1) h1.remove();
                var bar = clone.querySelector('.copy-bar');
                if (bar) bar.remove();
                return clone;
              }
              // Plain text copy
              document.getElementById('copy-text-btn').addEventListener('click', function() {
                var btn = this;
                var clone = getContent();
                var text = clone.innerText || clone.textContent;
                navigator.clipboard.writeText(text.trim()).then(function() { flash(btn); });
              });
              // Markdown copy
              document.getElementById('copy-md-btn').addEventListener('click', function() {
                var btn = this;
                var clone = getContent();
                var md = htmlToMd(clone);
                navigator.clipboard.writeText(md.trim()).then(function() { flash(btn); });
              });
              // Path copy
              document.getElementById('copy-path-btn').addEventListener('click', function() {
                var btn = this;
                navigator.clipboard.writeText(btn.dataset.path).then(function() { flash(btn); });
              });
              function htmlToMd(el) {
                var out = '';
                var children = el.childNodes;
                for (var i = 0; i < children.length; i++) {
                  var n = children[i];
                  if (n.nodeType === 3) { out += n.textContent; continue; }
                  if (n.nodeType !== 1) continue;
                  var tag = n.tagName;
                  if (tag === 'H2') { out += '\\n## ' + n.textContent.trim() + '\\n\\n'; }
                  else if (tag === 'H3') { out += '\\n### ' + n.textContent.trim() + '\\n\\n'; }
                  else if (tag === 'H4') { out += '\\n#### ' + n.textContent.trim() + '\\n\\n'; }
                  else if (tag === 'P') { out += mdInline(n) + '\\n\\n'; }
                  else if (tag === 'PRE') {
                    var code = n.querySelector('code');
                    var lang = '';
                    if (code && code.className) {
                      var m = code.className.match(/language-(\\S+)/);
                      if (m) lang = m[1];
                    }
                    out += '```' + lang + '\\n' + (code || n).textContent + '```\\n\\n';
                  }
                  else if (tag === 'UL') { out += mdList(n, '- ', 0) + '\\n'; }
                  else if (tag === 'OL') { out += mdOList(n, 0) + '\\n'; }
                  else if (tag === 'BLOCKQUOTE') { out += n.textContent.trim().split('\\n').map(function(l) { return '> ' + l; }).join('\\n') + '\\n\\n'; }
                  else if (tag === 'TABLE') { out += mdTable(n) + '\\n'; }
                  else if (tag === 'DIV' && n.classList.contains('admonition')) {
                    var title = n.querySelector('.admonition-title');
                    var body = n.querySelector('.admonition-body');
                    var type = 'note';
                    n.classList.forEach(function(c) { if (c.startsWith('admonition-') && c !== 'admonition-title' && c !== 'admonition-body') type = c.replace('admonition-', ''); });
                    out += ':::' + type + (title ? '[' + title.textContent.trim() + ']' : '') + '\\n';
                    if (body) out += body.textContent.trim();
                    out += '\\n:::\\n\\n';
                  }
                  else { out += htmlToMd(n); }
                }
                return out;
              }
              function mdInline(el) {
                var r = '';
                el.childNodes.forEach(function(n) {
                  if (n.nodeType === 3) { r += n.textContent; }
                  else if (n.nodeType === 1) {
                    var t = n.tagName;
                    if (t === 'CODE') r += '`' + n.textContent + '`';
                    else if (t === 'STRONG' || t === 'B') r += '**' + n.textContent + '**';
                    else if (t === 'EM' || t === 'I') r += '*' + n.textContent + '*';
                    else if (t === 'A') r += '[' + n.textContent + '](' + n.getAttribute('href') + ')';
                    else if (t === 'IMG') r += '![' + (n.getAttribute('alt')||'') + '](' + n.getAttribute('src') + ')';
                    else r += n.textContent;
                  }
                });
                return r;
              }
              function mdList(ul, marker, depth) {
                var r = '';
                var items = ul.children;
                for (var i = 0; i < items.length; i++) {
                  if (items[i].tagName !== 'LI') continue;
                  var indent = '  '.repeat(depth);
                  var sub = items[i].querySelector('ul,ol');
                  var text = '';
                  items[i].childNodes.forEach(function(c) {
                    if (c === sub) return;
                    if (c.nodeType === 1 && (c.tagName === 'UL' || c.tagName === 'OL')) return;
                    text += c.textContent;
                  });
                  r += indent + marker + text.trim() + '\\n';
                  if (sub) {
                    if (sub.tagName === 'OL') r += mdOList(sub, depth + 1);
                    else r += mdList(sub, '- ', depth + 1);
                  }
                }
                return r;
              }
              function mdOList(ol, depth) {
                var r = '';
                var items = ol.children;
                var num = 1;
                for (var i = 0; i < items.length; i++) {
                  if (items[i].tagName !== 'LI') continue;
                  var indent = '  '.repeat(depth);
                  var sub = items[i].querySelector('ul,ol');
                  var text = '';
                  items[i].childNodes.forEach(function(c) {
                    if (c.nodeType === 1 && (c.tagName === 'UL' || c.tagName === 'OL')) return;
                    text += c.textContent;
                  });
                  r += indent + (num++) + '. ' + text.trim() + '\\n';
                  if (sub) {
                    if (sub.tagName === 'OL') r += mdOList(sub, depth + 1);
                    else r += mdList(sub, '- ', depth + 1);
                  }
                }
                return r;
              }
              function mdTable(table) {
                var rows = table.querySelectorAll('tr');
                if (!rows.length) return '';
                var r = '';
                rows.forEach(function(row, ri) {
                  var cells = row.querySelectorAll('th,td');
                  var line = '|';
                  cells.forEach(function(c) { line += ' ' + c.textContent.trim() + ' |'; });
                  r += line + '\\n';
                  if (ri === 0) {
                    var sep = '|';
                    cells.forEach(function() { sep += ' --- |'; });
                    r += sep + '\\n';
                  }
                });
                return r;
              }
            })();
            // Right-side TOC: scroll-spy highlight
            (function() {
              var tocLinks = document.querySelectorAll('aside.toc a');
              if (!tocLinks.length) return;
              var headings = [];
              tocLinks.forEach(function(a) {
                var id = a.getAttribute('href').substring(1);
                var el = document.getElementById(id);
                if (el) headings.push({el: el, link: a});
              });
              function updateToc() {
                var scrollTop = window.scrollY + 80;
                var current = null;
                for (var i = 0; i < headings.length; i++) {
                  if (headings[i].el.offsetTop <= scrollTop) current = headings[i];
                }
                tocLinks.forEach(function(a) { a.classList.remove('toc-active'); });
                if (current) current.link.classList.add('toc-active');
              }
              window.addEventListener('scroll', updateToc);
              updateToc();
            })();
            var active = document.querySelector('nav.side a.active');
            if (active) {
              var el = active.parentElement;
              while (el && !el.classList.contains('content-wrap')) {
                if (el.classList.contains('cat-children')) {
                  el.classList.add('open');
                  var arrow = el.previousElementSibling && el.previousElementSibling.querySelector('.cat-arrow');
                  if (arrow) arrow.textContent = '▼';
                }
                el = el.parentElement;
              }
            }
            // Pre-block toolbar: copy + wrap-toggle buttons
            (function() {
              document.querySelectorAll('main pre').forEach(function(pre) {
                var container = document.createElement('div');
                container.className = 'pre-container';
                var toolbar = document.createElement('div');
                toolbar.className = 'pre-toolbar';
                // Copy button
                var copyBtn = document.createElement('button');
                copyBtn.innerHTML = '&#x1F4CB; Copy';
                copyBtn.title = 'Copy to clipboard';
                copyBtn.addEventListener('click', function() {
                  var text = pre.textContent;
                  navigator.clipboard.writeText(text.trim()).then(function() {
                    copyBtn.classList.add('copied');
                    copyBtn.innerHTML = '&#x2713; Copied';
                    setTimeout(function() { copyBtn.classList.remove('copied'); copyBtn.innerHTML = '&#x1F4CB; Copy'; }, 1500);
                  });
                });
                // Wrap toggle button (wrap is default)
                var wrapBtn = document.createElement('button');
                wrapBtn.innerHTML = '&#x21B5; Wrap';
                wrapBtn.title = 'Toggle line wrap';
                wrapBtn.classList.add('active');
                wrapBtn.addEventListener('click', function() {
                  if (container.classList.contains('pre-nowrap')) {
                    container.classList.remove('pre-nowrap');
                    wrapBtn.classList.add('active');
                    wrapBtn.innerHTML = '&#x21B5; Wrap';
                  } else {
                    container.classList.add('pre-nowrap');
                    wrapBtn.classList.remove('active');
                    wrapBtn.innerHTML = '&#x2194; NoWrap';
                  }
                });
                toolbar.appendChild(copyBtn);
                toolbar.appendChild(wrapBtn);
                pre.parentNode.insertBefore(container, pre);
                container.appendChild(toolbar);
                container.appendChild(pre);
              });
            })();
            // Language dropdown toggle
            (function() {
              document.querySelectorAll('.lang-btn').forEach(function(btn) {
                btn.addEventListener('click', function(e) {
                  e.stopPropagation();
                  btn.parentElement.classList.toggle('open');
                });
              });
              document.addEventListener('click', function() {
                document.querySelectorAll('.lang-dropdown.open').forEach(function(d) {
                  d.classList.remove('open');
                });
              });
            })();
            </script>
            """;
    }

    /** Loads a classpath resource relative to this class; throws at startup if missing. */
    private static String loadResource(String name) {
        try (var is = SiteBuilder.class.getResourceAsStream(name)) {
            if (is == null) throw new IllegalStateException("Missing resource: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Reads the site name to display in the navbar.
     * Priority: (1) {@code "title"} in {@code i18n/<locale>/docusaurus-theme-classic/navbar.json},
     * (2) {@code navbar.title} in {@code docusaurus.config.ts/js}, (3) returns {@code null}.
     */
    private static String readSiteNameFromConfig(Path projectRoot, String locale) {
        // 1. i18n/<locale>/docusaurus-theme-classic/navbar.json
        if (locale != null) {
            Path navbarJson = projectRoot.resolve(
                "i18n/" + locale + "/docusaurus-theme-classic/navbar.json");
            if (Files.exists(navbarJson)) {
                try {
                    String content = Files.readString(navbarJson);
                    var m = java.util.regex.Pattern
                        .compile("\"title\":\\s*\\{\\s*\"message\":\\s*\"([^\"]+)\"")
                        .matcher(content);
                    if (m.find()) return m.group(1);
                } catch (IOException ignored) {}
            }
        }
        // 2. docusaurus.config.ts/js: navbar: { title: '...' }
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path cfg = projectRoot.resolve(name);
            if (Files.exists(cfg)) {
                try {
                    String content = Files.readString(cfg);
                    var m = java.util.regex.Pattern
                        .compile("navbar:\\s*\\{[^}]*?title:\\s*['\"]([^'\"]+)['\"]",
                                 java.util.regex.Pattern.DOTALL)
                        .matcher(content);
                    if (m.find()) return m.group(1);
                } catch (IOException ignored) {}
            }
        }
        return null;
    }

    /**
     * Reads the favicon from the project's {@code static/} directory and returns it as a data URL.
     * The favicon path is read from {@code favicon:} in {@code docusaurus.config.ts/js}.
     * Returns {@code null} if not found.
     */
    private static String readFaviconDataUrl(Path projectRoot) {
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path cfg = projectRoot.resolve(name);
            if (!Files.exists(cfg)) continue;
            try {
                String content = Files.readString(cfg);
                var m = java.util.regex.Pattern
                    .compile("favicon:\\s*['\"]([^'\"]+)['\"]")
                    .matcher(content);
                if (!m.find()) continue;
                Path faviconFile = projectRoot.resolve("static").resolve(m.group(1));
                if (!Files.exists(faviconFile)) continue;
                byte[] bytes = Files.readAllBytes(faviconFile);
                String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                String ext = faviconFile.getFileName().toString().toLowerCase();
                String mime = ext.endsWith(".svg") ? "image/svg+xml"
                            : ext.endsWith(".png") ? "image/png"
                            : ext.endsWith(".jpg") || ext.endsWith(".jpeg") ? "image/jpeg"
                            : "image/x-icon";
                return "data:" + mime + ";base64," + base64;
            } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Returns the generated relative href for a given docs-relative file path,
     * using the same same-name-detection and cleanRelPath logic as {@link #convertPage}.
     * The result has no leading slash (e.g. {@code guides/top_page/} in production mode).
     */
    private String hrefForRel(Path rel) {
        boolean isSameName = false;
        if (rel.getNameCount() >= 2) {
            String fileBase = stripNumericPrefix(rel.getFileName().toString().replaceAll("\\.md$", ""));
            String parentBase = stripNumericPrefix(rel.getName(rel.getNameCount() - 2).toString());
            if (fileBase.equals(parentBase)) isSameName = true;
        }
        String cleanBase = isSameName
            ? cleanRelPath(rel.getParent().toString().replace('\\', '/'))
            : cleanRelPath(rel.toString().replace('\\', '/').replaceAll("\\.md$", ""));
        return production ? cleanBase + "/" : cleanBase + ".html";
    }

    /**
     * Returns {@code true} if the given Markdown source has {@code slug: /} in its YAML frontmatter,
     * indicating it is the Docusaurus root homepage.
     */
    private static boolean hasRootSlug(String source) {
        if (!source.startsWith("---")) return false;
        int end = source.indexOf("\n---", 3);
        if (end == -1) return false;
        for (String line : source.substring(3, end).split("\n")) {
            String t = line.strip();
            if (t.equals("slug: /") || t.equals("slug: '/'") || t.equals("slug: \"/\"")) return true;
        }
        return false;
    }

    /**
     * Reads the navbar logo from the project's {@code static/} directory and returns
     * {@code [dataUrl, alt]}. Returns {@code [null, ""]} if not configured or file is absent.
     * The logo path and alt text are read from {@code themeConfig.navbar.logo} in
     * {@code docusaurus.config.ts/js}.
     */
    private static String[] readLogoInfo(Path projectRoot) {
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path cfg = projectRoot.resolve(name);
            if (!Files.exists(cfg)) continue;
            try {
                String content = Files.readString(cfg);
                var logoBlock = java.util.regex.Pattern
                    .compile("logo:\\s*\\{([^}]+)\\}", java.util.regex.Pattern.DOTALL)
                    .matcher(content);
                if (!logoBlock.find()) continue;
                String block = logoBlock.group(1);
                var srcM = java.util.regex.Pattern.compile("src:\\s*['\"]([^'\"]+)['\"]").matcher(block);
                if (!srcM.find()) continue;
                String src = srcM.group(1);
                var altM = java.util.regex.Pattern.compile("alt:\\s*['\"]([^'\"]+)['\"]").matcher(block);
                String alt = altM.find() ? altM.group(1) : "";
                Path imgFile = projectRoot.resolve("static").resolve(src);
                if (!Files.exists(imgFile)) continue;
                byte[] bytes = Files.readAllBytes(imgFile);
                String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                String ext = imgFile.getFileName().toString().toLowerCase();
                String mime = ext.endsWith(".svg") ? "image/svg+xml"
                            : ext.endsWith(".png") ? "image/png"
                            : ext.endsWith(".jpg") || ext.endsWith(".jpeg") ? "image/jpeg"
                            : "image/png";
                return new String[]{"data:" + mime + ";base64," + base64, alt};
            } catch (IOException ignored) {}
        }
        return new String[]{null, ""};
    }

    /**
     * Walks up from {@code docsDir} to find the Docusaurus project root — the nearest ancestor
     * that contains a {@code docs/} subdirectory. This correctly handles alternate-locale builds
     * where {@code docsDir} is deep inside {@code i18n/<locale>/docusaurus-plugin-content-docs/current}.
     */
    private static Path findProjectRoot(Path docsDir) {
        Path p = docsDir.getParent();
        while (p != null) {
            if (Files.isDirectory(p.resolve("docs"))) return p;
            p = p.getParent();
        }
        return docsDir.getParent(); // fallback: should not happen in valid Docusaurus layout
    }

    /** Reads a file to a String, or returns null if the file does not exist. */
    /**
     * Reads a localized customization file, trying locale-specific name first.
     * For {@code html-saurus-header.html} with locale {@code en}, tries
     * {@code html-saurus-header.en.html} then falls back to {@code html-saurus-header.html}.
     * Returns {@code null} if neither file exists.
     */
    private static String readLocalized(Path dir, String filename, String locale) {
        if (locale != null) {
            int dot = filename.lastIndexOf('.');
            String localized = dot >= 0
                ? filename.substring(0, dot) + "." + locale + filename.substring(dot)
                : filename + "." + locale;
            String content = readOptional(dir.resolve(localized));
            if (content != null) return content;
        }
        return readOptional(dir.resolve(filename));
    }

    private static String readOptional(Path p) {
        if (!Files.exists(p)) return null;
        try { return Files.readString(p); }
        catch (IOException e) { System.err.println("Warning: could not read " + p + ": " + e.getMessage()); return null; }
    }
}
