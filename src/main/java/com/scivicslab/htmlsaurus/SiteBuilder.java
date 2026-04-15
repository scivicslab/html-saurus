package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
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
    /** Custom TOC footer HTML injected at the bottom of the right-side TOC aside; null if absent. */
    private final String customTocFooter;
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
    /** Site URL (e.g. "https://example.com") read from docusaurus.config; null if not found. Used for feeds/sitemap. */
    private final String siteUrl;
    /** Pages collected during build for feed and sitemap generation. Thread-safe for parallelStream. */
    private final List<PageInfo> builtPages = new CopyOnWriteArrayList<>();

    /** Holds metadata about each built page for feed and sitemap generation. */
    private record PageInfo(String title, String absoluteUrl, String summary, String isoDate) {}

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
        this.customCss       = production ? readLocalized(projectRoot, "html-saurus.css",              currentLocale) : null;
        this.customHeader    = production ? readLocalized(projectRoot, "html-saurus-header.html",    currentLocale) : null;
        this.customFooter    = production ? readLocalized(projectRoot, "html-saurus-footer.html",    currentLocale) : null;
        this.customTocFooter = production ? readLocalized(projectRoot, "html-saurus-toc-footer.html", currentLocale) : null;
        this.faviconDataUrl = readFaviconDataUrl(projectRoot);
        String[] logoInfo = readLogoInfo(projectRoot);
        this.logoDataUrl = logoInfo[0];
        this.logoAlt     = logoInfo[1];
        this.siteUrl     = readSiteUrl(projectRoot);
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
        List<SiteNode> pageOrder = flattenOrder(root.children());

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
                convertPage(pair[0], pair[1], root, pageOrder);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        // In production mode, non-same-name MD files produce dir/file/index.html,
        // one level deeper than the source directory.  Copy sibling assets (images etc.)
        // into each page's output directory so that relative src="./img.png" still works.
        if (production) {
            for (Path[] pair : mdFiles) {
                Path rel = pair[1];
                // Skip same-name pattern files (their output dir matches the source dir)
                boolean isSameName = false;
                if (rel.getNameCount() >= 2) {
                    String fileBase = stripNumericPrefix(rel.getFileName().toString().replaceAll("\\.md$", ""));
                    String parentBase = stripNumericPrefix(rel.getName(rel.getNameCount() - 2).toString());
                    if (fileBase.equals(parentBase)) isSameName = true;
                }
                if (isSameName) continue;

                // Determine the output directory for this page's index.html
                String[] fm = converter.parseFrontmatter(Files.readString(pair[0]));
                String fmId = fm[2];
                String cleanBase;
                if (!fmId.isEmpty()) {
                    String parentPath = rel.getParent() == null ? "" : rel.getParent().toString().replace('\\', '/');
                    cleanBase = parentPath.isEmpty() ? fmId : cleanRelPath(parentPath) + "/" + fmId;
                } else {
                    cleanBase = cleanRelPath(rel.toString().replace('\\', '/').replaceAll("\\.md$", ""));
                }
                Path pageOutDir = outDir.resolve(cleanBase);

                // Copy non-MD siblings from the source directory into the page output directory
                Path srcDir = pair[0].getParent();
                try (var siblings = Files.list(srcDir)) {
                    siblings.filter(f -> !Files.isDirectory(f) && !f.toString().endsWith(".md"))
                            .forEach(asset -> {
                                try {
                                    Path dest = pageOutDir.resolve(asset.getFileName().toString());
                                    if (!Files.exists(dest)) {
                                        Files.createDirectories(dest.getParent());
                                        Files.copy(asset, dest);
                                    }
                                } catch (IOException ignored) {}
                            });
                }
            }
        }

        // Generate root index.html for the homepage.
        // A doc with "slug: /" in its frontmatter is the Docusaurus homepage.
        // Render it directly as root index.html (no redirect) to avoid browser blocking.
        boolean rootPageGenerated = false;
        for (Path[] pair : mdFiles) {
            try {
                if (hasRootSlug(Files.readString(pair[0]))) {
                    convertPageAsRoot(pair[0], pair[1], root, pageOrder);
                    rootPageGenerated = true;
                    break;
                }
            } catch (IOException ignored) {}
        }
        if (!rootPageGenerated) {
            // Fallback: redirect to the first page in the nav tree
            String h = root.href();
            String homeTarget = h != null ? (h.startsWith("/") ? h.substring(1) : h) : null;
            if (homeTarget != null && !homeTarget.toLowerCase().startsWith("javascript:")) {
                String targetEsc = escapeHtml(homeTarget);
                String indexHtml = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                    + "<meta http-equiv=\"refresh\" content=\"0;url=" + targetEsc + "\">"
                    + "<title>Redirecting...</title></head><body>"
                    + "<p>Redirecting to <a href=\"" + targetEsc + "\">" + targetEsc + "</a>...</p>"
                    + "</body></html>\n";
                Files.writeString(outDir.resolve("index.html"), indexHtml);
            }
        }

        if (siteUrl != null && !builtPages.isEmpty()) {
            generateSitemap();
            generateRssFeed();
            generateJsonFeed();
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
    private void convertPage(Path mdFile, Path rel, SiteNode root, List<SiteNode> pageOrder) throws IOException {
        String source = Files.readString(mdFile);
        String[] fm = converter.parseFrontmatter(source);
        String title = fm[0].isBlank()
            ? stripNumericPrefix(stripExtension(mdFile.getFileName().toString())) : fm[0];
        String body = fm[1];
        String fmId = fm[2]; // frontmatter id (may be empty)
        String contentHtml = converter.convertMarkdown(body);

        // Detect same-name pattern: dir/dir.md (Docusaurus convention).
        // Also used below to fix relative asset paths in dev mode.
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

        String cleanBase;
        if (!fmId.isEmpty()) {
            // Frontmatter id overrides the filename segment (Docusaurus compatibility).
            // Strip numeric prefix from id so that "010_Foo" becomes "Foo" (consistent with filename handling).
            String cleanId = stripNumericPrefix(fmId);
            // For same-name files, id replaces the last directory segment.
            // For regular files, id replaces the filename.
            if (isSameName) {
                String parentPath = rel.getParent() == null ? "" : rel.getParent().toString().replace('\\', '/');
                int lastSlash = parentPath.lastIndexOf('/');
                String parentDir = lastSlash >= 0 ? cleanRelPath(parentPath.substring(0, lastSlash)) + "/" : "";
                cleanBase = parentDir + cleanId;
            } else {
                String parentPath = rel.getParent() == null ? "" : rel.getParent().toString().replace('\\', '/');
                cleanBase = parentPath.isEmpty() ? cleanId : cleanRelPath(parentPath) + "/" + cleanId;
            }
        } else {
            cleanBase = isSameName
                ? cleanRelPath(rel.getParent().toString().replace('\\', '/'))
                : cleanRelPath(rel.toString().replace('\\', '/').replaceAll("\\.md$", ""));
        }
        // In dev mode, same-name HTML is output one level up from the asset directory.
        // Rewrite relative src in content so that "img.png" becomes "dirName/img.png".
        if (isSameName && !production) {
            // Use the cleaned directory name (where assets are copied), not fmId
            String assetDir = stripNumericPrefix(rel.getName(rel.getNameCount() - 2).toString());
            String assetPrefix = assetDir + "/";
            contentHtml = contentHtml.replaceAll(
                "(src=\")(?!https?://|data:|/|#)([^\"]+\")",
                "$1" + assetPrefix.replace("$", "\\$") + "$2");
        }

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

        // Compute prev/next navigation from DFS page order
        String prevHref = null, prevLabel = null, nextHref = null, nextLabel = null;
        int pageIdx = -1;
        for (int i = 0; i < pageOrder.size(); i++) {
            SiteNode n = pageOrder.get(i);
            String nUrl = n.isDir() ? n.catLink() : n.href();
            if (currentPath.equals(nUrl)) { pageIdx = i; break; }
        }
        if (pageIdx > 0) {
            SiteNode prev = pageOrder.get(pageIdx - 1);
            String prevUrl = prev.isDir() ? prev.catLink() : prev.href();
            if (prevUrl != null) {
                prevHref = prefix + prevUrl.replaceFirst("^/", "");
                prevLabel = prev.label();
            }
        }
        if (pageIdx >= 0 && pageIdx < pageOrder.size() - 1) {
            SiteNode next = pageOrder.get(pageIdx + 1);
            String nextUrl = next.isDir() ? next.catLink() : next.href();
            if (nextUrl != null) {
                nextHref = prefix + nextUrl.replaceFirst("^/", "");
                nextLabel = next.label();
            }
        }

        // Git last-modified date (short for display, ISO for feeds)
        String lastUpdated = gitLastModified(mdFile);

        // Collect page info for feeds/sitemap
        if (siteUrl != null) {
            boolean isNonDefaultLocale = currentLocale != null && !currentLocale.equals(defaultLocale);
            String absUrl = siteUrl + (isNonDefaultLocale ? "/" + currentLocale : "") + currentPath;
            builtPages.add(new PageInfo(title, absUrl, plainText(body, 100), gitLastModifiedIso(mdFile)));
        }

        String html = renderPage(title, contentHtml, root, prefix, currentPath, topSection, rawRelPath,
                                  prevHref, prevLabel, nextHref, nextLabel, lastUpdated);
        Files.writeString(outFile, html);
        System.out.println("  " + outFile);
    }

    /**
     * Renders a page with {@code slug: /} directly as {@code outDir/index.html}.
     * Uses depth 0 and currentPath "/" so all relative links resolve from the site root.
     * The page is also generated at its normal location by {@link #convertPage}.
     */
    private void convertPageAsRoot(Path mdFile, Path rel, SiteNode root, List<SiteNode> pageOrder) throws IOException {
        String source = Files.readString(mdFile);
        String[] fm = converter.parseFrontmatter(source);
        String title = fm[0].isBlank()
            ? stripNumericPrefix(stripExtension(mdFile.getFileName().toString())) : fm[0];
        String body = fm[1];
        String contentHtml = converter.convertMarkdown(body);

        // Root page: depth is 0 in production (/index.html), prefix is "./"
        String prefix = "./";
        String currentPath = "/";
        String topSection = rel.getNameCount() > 1
            ? "/" + stripNumericPrefix(rel.getName(0).toString()) : null;
        String rawRelPath = rel.toString().replace('\\', '/');

        // prev/next: find this page's normal URL in pageOrder for navigation
        // Detect same-name pattern (dir/dir.md) to match convertPage's URL collapsing.
        boolean isSameName = false;
        if (rel.getNameCount() >= 2) {
            String fileBase = stripNumericPrefix(stripExtension(rel.getFileName().toString()));
            String parentBase = stripNumericPrefix(rel.getName(rel.getNameCount() - 2).toString());
            if (fileBase.equals(parentBase)) isSameName = true;
        }
        String fmId = fm[2];
        String cleanFmId = stripNumericPrefix(fmId);
        String normalCleanBase;
        if (!fmId.isEmpty()) {
            if (isSameName) {
                String parentPath = rel.getParent() == null ? "" : rel.getParent().toString().replace('\\', '/');
                int lastSlash = parentPath.lastIndexOf('/');
                String parentDir = lastSlash >= 0 ? cleanRelPath(parentPath.substring(0, lastSlash)) + "/" : "";
                normalCleanBase = parentDir + cleanFmId;
            } else {
                normalCleanBase = rel.getParent() == null ? cleanFmId : cleanRelPath(rel.getParent().toString().replace('\\', '/')) + "/" + cleanFmId;
            }
        } else {
            normalCleanBase = isSameName
                ? cleanRelPath(rel.getParent().toString().replace('\\', '/'))
                : cleanRelPath(rel.toString().replace('\\', '/').replaceAll("\\.md$", ""));
        }
        String normalPath = production ? "/" + normalCleanBase + "/" : "/" + normalCleanBase + ".html";
        String prevHref = null, prevLabel = null, nextHref = null, nextLabel = null;
        int pageIdx = -1;
        for (int i = 0; i < pageOrder.size(); i++) {
            SiteNode n = pageOrder.get(i);
            String nUrl = n.isDir() ? n.catLink() : n.href();
            if (normalPath.equals(nUrl)) { pageIdx = i; break; }
        }
        if (pageIdx > 0) {
            SiteNode prev = pageOrder.get(pageIdx - 1);
            String prevUrl = prev.isDir() ? prev.catLink() : prev.href();
            if (prevUrl != null) { prevHref = prefix + prevUrl.replaceFirst("^/", ""); prevLabel = prev.label(); }
        }
        if (pageIdx >= 0 && pageIdx < pageOrder.size() - 1) {
            SiteNode next = pageOrder.get(pageIdx + 1);
            String nextUrl = next.isDir() ? next.catLink() : next.href();
            if (nextUrl != null) { nextHref = prefix + nextUrl.replaceFirst("^/", ""); nextLabel = next.label(); }
        }

        String lastUpdated = gitLastModified(mdFile);
        String html = renderPage(title, contentHtml, root, prefix, currentPath, topSection, rawRelPath,
                                  prevHref, prevLabel, nextHref, nextLabel, lastUpdated);
        Path outFile = outDir.resolve("index.html");
        Files.writeString(outFile, html);
        System.out.println("  " + outFile + " (root)");

        // Copy non-MD sibling assets (images etc.) from the source directory to outDir root
        // so that relative src="top_image.png" in root index.html resolves correctly.
        Path srcDir = mdFile.getParent();
        try (var siblings = Files.list(srcDir)) {
            siblings.filter(f -> !Files.isDirectory(f) && !f.toString().endsWith(".md"))
                    .forEach(asset -> {
                        try {
                            Path dest = outDir.resolve(asset.getFileName().toString());
                            Files.copy(asset, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {}
                    });
        }
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

    /**
     * Derives a stable, mode-independent localStorage key for a sidebar category.
     * Strips the leading slash, trailing slash, and {@code .html} extension so that
     * dev-mode hrefs ({@code /guides/software.html}) and production-mode hrefs
     * ({@code /guides/software/}) both produce the same key ({@code guides/software}).
     * Numeric prefixes are also removed via {@link #cleanRelPath}.
     */
    static String stableCatKey(String href) {
        if (href == null) return "";
        String k = href;
        if (k.startsWith("/")) k = k.substring(1);
        if (k.endsWith("/")) k = k.substring(0, k.length() - 1);
        if (k.endsWith(".html")) k = k.substring(0, k.length() - 5);
        return cleanRelPath(k);
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
        // Site title always links to the directory root (./) — the server resolves / to index.html
        // internally.  Never expose "index.html" in the URL bar regardless of mode.
        sb.append("  <a class=\"site-title\" href=\"").append(prefix).append("\">");
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
        sb.append("  <button class=\"menu-toggle\" id=\"menu-toggle\" aria-label=\"Toggle navigation\" aria-expanded=\"false\">\n");
        sb.append("    <svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\">\n");
        sb.append("      <line x1=\"3\" y1=\"6\" x2=\"21\" y2=\"6\"/><line x1=\"3\" y1=\"12\" x2=\"21\" y2=\"12\"/><line x1=\"3\" y1=\"18\" x2=\"21\" y2=\"18\"/>\n");
        sb.append("    </svg>\n");
        sb.append("  </button>\n");
        sb.append("</header>\n");
        sb.append("<div class=\"sidebar-overlay\" id=\"sidebar-overlay\"></div>\n");

        // Content wrapper
        sb.append("<div class=\"content-wrap\">\n");

        // Sidebar: show only the active section's children, or all if no section
        sb.append("<nav class=\"side\" id=\"sidebar\">\n");
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

        // Last updated from git history
        if (lastUpdated != null) {
            sb.append("<div class=\"page-last-updated\">Last updated: ")
              .append(escapeHtml(lastUpdated)).append("</div>\n");
        }

        // Previous / Next page navigation
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

        // Right-side TOC: extract h2/h3 headings from content HTML
        sb.append(buildToc(content, buildTocFooter(prefix)));
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

    /** Matches {@code <h2>} and {@code <h3>} elements with {@code id} attributes for TOC extraction. */
    private static final Pattern HEADING_PATTERN = Pattern.compile(
        "<h([23])\\s[^>]*id=\"([^\"]+)\"[^>]*>(.*?)</h[23]>", Pattern.DOTALL);

    /**
     * Builds a right-side table of contents by extracting h2/h3 headings from the rendered HTML.
     * Returns an empty string if no headings are found and footer is empty.
     *
     * @param contentHtml the rendered page content HTML
     * @param footer      HTML to inject at the bottom of the aside (may be empty)
     * @return HTML string for the {@code <aside class="toc">} element
     */
    private String buildToc(String contentHtml, String footer) {
        var matcher = HEADING_PATTERN.matcher(contentHtml);
        List<String[]> entries = new ArrayList<>(); // [level, id, text]
        while (matcher.find()) {
            String level = matcher.group(1);
            String id = matcher.group(2);
            String text = matcher.group(3).replaceAll("<[^>]+>", "").trim(); // strip inner HTML tags
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
     * If a {@code html-saurus-toc-footer.html} file exists in the project root, its content is used.
     * Otherwise, if {@link #siteUrl} is configured, auto-generates RSS and JSON Feed links.
     * Returns an empty string if neither condition is met.
     *
     * @param prefix relative path prefix for resolving asset URLs (e.g., {@code "../"})
     * @return HTML fragment to append inside the TOC aside, or empty string
     */
    /** Inline SVG of the standard RSS wave icon (dot + two arcs), white fill. */
    private static final String RSS_ICON =
        "<svg width=\"13\" height=\"13\" viewBox=\"0 0 10 10\" fill=\"white\" xmlns=\"http://www.w3.org/2000/svg\">"
        + "<circle cx=\"1.5\" cy=\"8.5\" r=\"1.5\"/>"
        + "<path d=\"M0 5.5a4.5 4.5 0 0 1 4.5 4.5H3A3 3 0 0 0 0 7z\"/>"
        + "<path d=\"M0 2a8 8 0 0 1 8 8H6.5A6.5 6.5 0 0 0 0 3.5z\"/>"
        + "</svg>";

    /** Curly-brace icon for JSON Feed button, rendered as styled HTML. */
    private static final String JSON_ICON =
        "<span style=\"font-family:monospace;font-size:0.85em;letter-spacing:-0.05em;font-weight:900\">{}</span>";

    /**
     * Builds the HTML to inject at the bottom of the right-side TOC aside.
     * If a {@code html-saurus-toc-footer.html} file exists in the project root, its content is used.
     * Otherwise, if {@link #siteUrl} is configured, auto-generates RSS and JSON Feed links.
     * Returns an empty string if neither condition is met.
     *
     * @param prefix relative path prefix for resolving asset URLs (e.g., {@code "../"})
     * @return HTML fragment to append inside the TOC aside, or empty string
     */
    private String buildTocFooter(String prefix) {
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
            (function() {
              var toggle = document.getElementById('menu-toggle');
              var sidebar = document.getElementById('sidebar');
              var overlay = document.getElementById('sidebar-overlay');
              if (!toggle || !sidebar || !overlay) return;
              function openSidebar() {
                sidebar.classList.add('open');
                overlay.classList.add('open');
                toggle.setAttribute('aria-expanded', 'true');
              }
              function closeSidebar() {
                sidebar.classList.remove('open');
                overlay.classList.remove('open');
                toggle.setAttribute('aria-expanded', 'false');
              }
              toggle.addEventListener('click', function() {
                sidebar.classList.contains('open') ? closeSidebar() : openSidebar();
              });
              overlay.addEventListener('click', closeSidebar);
              document.addEventListener('keydown', function(e) {
                if (e.key === 'Escape') closeSidebar();
              });
            })();
            document.querySelectorAll('nav.side a.cat-label').forEach(function(link) {
              link.addEventListener('click', function(e) { e.stopPropagation(); });
            });
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
                  fetch('YADOC_BUILD_URL', {method: 'POST'})
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
                  var header = el.previousElementSibling;
                  if (header && header.dataset.cat) {
                    localStorage.setItem('hs-cat:' + header.dataset.cat, '1');
                  }
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
     *
     * @param rel   relative path from docs root
     * @param fmId  frontmatter id (may be empty)
     */
    private String hrefForRel(Path rel, String fmId) {
        boolean isSameName = false;
        if (rel.getNameCount() >= 2) {
            String fileBase = stripNumericPrefix(rel.getFileName().toString().replaceAll("\\.md$", ""));
            String parentBase = stripNumericPrefix(rel.getName(rel.getNameCount() - 2).toString());
            if (fileBase.equals(parentBase)) isSameName = true;
        }
        String cleanBase;
        String cleanId = fmId != null ? stripNumericPrefix(fmId) : "";
        if (!cleanId.isEmpty()) {
            if (isSameName) {
                String parentPath = rel.getParent() == null ? "" : rel.getParent().toString().replace('\\', '/');
                int lastSlash = parentPath.lastIndexOf('/');
                String parentDir = lastSlash >= 0 ? cleanRelPath(parentPath.substring(0, lastSlash)) + "/" : "";
                cleanBase = parentDir + cleanId;
            } else {
                String parentPath = rel.getParent() == null ? "" : rel.getParent().toString().replace('\\', '/');
                cleanBase = parentPath.isEmpty() ? cleanId : cleanRelPath(parentPath) + "/" + cleanId;
            }
        } else {
            cleanBase = isSameName
                ? cleanRelPath(rel.getParent().toString().replace('\\', '/'))
                : cleanRelPath(rel.toString().replace('\\', '/').replaceAll("\\.md$", ""));
        }
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

    /**
     * Reads the site URL from {@code url:} in {@code docusaurus.config.ts/js}.
     * Returns {@code null} if not found. Trailing slashes are stripped.
     */
    private static String readSiteUrl(Path projectRoot) {
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path cfg = projectRoot.resolve(name);
            if (!Files.exists(cfg)) continue;
            try {
                String content = Files.readString(cfg);
                var m = java.util.regex.Pattern.compile("url:\\s*['\"]([^'\"]+)['\"]").matcher(content);
                if (m.find()) return m.group(1).replaceAll("/+$", "");
            } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Returns a DFS-ordered flat list of navigable page nodes from the nav tree.
     * For directory nodes with a category link page, the dir node itself is added first (representing
     * its intro/category page), then its children are recursed. Leaf nodes are added directly.
     */
    private static List<SiteNode> flattenOrder(List<SiteNode> nodes) {
        List<SiteNode> result = new ArrayList<>();
        for (SiteNode node : nodes) {
            if (node.isDir()) {
                if (node.catLink() != null) result.add(node);
                result.addAll(flattenOrder(node.children()));
            } else {
                result.add(node);
            }
        }
        return result;
    }

    /**
     * Returns the date of the last git commit that touched {@code filePath},
     * formatted as {@code YYYY-MM-DD}, or {@code null} if not tracked or git is unavailable.
     */
    private static String gitLastModified(Path filePath) {
        try {
            var pb = new ProcessBuilder(
                "git", "log", "-1", "--format=%ad", "--date=short", "--",
                filePath.toAbsolutePath().toString());
            pb.directory(filePath.getParent().toFile());
            pb.redirectErrorStream(true);
            var proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Generates {@code sitemap.xml} in the output directory listing all built pages. */
    private void generateSitemap() throws IOException {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        List<PageInfo> sorted = new ArrayList<>(builtPages);
        sorted.sort(Comparator.comparing(PageInfo::absoluteUrl));
        for (PageInfo p : sorted) {
            sb.append("  <url><loc>").append(escapeHtml(p.absoluteUrl())).append("</loc></url>\n");
        }
        sb.append("</urlset>\n");
        Path out = outDir.resolve("sitemap.xml");
        Files.writeString(out, sb.toString());
        System.out.println("  " + out);
    }

    /** Generates {@code rss.xml} (RSS 2.0) in the output directory. */
    private void generateRssFeed() throws IOException {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String buildDate = now.format(DateTimeFormatter.ofPattern(
            "EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH));
        String lang = currentLocale != null ? currentLocale : (defaultLocale != null ? defaultLocale : "ja");
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<rss version=\"2.0\">\n<channel>\n");
        sb.append("  <title>").append(escapeHtml(siteName)).append("</title>\n");
        sb.append("  <link>").append(escapeHtml(siteUrl)).append("</link>\n");
        sb.append("  <description>").append(escapeHtml(siteName)).append("</description>\n");
        sb.append("  <language>").append(escapeHtml(lang)).append("</language>\n");
        sb.append("  <lastBuildDate>").append(escapeHtml(buildDate)).append("</lastBuildDate>\n");
        List<PageInfo> sorted = new ArrayList<>(builtPages);
        sorted.sort(Comparator.comparing(PageInfo::absoluteUrl));
        for (PageInfo p : sorted) {
            sb.append("  <item>\n");
            sb.append("    <title>").append(escapeHtml(p.title())).append("</title>\n");
            sb.append("    <link>").append(escapeHtml(p.absoluteUrl())).append("</link>\n");
            sb.append("    <guid>").append(escapeHtml(p.absoluteUrl())).append("</guid>\n");
            sb.append("    <pubDate>").append(escapeHtml(buildDate)).append("</pubDate>\n");
            sb.append("    <description>").append(escapeHtml(p.summary())).append("</description>\n");
            sb.append("  </item>\n");
        }
        sb.append("</channel>\n</rss>\n");
        Path out = outDir.resolve("rss.xml");
        Files.writeString(out, sb.toString());
        System.out.println("  " + out);
    }

    /** Generates {@code feed.json} (JSON Feed v1.1) in the output directory. */
    private void generateJsonFeed() throws IOException {
        // Fallback timestamp used when a file has no git history
        String fallbackIso = ZonedDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String lang = currentLocale != null ? currentLocale : (defaultLocale != null ? defaultLocale : "ja");
        boolean isNonDefault = currentLocale != null && !currentLocale.equals(defaultLocale);
        String localePath = isNonDefault ? "/" + currentLocale : "";
        String feedUrl = siteUrl + localePath + "/feed.json";
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": \"https://jsonfeed.org/version/1.1\",\n");
        sb.append("  \"title\": ").append(jsonString(siteName)).append(",\n");
        sb.append("  \"home_page_url\": ").append(jsonString(siteUrl)).append(",\n");
        sb.append("  \"feed_url\": ").append(jsonString(feedUrl)).append(",\n");
        sb.append("  \"language\": ").append(jsonString(lang)).append(",\n");
        sb.append("  \"items\": [\n");
        List<PageInfo> sorted = new ArrayList<>(builtPages);
        sorted.sort(Comparator.comparing(PageInfo::absoluteUrl));
        for (int i = 0; i < sorted.size(); i++) {
            PageInfo p = sorted.get(i);
            String datePublished = p.isoDate() != null ? p.isoDate() : fallbackIso;
            sb.append("    {\n");
            sb.append("      \"id\": ").append(jsonString(p.absoluteUrl())).append(",\n");
            sb.append("      \"url\": ").append(jsonString(p.absoluteUrl())).append(",\n");
            sb.append("      \"title\": ").append(jsonString(p.title())).append(",\n");
            sb.append("      \"content_text\": ").append(jsonString(p.summary())).append(",\n");
            sb.append("      \"date_published\": ").append(jsonString(datePublished)).append("\n");
            sb.append("    }").append(i < sorted.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}\n");
        Path out = outDir.resolve("feed.json");
        Files.writeString(out, sb.toString());
        System.out.println("  " + out);
    }

    /**
     * Returns the RFC 3339 timestamp of the last git commit that touched {@code filePath}
     * (e.g. {@code 2024-01-15T10:30:00+09:00}), or {@code null} if not tracked.
     */
    private static String gitLastModifiedIso(Path filePath) {
        try {
            var pb = new ProcessBuilder(
                "git", "log", "-1", "--format=%aI", "--",
                filePath.toAbsolutePath().toString());
            pb.directory(filePath.getParent().toFile());
            pb.redirectErrorStream(true);
            var proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Strips HTML tags, MDX/JSX expressions, HTML entities, and common Markdown syntax
     * from {@code markdown}, then truncates to at most {@code maxLen} characters.
     */
    private static String plainText(String markdown, int maxLen) {
        if (markdown == null) return "";
        String s = markdown;
        // Strip HTML/MDX tags
        s = s.replaceAll("<[^>]+?>", " ");
        // Strip MDX/JSX expressions {#id}, {{prop: val}}, etc. (up to 3 nesting levels)
        for (int i = 0; i < 3; i++) s = s.replaceAll("\\{[^{}]*\\}", " ");
        // Strip HTML entities (&amp; &#x1f517; etc.)
        s = s.replaceAll("&[a-zA-Z0-9#]+;", "");
        // Strip Markdown heading markers (## text → text)
        s = s.replaceAll("(?m)^#{1,6}\\s+", "");
        // Strip bold/italic/code markers
        s = s.replaceAll("\\*{1,3}([^*\n]+)\\*{1,3}", "$1");
        s = s.replaceAll("`[^`]+`", "");
        // Strip link syntax [text](url) → text, image ![alt](src) → alt
        s = s.replaceAll("!?\\[([^\\]]*)]\\([^)]*\\)", "$1");
        // Normalize whitespace
        s = s.replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").strip();
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** Encodes a string as a JSON string literal (with surrounding quotes). */
    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }
}
