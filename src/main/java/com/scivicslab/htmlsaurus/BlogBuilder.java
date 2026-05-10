package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds all blog-related pages: index, post pages, tag index, and tag pages.
 */
class BlogBuilder {

    static final int POSTS_PER_PAGE = 10;

    static final Pattern BLOG_DATE      = Pattern.compile("^date:\\s*(\\d{4}-\\d{2}-\\d{2})", Pattern.MULTILINE);
    static final Pattern BLOG_SLUG_PAT  = Pattern.compile("^slug:\\s*(\\S+)", Pattern.MULTILINE);
    static final Pattern BLOG_TAGS_SECT = Pattern.compile("^tags:\\s*\\n((?:[ \\t]+-[ \\t]+\\S.*\\n?)+)", Pattern.MULTILINE);
    static final Pattern BLOG_TAG_LINE  = Pattern.compile("[ \\t]+-[ \\t]+(.+)");
    static final Pattern BLOG_FILE_DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})-");

    private final Path outDir;
    private final MarkdownConverter converter;
    private final String currentLocale;
    private final String defaultLocale;
    private final boolean production;
    private final String siteName;
    private final String customCss;
    private final String customHeader;
    private final String customFooter;
    /** Callback to SiteBuilder.renderPage (will move to PageRenderer later). */
    private final SiteBuilder siteBuilder;

    BlogBuilder(Path outDir, MarkdownConverter converter,
                String currentLocale, String defaultLocale,
                boolean production, String siteName,
                String customCss, String customHeader, String customFooter,
                SiteBuilder siteBuilder) {
        this.outDir = outDir;
        this.converter = converter;
        this.currentLocale = currentLocale;
        this.defaultLocale = defaultLocale;
        this.production = production;
        this.siteName = siteName;
        this.customCss = customCss;
        this.customHeader = customHeader;
        this.customFooter = customFooter;
        this.siteBuilder = siteBuilder;
    }

    /** Represents a parsed Docusaurus blog post. */
    record BlogPost(String slug, String title, LocalDate date,
                    List<String> tags, String excerpt, String body) {}

    /** Parses a blog post Markdown file. */
    BlogPost parseBlogPost(Path file) throws IOException {
        String source = Files.readString(file);
        String rawFm = "";
        if (source.startsWith("---")) {
            int end = source.indexOf("---", 3);
            if (end > 0) rawFm = source.substring(3, end);
        }

        String[] fm = converter.parseFrontmatter(source);
        String title = fm[0];
        String body  = fm[1];
        if (title.isBlank()) {
            title = file.getFileName().toString().replaceAll("\\.md$", "")
                        .replaceFirst("^\\d{4}-\\d{2}-\\d{2}-", "");
        }

        LocalDate date = null;
        var dm = BLOG_DATE.matcher(rawFm);
        if (dm.find()) { try { date = LocalDate.parse(dm.group(1)); } catch (Exception ignored) {} }
        if (date == null) {
            var fdm = BLOG_FILE_DATE.matcher(file.getFileName().toString());
            if (fdm.find()) { try { date = LocalDate.parse(fdm.group(1)); } catch (Exception ignored) {} }
        }
        if (date == null) {
            // Folder-based post: try parent directory name (e.g. 2026-04-05-slug/index.md)
            var ddm = BLOG_FILE_DATE.matcher(file.getParent().getFileName().toString());
            if (ddm.find()) { try { date = LocalDate.parse(ddm.group(1)); } catch (Exception ignored) {} }
        }

        String slug;
        var sm = BLOG_SLUG_PAT.matcher(rawFm);
        if (sm.find()) {
            slug = sm.group(1).trim();
        } else if (file.getFileName().toString().equals("index.md")) {
            // Folder-based post without slug in frontmatter: use directory name
            slug = file.getParent().getFileName().toString()
                       .replaceFirst("^\\d{4}-\\d{2}-\\d{2}-", "");
        } else {
            slug = file.getFileName().toString().replaceAll("\\.md$", "")
                       .replaceFirst("^\\d{4}-\\d{2}-\\d{2}-", "");
        }

        List<String> tags = new ArrayList<>();
        var tm = BLOG_TAGS_SECT.matcher(rawFm);
        if (tm.find()) {
            var tlm = BLOG_TAG_LINE.matcher(tm.group(1));
            while (tlm.find()) tags.add(tlm.group(1).trim());
        }

        int ti = body.indexOf("<!-- truncate -->");
        if (ti < 0) ti = body.indexOf("<!--truncate-->");
        String excerpt = ti >= 0 ? body.substring(0, ti).trim() : body;

        return new BlogPost(slug, title, date, List.copyOf(tags), excerpt, body);
    }

    /** Builds the Blog SiteNode added as a top-level navbar section. */
    SiteNode buildBlogNavNode(List<BlogPost> posts, Map<String, List<BlogPost>> byTag) {
        List<SiteNode> tagNodes = new ArrayList<>();
        for (var entry : byTag.entrySet()) {
            String tag = entry.getKey();
            tagNodes.add(new SiteNode(tag + " (" + entry.getValue().size() + ")",
                    "/blog/tags/" + tagToSlug(tag) + "/", false, List.of(), null));
        }
        SiteNode tagsNode = new SiteNode("Tags", "/blog/tags/", true, tagNodes, "/blog/tags/");
        SiteNode allNode  = new SiteNode("All Posts", "/blog/", false, List.of(), null);
        return new SiteNode("Blog", "/blog/", true, new ArrayList<>(List.of(allNode, tagsNode)), "/blog/");
    }

    /** Orchestrates building all blog pages. */
    void buildBlog(List<BlogPost> posts, Map<String, List<BlogPost>> byTag, SiteNode navRoot)
            throws IOException {
        String localePrefix = (currentLocale != null && defaultLocale != null
                && !currentLocale.equals(defaultLocale)) ? "/" + currentLocale : "";
        Files.createDirectories(outDir.resolve("blog/tags"));
        Files.createDirectories(outDir.resolve("blog/page"));
        renderBlogIndex(posts, byTag, navRoot, localePrefix);
        for (int i = 0; i < posts.size(); i++) renderBlogPostPage(posts.get(i), i, posts, navRoot);
        renderTagIndex(byTag, navRoot);
        for (var entry : byTag.entrySet())
            renderTagPage(entry.getKey(), entry.getValue(), navRoot, localePrefix);
    }

    void renderBlogIndex(List<BlogPost> posts, Map<String, List<BlogPost>> byTag,
                         SiteNode navRoot, String localePrefix) throws IOException {
        int totalPages = Math.max(1, (posts.size() + POSTS_PER_PAGE - 1) / POSTS_PER_PAGE);
        String absBase = localePrefix + "/blog/";

        for (int page = 1; page <= totalPages; page++) {
            int from = (page - 1) * POSTS_PER_PAGE;
            int to   = Math.min(from + POSTS_PER_PAGE, posts.size());
            List<BlogPost> pagePosts = posts.subList(from, to);

            // Post/tag link prefix relative to this page's URL
            String pathToBlog = page == 1 ? "" : "../../";

            StringBuilder c = new StringBuilder("<div class=\"blog-list\">\n");
            for (BlogPost p : pagePosts) {
                c.append("<article class=\"blog-card\">\n");
                c.append("  <h2 class=\"blog-card-title\"><a href=\"")
                 .append(SiteBuilder.escapeHtml(pathToBlog + p.slug())).append("/\">")
                 .append(SiteBuilder.escapeHtml(p.title())).append("</a></h2>\n");
                c.append("  <div class=\"blog-meta\">");
                if (p.date() != null) c.append("<time>").append(p.date()).append("</time>");
                if (!p.tags().isEmpty()) {
                    c.append(" · ");
                    for (int i = 0; i < p.tags().size(); i++) {
                        if (i > 0) c.append(", ");
                        String tag = p.tags().get(i);
                        c.append("<a href=\"").append(SiteBuilder.escapeHtml(pathToBlog + "tags/" + tagToSlug(tag)))
                         .append("/\">").append(SiteBuilder.escapeHtml(tag)).append("</a>");
                    }
                }
                c.append("</div>\n");
                c.append("  <div class=\"blog-excerpt\">")
                 .append(stripHeadingIds(converter.convertMarkdown(p.excerpt()))).append("</div>\n");
                c.append("  <a href=\"").append(SiteBuilder.escapeHtml(pathToBlog + p.slug()))
                 .append("/\" class=\"blog-read-more\">Read more \u2192</a>\n");
                c.append("</article>\n");
            }
            c.append("</div>\n");
            c.append(buildBlogPaginator(page, totalPages, absBase));

            String pageTitle = page == 1 ? "Blog" : "Blog \u2014 Page " + page;
            String relRoot   = page == 1 ? "../" : "../../../";
            String currHref  = page == 1 ? absBase : absBase + "page/" + page + "/";
            Path out = page == 1
                    ? outDir.resolve("blog/index.html")
                    : outDir.resolve("blog/page/" + page + "/index.html");
            Files.createDirectories(out.getParent());
            Files.writeString(out, siteBuilder.renderPage(pageTitle, c.toString(), navRoot,
                    relRoot, currHref, "/blog", "blog/index.md",
                    null, null, null, null, null));
            System.out.println("  " + out);
        }
    }

    void renderBlogPostPage(BlogPost post, int idx, List<BlogPost> posts,
                            SiteNode navRoot) throws IOException {
        StringBuilder c = new StringBuilder("<div class=\"blog-post-meta\">");
        if (post.date() != null) c.append("<time>").append(post.date()).append("</time>");
        if (!post.tags().isEmpty()) {
            c.append(" \u00b7 ");
            for (int i = 0; i < post.tags().size(); i++) {
                if (i > 0) c.append(", ");
                String tag = post.tags().get(i);
                c.append("<a href=\"../tags/").append(SiteBuilder.escapeHtml(tagToSlug(tag)))
                 .append("/\">").append(SiteBuilder.escapeHtml(tag)).append("</a>");
            }
        }
        c.append("</div>\n");
        c.append(converter.convertMarkdown(post.body()));

        // Prev = older (higher index), Next = newer (lower index) — posts sorted newest-first
        String prevHref = null, prevLabel = null, nextHref = null, nextLabel = null;
        if (idx + 1 < posts.size()) {
            BlogPost older = posts.get(idx + 1);
            prevHref = "../" + older.slug() + "/";
            prevLabel = older.title();
        }
        if (idx > 0) {
            BlogPost newer = posts.get(idx - 1);
            nextHref = "../" + newer.slug() + "/";
            nextLabel = newer.title();
        }

        Path out = outDir.resolve("blog/" + post.slug() + "/index.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, siteBuilder.renderPage(post.title(), c.toString(), navRoot,
                "../../", "/blog/" + post.slug() + "/", "/blog",
                "blog/" + post.slug() + ".md",
                prevHref, prevLabel, nextHref, nextLabel,
                post.date() != null ? post.date().toString() : null));
        System.out.println("  " + out);
    }

    void renderTagIndex(Map<String, List<BlogPost>> byTag, SiteNode navRoot) throws IOException {
        StringBuilder c = new StringBuilder("<div class=\"blog-tags-index\">\n");
        for (var entry : byTag.entrySet()) {
            String tag = entry.getKey();
            c.append("<a class=\"blog-tag-chip\" href=\"").append(SiteBuilder.escapeHtml(tagToSlug(tag)))
             .append("/\">").append(SiteBuilder.escapeHtml(tag))
             .append(" <span>").append(entry.getValue().size()).append("</span></a>\n");
        }
        c.append("</div>\n");

        Path out = outDir.resolve("blog/tags/index.html");
        Files.writeString(out, siteBuilder.renderPage("Tags", c.toString(), navRoot,
                "../../", "/blog/tags/", "/blog", "blog/tags/index.md",
                null, null, null, null, null));
        System.out.println("  " + out);
    }

    void renderTagPage(String tag, List<BlogPost> posts,
                       SiteNode navRoot, String localePrefix) throws IOException {
        String tagSlug  = tagToSlug(tag);
        int totalPages  = Math.max(1, (posts.size() + POSTS_PER_PAGE - 1) / POSTS_PER_PAGE);
        String absBase  = localePrefix + "/blog/tags/" + tagSlug + "/";

        for (int page = 1; page <= totalPages; page++) {
            int from = (page - 1) * POSTS_PER_PAGE;
            int to   = Math.min(from + POSTS_PER_PAGE, posts.size());
            List<BlogPost> pagePosts = posts.subList(from, to);

            // path from this page's URL to /blog/
            String pathToBlog = page == 1 ? "../../" : "../../../../";
            // path from this page's URL to /blog/tags/
            String pathToTags = page == 1 ? "../" : "../../../";

            StringBuilder c = new StringBuilder("<div class=\"blog-list\">\n");
            for (BlogPost p : pagePosts) {
                c.append("<article class=\"blog-card\">\n");
                c.append("  <h2 class=\"blog-card-title\"><a href=\"")
                 .append(SiteBuilder.escapeHtml(pathToBlog + p.slug())).append("/\">")
                 .append(SiteBuilder.escapeHtml(p.title())).append("</a></h2>\n");
                c.append("  <div class=\"blog-meta\">");
                if (p.date() != null) c.append("<time>").append(p.date()).append("</time>");
                if (!p.tags().isEmpty()) {
                    c.append(" \u00b7 ");
                    for (int i = 0; i < p.tags().size(); i++) {
                        if (i > 0) c.append(", ");
                        String t = p.tags().get(i);
                        c.append("<a href=\"").append(SiteBuilder.escapeHtml(pathToTags + tagToSlug(t)))
                         .append("/\">").append(SiteBuilder.escapeHtml(t)).append("</a>");
                    }
                }
                c.append("</div>\n");
                c.append("  <div class=\"blog-excerpt\">")
                 .append(stripHeadingIds(converter.convertMarkdown(p.excerpt()))).append("</div>\n");
                c.append("  <a href=\"").append(SiteBuilder.escapeHtml(pathToBlog + p.slug()))
                 .append("/\" class=\"blog-read-more\">Read more \u2192</a>\n");
                c.append("</article>\n");
            }
            c.append("</div>\n");
            c.append(buildBlogPaginator(page, totalPages, absBase));

            String pageTitle = page == 1 ? tag : tag + " \u2014 Page " + page;
            String relRoot   = page == 1 ? "../../../" : "../../../../../";
            String currHref  = page == 1 ? absBase : absBase + "page/" + page + "/";
            Path out = page == 1
                    ? outDir.resolve("blog/tags/" + tagSlug + "/index.html")
                    : outDir.resolve("blog/tags/" + tagSlug + "/page/" + page + "/index.html");
            Files.createDirectories(out.getParent());
            Files.writeString(out, siteBuilder.renderPage(pageTitle, c.toString(), navRoot,
                    relRoot, currHref, "/blog",
                    "blog/tags/" + tagSlug + ".md",
                    null, null, null, null, null));
            System.out.println("  " + out);
        }
    }

    /**
     * Builds paginator HTML for blog listing pages.
     * Uses absolute hrefs so the markup is identical regardless of page depth.
     *
     * @param page        current 1-based page number
     * @param totalPages  total number of pages
     * @param absBaseHref absolute URL of the listing's first page (e.g. {@code /blog/} or {@code /blog/tags/news/})
     */
    static String buildBlogPaginator(int page, int totalPages, String absBaseHref) {
        if (totalPages <= 1) return "";
        var sb = new StringBuilder("<nav class=\"blog-paginator\" aria-label=\"Blog pages\">\n");

        // Prev
        if (page > 1) {
            String prevHref = page == 2 ? absBaseHref : absBaseHref + "page/" + (page - 1) + "/";
            sb.append("  <a href=\"").append(prevHref).append("\" class=\"paginator-prev\">\u2190 Prev</a>\n");
        } else {
            sb.append("  <span class=\"paginator-prev paginator-disabled\">\u2190 Prev</span>\n");
        }

        // Page numbers
        sb.append("  <span class=\"paginator-pages\">");
        for (int k = 1; k <= totalPages; k++) {
            String href = k == 1 ? absBaseHref : absBaseHref + "page/" + k + "/";
            if (k == page) {
                sb.append("<span class=\"paginator-current\">").append(k).append("</span>");
            } else {
                sb.append("<a href=\"").append(href).append("\" class=\"paginator-page\">").append(k).append("</a>");
            }
        }
        sb.append("</span>\n");

        // Next
        if (page < totalPages) {
            String nextHref = absBaseHref + "page/" + (page + 1) + "/";
            sb.append("  <a href=\"").append(nextHref).append("\" class=\"paginator-next\">Next \u2192</a>\n");
        } else {
            sb.append("  <span class=\"paginator-next paginator-disabled\">Next \u2192</span>\n");
        }

        sb.append("</nav>\n");
        return sb.toString();
    }

    /** Strips {@code id} attributes from heading elements so they are excluded from TOC extraction. */
    static String stripHeadingIds(String html) {
        return html.replaceAll("<(h[23])(\\s[^>]*?)\\sid=\"[^\"]*\"", "<$1$2")
                   .replaceAll("<(h[23])\\sid=\"[^\"]*\"(\\s[^>]*)?>", "<$1$2>");
    }

    /** Creates a URL-safe ASCII slug from a tag string. */
    static String tagToSlug(String tag) {
        String s = tag.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "tag-" + Math.abs(tag.hashCode()) : s;
    }
}
