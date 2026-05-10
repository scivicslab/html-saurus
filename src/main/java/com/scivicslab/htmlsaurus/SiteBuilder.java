package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private final PageRenderer pageRenderer;

    /** Holds metadata about each built page for feed and sitemap generation. */
    record PageInfo(String title, String absoluteUrl, String summary, String isoDate) {}

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
        Path projectRoot = ConfigReader.findProjectRoot(docsDir);
        // Read site name from i18n navbar.json or docusaurus.config; fall back to passed-in name
        String configSiteName = ConfigReader.readSiteNameFromConfig(projectRoot, currentLocale);
        this.siteName = configSiteName != null ? configSiteName : siteName;
        this.customCss       = production ? ConfigReader.readLocalized(projectRoot, "html-saurus.css",              currentLocale) : null;
        this.customHeader    = production ? ConfigReader.readLocalized(projectRoot, "html-saurus-header.html",    currentLocale) : null;
        this.customFooter    = production ? ConfigReader.readLocalized(projectRoot, "html-saurus-footer.html",    currentLocale) : null;
        this.customTocFooter = production ? ConfigReader.readLocalized(projectRoot, "html-saurus-toc-footer.html", currentLocale) : null;
        this.faviconDataUrl = ConfigReader.readFaviconDataUrl(projectRoot);
        String[] logoInfo = ConfigReader.readLogoInfo(projectRoot);
        this.logoDataUrl = logoInfo[0];
        this.logoAlt     = logoInfo[1];
        this.siteUrl     = ConfigReader.readSiteUrl(projectRoot);
        this.converter = new MarkdownConverter();
        // For alternate locales, use the default-locale docs dir as fallback so untranslated
        // pages still appear in the sidebar (Docusaurus standard behaviour).
        Path fallbackDocsDir = null;
        if (currentLocale != null && defaultLocale != null && !currentLocale.equals(defaultLocale)) {
            Path defaultDocs = projectRoot.resolve("docs");
            if (Files.isDirectory(defaultDocs)) fallbackDocsDir = defaultDocs;
        }
        this.navBuilder = new NavTreeBuilder(docsDir, production, converter, currentLocale, defaultLocale, fallbackDocsDir);
        this.pageRenderer = new PageRenderer(production, this.siteName,
                customCss, customHeader, customFooter, customTocFooter,
                faviconDataUrl, logoDataUrl, logoAlt,
                currentLocale, defaultLocale, this.allLocales, siteUrl);
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

        // Parse blog posts before building docs so "Blog" appears in the navbar for all pages.
        List<BlogBuilder.BlogPost> blogPosts = new ArrayList<>();
        Map<String, List<BlogBuilder.BlogPost>> blogByTag = new LinkedHashMap<>();
        Path projectRoot = ConfigReader.findProjectRoot(docsDir);
        boolean isNonDefaultLocale = currentLocale != null && !currentLocale.equals(defaultLocale);
        Path blogSrcDir = isNonDefaultLocale
                ? projectRoot.resolve("i18n/" + currentLocale + "/docusaurus-plugin-content-blog")
                : projectRoot.resolve("blog");
        BlogBuilder blogBuilder = new BlogBuilder(outDir, converter, currentLocale, defaultLocale,
                production, siteName, customCss, customHeader, customFooter, this);
        boolean blogInNav = ConfigReader.hasBlogNavbarEntry(projectRoot);
        if (Files.exists(blogSrcDir) && Files.isDirectory(blogSrcDir) && blogInNav) {
            try (var topStream = Files.list(blogSrcDir)) {
                topStream.forEach(entry -> {
                    try {
                        if (!Files.isDirectory(entry) && entry.toString().endsWith(".md")) {
                            blogPosts.add(blogBuilder.parseBlogPost(entry));
                        } else if (Files.isDirectory(entry)) {
                            Path idx = entry.resolve("index.md");
                            if (Files.exists(idx)) {
                                blogPosts.add(blogBuilder.parseBlogPost(idx));
                            } else {
                                try (var inner = Files.list(entry)) {
                                    inner.filter(p -> !Files.isDirectory(p) && p.toString().endsWith(".md"))
                                         .findFirst().ifPresent(p -> {
                                             try { blogPosts.add(blogBuilder.parseBlogPost(p)); }
                                             catch (IOException e) { System.err.println("WARN: blog parse error: " + p); }
                                         });
                                }
                            }
                        }
                    } catch (IOException e) { System.err.println("WARN: blog scan error: " + entry); }
                });
            }
            blogPosts.sort(Comparator.<BlogBuilder.BlogPost, LocalDate>comparing(
                    p -> p.date() != null ? p.date() : LocalDate.EPOCH).reversed());
            for (BlogBuilder.BlogPost p : blogPosts) {
                for (String t : p.tags()) blogByTag.computeIfAbsent(t, k -> new ArrayList<>()).add(p);
            }
            root.children().add(blogBuilder.buildBlogNavNode(blogPosts, blogByTag));
        }

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
            FeedGenerator feedGenerator = new FeedGenerator(outDir, siteUrl, siteName,
                    currentLocale, defaultLocale, builtPages);
            feedGenerator.generateSitemap();
            feedGenerator.generateRssFeed();
            feedGenerator.generateJsonFeed();
        }

        if (!blogPosts.isEmpty()) {
            blogBuilder.buildBlog(blogPosts, blogByTag, root);
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
            builtPages.add(new PageInfo(title, absUrl, FeedGenerator.plainText(body, 100), FeedGenerator.gitLastModifiedIso(mdFile)));
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

    static String stripNumericPrefix(String name) { return PageRenderer.stripNumericPrefix(name); }
    static String cleanRelPath(String relPath) { return PageRenderer.cleanRelPath(relPath); }
    static String stripExtension(String filename) { return PageRenderer.stripExtension(filename); }
    static String escapeHtml(String s) { return PageRenderer.escapeHtml(s); }
    static String dirNameForSection(SiteNode section) { return PageRenderer.dirNameForSection(section); }

    String renderPage(String title, String content, SiteNode root,
                      String prefix, String currentPath, String topSection,
                      String rawRelPath,
                      String prevHref, String prevLabel,
                      String nextHref, String nextLabel,
                      String lastUpdated) {
        return pageRenderer.renderPage(title, content, root, prefix, currentPath, topSection,
                                        rawRelPath, prevHref, prevLabel, nextHref, nextLabel, lastUpdated);
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

}
