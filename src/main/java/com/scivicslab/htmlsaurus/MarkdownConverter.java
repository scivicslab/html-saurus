package com.scivicslab.htmlsaurus;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Markdown source text to HTML, handling Docusaurus-style admonitions
 * ({@code :::type[Title] ... :::}) and YAML frontmatter extraction.
 *
 * <p>Owns the CommonMark {@link Parser} and {@link HtmlRenderer} instances,
 * which are created once and reused across all conversions.
 */
class MarkdownConverter {

    /**
     * Matches the opening line of a Docusaurus admonition.
     * Supports both bracket syntax ({@code :::note[Title]}) and
     * space-separated syntax ({@code :::note Title}) used in older Docusaurus docs.
     * Group 1: type, Group 2: bracket title, Group 3: space-separated title.
     */
    private static final Pattern ADMONITION_START = Pattern.compile("^:::(\\w+)(?:\\[([^\\]]*)]|[ \\t]+(.+?))?\\s*$");
    /** Matches the closing line of a Docusaurus admonition ({@code :::}). */
    private static final Pattern ADMONITION_END   = Pattern.compile("^:::\\s*$");
    /** Matches the opening characters of a fenced code block (``` or ~~~). */
    private static final Pattern FENCE_OPEN       = Pattern.compile("^(`{3,}|~{3,})");

    private final Parser parser;
    private final HtmlRenderer renderer;

