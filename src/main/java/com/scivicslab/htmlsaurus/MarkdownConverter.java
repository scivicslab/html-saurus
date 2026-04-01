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
