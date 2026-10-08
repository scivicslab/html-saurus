package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates sitemap.xml, rss.xml, and feed.json for the built site.
 */
class FeedGenerator {

    private final Path outDir;
    private final String siteUrl;
    private final String siteName;
    private final String currentLocale;
    private final String defaultLocale;
    private final List<SiteBuilder.PageInfo> builtPages;

    FeedGenerator(Path outDir, String siteUrl, String siteName,
                  String currentLocale, String defaultLocale,
                  List<SiteBuilder.PageInfo> builtPages) {
        this.outDir = outDir;
        this.siteUrl = siteUrl;
        this.siteName = siteName;
        this.currentLocale = currentLocale;
        this.defaultLocale = defaultLocale;
        this.builtPages = builtPages;
    }

    /** Generates {@code sitemap.xml} in the output directory listing all built pages. */
    void generateSitemap() throws IOException {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        List<SiteBuilder.PageInfo> sorted = new ArrayList<>(builtPages);
        sorted.sort(Comparator.comparing(SiteBuilder.PageInfo::absoluteUrl));
        for (SiteBuilder.PageInfo p : sorted) {
            sb.append("  <url><loc>").append(escapeHtml(p.absoluteUrl())).append("</loc></url>\n");
        }
        sb.append("</urlset>\n");
        Path out = outDir.resolve("sitemap.xml");
        Files.writeString(out, sb.toString());
        System.out.println("  " + out);
    }

    /** Generates {@code rss.xml} (RSS 2.0) in the output directory. */
    void generateRssFeed() throws IOException {
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
        List<SiteBuilder.PageInfo> sorted = new ArrayList<>(builtPages);
        sorted.sort(Comparator.comparing(SiteBuilder.PageInfo::absoluteUrl));
        for (SiteBuilder.PageInfo p : sorted) {
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
    void generateJsonFeed() throws IOException {
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
        List<SiteBuilder.PageInfo> sorted = new ArrayList<>(builtPages);
        sorted.sort(Comparator.comparing(SiteBuilder.PageInfo::absoluteUrl));
        for (int i = 0; i < sorted.size(); i++) {
            SiteBuilder.PageInfo p = sorted.get(i);
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
    static String gitLastModifiedIso(Path filePath) {
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
    static String plainText(String markdown, int maxLen) {
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
    static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