    MarkdownConverter() {
        var extensions = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            HeadingAnchorExtension.create()
        );
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder().extensions(extensions).build();
    }

    /**
     * Converts Markdown to HTML, handling Docusaurus-style admonitions ({@code :::note ... :::})
     * as a pre-processing step before passing through the CommonMark parser.
     * Admonitions inside fenced code blocks are passed through unchanged.
     */
    String convertMarkdown(String markdown) {
        // CommonMark ends an HTML block (type 6) at a blank line.
        // Tables with blank lines inside cells would be split: post-blank content
        // rendered as Markdown, and 4-space-indented <td> tags as code blocks.
        // Pre-processing: collapse blank lines within <table>...</table> blocks.
        markdown = normalizeHtmlTableBlocks(markdown);
        StringBuilder result = new StringBuilder();
        StringBuilder normalBuffer = new StringBuilder();
        String[] lines = markdown.split("\n", -1);
        int i = 0;
        String openFence = null; // non-null when inside a fenced code block
        while (i < lines.length) {
            String line = lines[i];
            // Track fenced code block boundaries (``` or ~~~~)
            if (openFence == null) {
                java.util.regex.Matcher fence = FENCE_OPEN.matcher(line);
                if (fence.find()) {
                    openFence = fence.group(1); // remember the opening fence characters
                    normalBuffer.append(line).append("\n");
                    i++;
                    continue;
                }
            } else {
                // Inside a fence: look for a closing fence of the same type and length
                if (line.startsWith(openFence) && line.trim().equals(openFence)) {
                    openFence = null;
                }
                normalBuffer.append(line).append("\n");
                i++;
                continue;
            }

            Matcher m = ADMONITION_START.matcher(line);
            if (m.matches()) {
                // Flush pending normal markdown
                if (normalBuffer.length() > 0) {
                    result.append(renderMarkdown(normalBuffer.toString()));
                    normalBuffer.setLength(0);
                }
                String type = m.group(1).toLowerCase();
                // group(2) = bracket title [Title], group(3) = space-separated title
                String customTitle = m.group(2) != null ? m.group(2) : m.group(3);
                StringBuilder inner = new StringBuilder();
                i++;
                while (i < lines.length && !ADMONITION_END.matcher(lines[i]).matches()) {
                    inner.append(lines[i]).append("\n");
                    i++;
                }
                result.append(renderAdmonition(type, customTitle, inner.toString()));
            } else {
                normalBuffer.append(line).append("\n");
            }
            i++;
        }
        if (normalBuffer.length() > 0) {
            result.append(renderMarkdown(normalBuffer.toString()));
        }
        return result.toString();
    }

    /** Renders a block of Markdown to HTML, converting fenced mermaid code blocks to {@code <div class="mermaid">}. */
    private String renderMarkdown(String markdown) {
        var doc = parser.parse(markdown);
        String html = renderer.render(doc);
        // Replace fenced mermaid code blocks with mermaid div
        html = html.replaceAll(
            "(?s)<pre><code class=\"language-mermaid\">(.*?)</code></pre>",
            "<div class=\"mermaid\">$1</div>"
        );
        // Docusaurus-style heading ID override: ## Title {#my-id} sets id="my-id" on the element.
        // The {#id} marker is removed from the visible heading text.
        html = html.replaceAll(
            "(<h[1-6][^>]*?) id=\"[^\"]*\"([^>]*>)(.*?)\\{#([^}]+)\\}(.*?)(</h[1-6]>)",
            "$1 id=\"$4\"$2$3$5$6"
        );
        return html;
    }

    /**
     * Renders a Docusaurus-style admonition as a styled HTML block.
     * Supports types: note, info, tip, warning, caution, danger.
     */
    private String renderAdmonition(String type, String customTitle, String innerMarkdown) {
        Map<String, String[]> types = Map.of(
            "note",    new String[]{"Note",    "#4cb3d4", "#eef9fd"},
            "info",    new String[]{"Info",    "#2196f3", "#ebf8ff"},
            "tip",     new String[]{"Tip",     "#009400", "#e6f6e6"},
            "warning", new String[]{"Warning", "#e6a700", "#fff8e6"},
            "caution", new String[]{"Caution", "#e6a700", "#fff8e6"},
            "danger",  new String[]{"Danger",  "#e13238", "#ffebec"}
        );
        String[] meta = types.getOrDefault(type, new String[]{
            capitalize(type), "#888", "#f8f8f8"
        });
        String title = customTitle != null ? customTitle : meta[0];
        String color = meta[1];
        String bg    = meta[2];
        String innerHtml = renderMarkdown(innerMarkdown.strip());
        return String.format(
            "<div class=\"admonition admonition-%s\" style=\"background:%s;border-left:4px solid %s;\">" +
            "<div class=\"admonition-title\" style=\"color:%s;\">%s</div>" +
            "<div class=\"admonition-body\">%s</div></div>\n",
            SiteBuilder.escapeHtml(type), bg, color, color, SiteBuilder.escapeHtml(title), innerHtml
        );
    }

    private String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Matches a Markdown image: {@code ![alt](url)} — must be tried before {@link #MD_LINK}.
     * Group 1: alt text, Group 2: URL.
     */
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    /**
     * Matches a Markdown inline link: {@code [text](url)}.
     * Group 1: link text (may contain nested brackets), Group 2: URL.
     */
    private static final Pattern MD_LINK  = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    /**
     * Matches a run of consecutive Markdown unordered list lines (lines starting with
     * {@code -}, {@code *}, or {@code +} followed by a space).
     */
    private static final Pattern MD_LIST_BLOCK = Pattern.compile(
        "(?:^[ \\t]*[-*+][ \\t]+.+(?:\\n|$))+", Pattern.MULTILINE);

    /**
     * Removes blank lines within {@code <table>...</table>} blocks so that CommonMark
     * does not terminate the HTML block at blank lines inside cells.
     * Also converts Markdown inline syntax (images and links) inside table blocks to HTML,
     * because CommonMark does not process Markdown inside HTML blocks.
     * Handles nested tables via depth counting.
     */
    private static String normalizeHtmlTableBlocks(String markdown) {
        StringBuilder result = new StringBuilder();
        int pos = 0;
        while (pos < markdown.length()) {
            int tableOpen = markdown.indexOf("<table", pos);
            if (tableOpen == -1) {
                result.append(markdown, pos, markdown.length());
                break;
            }
            result.append(markdown, pos, tableOpen);
            int tableClose = findMatchingTableClose(markdown, tableOpen);
            if (tableClose == -1) {
                result.append(markdown, tableOpen, markdown.length());
                break;
            }
            String tableBlock = markdown.substring(tableOpen, tableClose);
            // Collapse consecutive blank lines so CommonMark does not split the HTML block
            tableBlock = tableBlock.replaceAll("\n([ \t]*\n)+", "\n");
            // Convert Markdown inline syntax to HTML (images first, then links)
            tableBlock = convertInlineMarkdownToHtml(tableBlock);
            result.append(tableBlock);
            pos = tableClose;
        }
        return result.toString();
    }

    /**
     * Converts Markdown inline images and links within a string to their HTML equivalents.
     * Images ({@code ![alt](url)}) are processed before links to avoid partial matches.
     */
    private static String convertInlineMarkdownToHtml(String text) {
        // Unordered lists: groups of "- item" lines → <ul><li>...</li></ul>
        Matcher listM = MD_LIST_BLOCK.matcher(text);
        StringBuffer sb0 = new StringBuffer();
        while (listM.find()) {
            StringBuilder ul = new StringBuilder("<ul>\n");
            for (String line : listM.group().split("\\n")) {
                if (!line.isBlank()) {
                    String item = line.replaceFirst("^[ \\t]*[-*+][ \\t]+", "");
                    ul.append("<li>").append(item).append("</li>\n");
                }
            }
            ul.append("</ul>");
            listM.appendReplacement(sb0, Matcher.quoteReplacement(ul.toString()));
        }
        listM.appendTail(sb0);
        text = sb0.toString();

        // Images: ![alt](url) → <img src="url" alt="alt">
        Matcher imgM = MD_IMAGE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (imgM.find()) {
            String alt = imgM.group(1).replace("\"", "&quot;");
            String url = imgM.group(2).trim();
            imgM.appendReplacement(sb, Matcher.quoteReplacement(
                "<img src=\"" + url + "\" alt=\"" + alt + "\">"));
        }
        imgM.appendTail(sb);
        text = sb.toString();

        // Links: [text](url) → <a href="url">text</a>
        Matcher linkM = MD_LINK.matcher(text);
        StringBuffer sb2 = new StringBuffer();
        while (linkM.find()) {
            String linkText = linkM.group(1);
            String url = linkM.group(2).trim();
            linkM.appendReplacement(sb2, Matcher.quoteReplacement(
                "<a href=\"" + url + "\">" + linkText + "</a>"));
        }
        linkM.appendTail(sb2);
        return sb2.toString();
    }

    /** Returns the index just past the {@code </table>} that matches the {@code <table} at {@code openPos}. */
    private static int findMatchingTableClose(String markdown, int openPos) {
        int depth = 1;
        int pos = openPos + 6; // skip "<table"
        while (pos < markdown.length() && depth > 0) {
            int nextOpen  = markdown.indexOf("<table",  pos);
            int nextClose = markdown.indexOf("</table>", pos);
            if (nextClose == -1) return -1;
            if (nextOpen != -1 && nextOpen < nextClose) {
                depth++;
                pos = nextOpen + 6;
            } else {
                depth--;
                pos = nextClose + 8;
            }
        }
        return depth == 0 ? pos : -1;
    }

    /**
     * Extracts YAML frontmatter and body from a Markdown source.
     * If a leading {@code # heading} is present, it is used as the title (when not set in frontmatter)
     * and stripped from the body to avoid duplication with the rendered {@code <h1>}.
     *
     * @param source the raw Markdown source
     * @return a two-element array: {@code [title, body]}
     */
    String[] parseFrontmatter(String source) {
        String title = "";
        String body = source;
        if (source.startsWith("---")) {
            int end = source.indexOf("\n---", 3);
            if (end != -1) {
                String fm = source.substring(3, end);
                body = source.substring(end + 4).stripLeading();
                for (String line : fm.split("\n")) {
                    if (line.startsWith("title:")) {
                        title = line.substring(6).trim().replaceAll("^\"|\"$", "");
                    }
                }
            }
        }
        // If body starts with a # heading, use it as title (if none from frontmatter)
        // and always strip it so it doesn't duplicate the <h1> we render explicitly.
        if (body.startsWith("# ")) {
            int nl = body.indexOf('\n');
            String headingTitle = (nl >= 0 ? body.substring(2, nl) : body.substring(2)).trim();
            body = nl >= 0 ? body.substring(nl + 1).stripLeading() : "";
            if (title.isBlank()) title = headingTitle;
        }
        return new String[]{title, body};
    }
}
