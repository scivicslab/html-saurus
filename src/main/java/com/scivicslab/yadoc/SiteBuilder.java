package com.scivicslab.yadoc;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SiteBuilder {

    private final Path docsDir;
    private final Path outDir;
    private final Parser parser;
    private final HtmlRenderer renderer;

    private static final Pattern NUM_PREFIX       = Pattern.compile("^(\\d+)_(.*)$");
    private static final Pattern ADMONITION_START = Pattern.compile("^:::(\\w+)(?:\\[([^\\]]*)])?\\s*$");
    private static final Pattern ADMONITION_END   = Pattern.compile("^:::\\s*$");

    // href: first reachable page link (for directories), or page link (for files)
    record SiteNode(String label, String href, boolean isDir, List<SiteNode> children) {}

    private final String siteName;

    public SiteBuilder(Path docsDir, Path outDir) {
        this(docsDir, outDir, docsDir.getParent().getFileName().toString());
    }

    public SiteBuilder(Path docsDir, Path outDir, String siteName) {
        this.docsDir = docsDir;
        this.outDir = outDir;
        this.siteName = siteName;
        var extensions = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            HeadingAnchorExtension.create()
        );
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder().extensions(extensions).build();
    }

    public void build() throws IOException {
        SiteNode root = buildTree(docsDir);

        Files.walkFileTree(docsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = docsDir.relativize(file);
                if (file.toString().endsWith(".md")) {
                    convertPage(file, rel, root);
                } else {
                    Path dest = outDir.resolve(rel);
                    Files.createDirectories(dest.getParent());
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(outDir.resolve(docsDir.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
        });

        // Generate index.html that redirects to the first page
        String firstHref = root.href(); // e.g. "/Cluster-IaC/foo.html"
        if (firstHref != null) {
            String target = firstHref.startsWith("/") ? firstHref.substring(1) : firstHref;
            String indexHtml = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<meta http-equiv=\"refresh\" content=\"0;url=" + target + "\">"
                + "<title>Redirecting...</title></head><body>"
                + "<p>Redirecting to <a href=\"" + target + "\">" + target + "</a>...</p>"
                + "</body></html>\n";
            Files.writeString(outDir.resolve("index.html"), indexHtml);
        }
    }

    private SiteNode buildTree(Path dir) throws IOException {
        List<Path> entries;
        try (var stream = Files.list(dir)) {
            entries = stream.sorted(Comparator.comparing(p -> sortKey(p.getFileName().toString()))).toList();
        }

        String dirBase = stripNumericPrefix(dir.getFileName().toString());
        List<Path> mdFiles = entries.stream()
            .filter(e -> !Files.isDirectory(e) && e.toString().endsWith(".md"))
            .toList();
        boolean hasSubdirs = entries.stream().anyMatch(Files::isDirectory);

        // Find a same-name .md file if it exists
        Path sameNameMd = null;
        for (Path md : mdFiles) {
            String mdBase = stripNumericPrefix(stripExtension(md.getFileName().toString()));
            if (dirBase.equals(mdBase)) {
                sameNameMd = md;
                break;
            }
        }

        // Docusaurus convention: if the directory contains ONLY a same-name .md file
        // (no subdirs, no other .md files), treat it as a direct leaf page link.
        if (sameNameMd != null && !hasSubdirs && mdFiles.size() == 1) {
            String[] fm = parseFrontmatter(Files.readString(sameNameMd));
            String title = fm[0].isBlank() ? dirBase : fm[0];
            Path rel = docsDir.relativize(sameNameMd);
            String href = "/" + rel.toString().replace('\\', '/').replaceAll("\\.md$", ".html");
            return new SiteNode(title, href, false, List.of());
        }

        // Build category. If there's a same-name .md, use it as label source and
        // include it as the first child (index page); exclude it from normal child iteration.
        String label;
        List<SiteNode> children = new ArrayList<>();
        if (sameNameMd != null) {
            String[] fm = parseFrontmatter(Files.readString(sameNameMd));
            label = fm[0].isBlank() ? dirBase : fm[0];
            Path rel = docsDir.relativize(sameNameMd);
            String href = "/" + rel.toString().replace('\\', '/').replaceAll("\\.md$", ".html");
            // Add the index page as first child
            children.add(new SiteNode(label, href, false, List.of()));
        } else {
            label = readCategoryLabel(dir);
        }

        for (Path entry : entries) {
            if (entry.equals(sameNameMd)) continue; // already added above
            if (Files.isDirectory(entry)) {
                children.add(buildTree(entry));
            } else if (entry.toString().endsWith(".md")) {
                String[] fm = parseFrontmatter(Files.readString(entry));
                String title = fm[0].isBlank()
                    ? stripNumericPrefix(stripExtension(entry.getFileName().toString())) : fm[0];
                Path rel = docsDir.relativize(entry);
                String href = "/" + rel.toString().replace('\\', '/').replaceAll("\\.md$", ".html");
                children.add(new SiteNode(title, href, false, List.of()));
            }
        }

        String firstHref = firstHref(children);
        return new SiteNode(label, firstHref, true, children);
    }

    // Find the first page href in a subtree
    private String firstHref(List<SiteNode> nodes) {
        for (SiteNode n : nodes) {
            if (!n.isDir()) return n.href();
            String h = firstHref(n.children());
            if (h != null) return h;
        }
        return null;
    }

    private String sortKey(String name) {
        var m = NUM_PREFIX.matcher(name);
        if (m.matches()) return String.format("%010d_%s", Long.parseLong(m.group(1)), m.group(2));
        return name;
    }

    private String stripNumericPrefix(String name) {
        var m = NUM_PREFIX.matcher(name);
        return m.matches() ? m.group(2) : name;
    }

    private String readCategoryLabel(Path dir) {
        Path cat = dir.resolve("_category_.json");
        if (Files.exists(cat)) {
            try {
                String json = Files.readString(cat);
                var m = Pattern.compile("\"label\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                if (m.find()) return m.group(1);
            } catch (IOException ignored) {}
        }
        return stripNumericPrefix(dir.getFileName().toString());
    }

    private void convertPage(Path mdFile, Path rel, SiteNode root) throws IOException {
        String source = Files.readString(mdFile);
        String[] fm = parseFrontmatter(source);
        String title = fm[0].isBlank()
            ? stripNumericPrefix(stripExtension(mdFile.getFileName().toString())) : fm[0];
        String body = fm[1];
        String contentHtml = convertMarkdown(body);

        String relStr = rel.toString().replace('\\', '/').replaceAll("\\.md$", ".html");
        Path outFile = outDir.resolve(relStr);
        Files.createDirectories(outFile.getParent());

        long depth = rel.getNameCount() - 1;
        String prefix = "../".repeat((int) depth);
        if (prefix.isEmpty()) prefix = "./";

        String currentPath = "/" + relStr;

        // Determine which top-level section this page belongs to
        String topSection = rel.getNameCount() > 1 ? "/" + rel.getName(0).toString() : null;

        String html = renderPage(title, contentHtml, root, prefix, currentPath, topSection);
        Files.writeString(outFile, html);
        System.out.println("  " + outFile);
    }

    private String convertMarkdown(String markdown) {
        StringBuilder result = new StringBuilder();
        StringBuilder normalBuffer = new StringBuilder();
        String[] lines = markdown.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            Matcher m = ADMONITION_START.matcher(lines[i]);
            if (m.matches()) {
                // Flush pending normal markdown
                if (normalBuffer.length() > 0) {
                    result.append(renderMarkdown(normalBuffer.toString()));
                    normalBuffer.setLength(0);
                }
                String type = m.group(1).toLowerCase();
                String customTitle = m.group(2); // null if not specified
                StringBuilder inner = new StringBuilder();
                i++;
                while (i < lines.length && !ADMONITION_END.matcher(lines[i]).matches()) {
                    inner.append(lines[i]).append("\n");
                    i++;
                }
                result.append(renderAdmonition(type, customTitle, inner.toString()));
            } else {
                normalBuffer.append(lines[i]).append("\n");
            }
            i++;
        }
        if (normalBuffer.length() > 0) {
            result.append(renderMarkdown(normalBuffer.toString()));
        }
        return result.toString();
    }

    private String renderMarkdown(String markdown) {
        var doc = parser.parse(markdown);
        String html = renderer.render(doc);
        // Replace fenced mermaid code blocks with mermaid div
        html = html.replaceAll(
            "(?s)<pre><code class=\"language-mermaid\">(.*?)</code></pre>",
            "<div class=\"mermaid\">$1</div>"
        );
        return html;
    }

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
            escapeHtml(type), bg, color, color, escapeHtml(title), innerHtml
        );
    }

    private String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String[] parseFrontmatter(String source) {
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

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }

    private String renderPage(String title, String content, SiteNode root,
                               String prefix, String currentPath, String topSection) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            <!DOCTYPE html>
            <html lang="ja">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>%s</title>
              <link rel="icon" type="image/png" href="data:image/png;base64,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">
              <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
              <style>
                /* ---- Themes (CSS variables) ---- */
                :root {
                  --c-text:#1c1e21; --c-bg:#fff; --c-sidebar-bg:#f6f7f8; --c-border:#e3e4e5;
                  --c-hover:#e3e4e5; --c-nav-bg:#1c1e21; --c-nav-border:#333; --c-nav-accent:#2e8555;
                  --c-act-bg:#e8f4fd; --c-act-text:#1877f2; --c-cat-label:#444; --c-arrow:#888;
                  --c-pre-bg:#f6f7f8; --c-code-bg:#f0f0f0; --c-th-bg:#f6f7f8;
                  --c-bq-border:#ddd; --c-bq-text:#555; --c-h2-border:#eee;
                }
                [data-theme="warm"] {
                  --c-text:#2d1f00; --c-bg:#fdf6e3; --c-sidebar-bg:#f5ead0; --c-border:#d9c89a;
                  --c-hover:#ecddb8; --c-nav-bg:#4a3820; --c-nav-border:#6b5030; --c-nav-accent:#c8860a;
                  --c-act-bg:#fef3d0; --c-act-text:#b07a0a; --c-cat-label:#5c4010; --c-arrow:#a08050;
                  --c-pre-bg:#f5ead0; --c-code-bg:#ede4c8; --c-th-bg:#f0e5c8;
                  --c-bq-border:#c8a870; --c-bq-text:#6b5030; --c-h2-border:#ddd0a8;
                }
                [data-theme="blue"] {
                  --c-text:#1a2540; --c-bg:#f0f4ff; --c-sidebar-bg:#e8edf8; --c-border:#c5d0e8;
                  --c-hover:#d8e2f5; --c-nav-bg:#1a3a6b; --c-nav-border:#2a4a7b; --c-nav-accent:#4a9fe8;
                  --c-act-bg:#ddeeff; --c-act-text:#1a6fd4; --c-cat-label:#2a3a60; --c-arrow:#6080b0;
                  --c-pre-bg:#e8edf8; --c-code-bg:#dde4f5; --c-th-bg:#e2e8f5;
                  --c-bq-border:#90aad8; --c-bq-text:#3a5080; --c-h2-border:#c8d5ee;
                }
                [data-theme="green"] {
                  --c-text:#1a3020; --c-bg:#f0f8f0; --c-sidebar-bg:#e4f0e4; --c-border:#b8d8b8;
                  --c-hover:#d0e8d0; --c-nav-bg:#1a4a2a; --c-nav-border:#2a5a3a; --c-nav-accent:#40c060;
                  --c-act-bg:#d5f0d5; --c-act-text:#1a8040; --c-cat-label:#2a4a30; --c-arrow:#508050;
                  --c-pre-bg:#e4f0e4; --c-code-bg:#d8ecd8; --c-th-bg:#deeede;
                  --c-bq-border:#80c080; --c-bq-text:#3a5a3a; --c-h2-border:#c0dcc0;
                }
                [data-theme="red"] {
                  --c-text:#3a1010; --c-bg:#fff5f5; --c-sidebar-bg:#fce8e8; --c-border:#e8c0c0;
                  --c-hover:#f5d8d8; --c-nav-bg:#5a1a1a; --c-nav-border:#7a2a2a; --c-nav-accent:#e04040;
                  --c-act-bg:#fde0e0; --c-act-text:#c02020; --c-cat-label:#5a2020; --c-arrow:#a06060;
                  --c-pre-bg:#fce8e8; --c-code-bg:#f8dede; --c-th-bg:#faeaea;
                  --c-bq-border:#d09090; --c-bq-text:#703030; --c-h2-border:#e8c8c8;
                }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                       font-size: 16px; color: var(--c-text); background: var(--c-bg);
                       display: flex; flex-direction: column; min-height: 100vh; }
                /* ---- Top navbar ---- */
                header { background: var(--c-nav-bg); color: #fff; display: flex; align-items: center;
                         padding: 0 1.5rem; height: 60px; position: sticky; top: 0; z-index: 100;
                         border-bottom: 1px solid var(--c-nav-border); flex-shrink: 0; }
                header .site-title { font-weight: 700; font-size: 1.1rem; color: #fff;
                                     text-decoration: none; margin-right: 2rem; white-space: nowrap; }
                header nav.top { display: flex; gap: 0; height: 100%%; flex: 1; }
                header nav.top a { display: flex; align-items: center; padding: 0 1rem;
                                   color: #ccc; text-decoration: none; font-size: 0.875rem;
                                   border-bottom: 3px solid transparent; white-space: nowrap; }
                header nav.top a:hover { color: #fff; }
                header nav.top a.active { color: #fff; border-bottom-color: var(--c-nav-accent); }
                /* ---- Theme switcher ---- */
                #rebuild-btn { margin-left: auto; background: transparent; border: 1px solid #555;
                               color: #ccc; border-radius: 4px; padding: 0.2rem 0.6rem;
                               font-size: 0.8rem; cursor: pointer; white-space: nowrap; }
                #rebuild-btn:hover:not(:disabled) { background: rgba(255,255,255,0.1); color: #fff; }
                #rebuild-btn:disabled { opacity: 0.5; cursor: not-allowed; }
                #theme-sel { background: transparent; color: #ccc;
                             border: 1px solid #666; border-radius: 4px; padding: 3px 6px;
                             font-size: 0.8rem; cursor: pointer; }
                #theme-sel option { background: #2c2c2c; color: #fff; }
                /* ---- Search ---- */
                #search-wrap { position: relative; margin-left: 1rem; }
                #search-input { background: rgba(255,255,255,0.12); border: 1px solid #666; border-radius: 4px;
                                padding: 4px 10px; color: #fff; font-size: 0.875rem; width: 200px; outline: none; }
                #search-input::placeholder { color: #aaa; }
                #search-input:focus { background: rgba(255,255,255,0.22); border-color: #aaa; width: 260px; transition: width 0.2s; }
                #search-results { position: absolute; top: calc(100%% + 6px); right: 0; width: 400px;
                                  background: var(--c-bg); border: 1px solid var(--c-border); border-radius: 6px;
                                  box-shadow: 0 4px 20px rgba(0,0,0,0.18); z-index: 200;
                                  display: none; max-height: 500px; overflow-y: auto; }
                #search-results.open { display: block; }
                .sr-item { padding: 10px 14px; border-bottom: 1px solid var(--c-border);
                           text-decoration: none; display: block; color: var(--c-text); }
                .sr-item:last-child { border-bottom: none; }
                .sr-item:hover { background: var(--c-hover); }
                .sr-title { font-weight: 600; font-size: 0.875rem; }
                .sr-breadcrumb { font-size: 0.72rem; color: #2e8555; margin-top: 1px;
                                 white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .sr-summary { font-size: 0.8rem; color: #888; margin-top: 2px;
                              white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .sr-empty { padding: 1rem; text-align: center; color: #888; font-size: 0.875rem; }
                /* ---- Content area ---- */
                .content-wrap { display: flex; flex: 1; overflow: hidden; }
                /* ---- Sidebar ---- */
                nav.side { width: 280px; min-width: 280px; background: var(--c-sidebar-bg);
                           border-right: 1px solid var(--c-border); padding: 1rem;
                           overflow-y: auto; height: calc(100vh - 60px); position: sticky; top: 60px; }
                nav.side ul { list-style: none; }
                nav.side ul li { margin: 1px 0; }
                nav.side ul li a { display: block; padding: 3px 8px; border-radius: 4px; color: var(--c-text);
                                   text-decoration: none; font-size: 0.875rem;
                                   white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                nav.side ul li a:hover { background: var(--c-hover); }
                nav.side ul li a.active { background: var(--c-act-bg); color: var(--c-act-text); font-weight: 600; }
                .cat-header { display: flex; align-items: center; cursor: pointer; user-select: none;
                              padding: 4px 8px; border-radius: 4px; margin-top: 4px; }
                .cat-header:hover { background: var(--c-hover); }
                .cat-label { font-weight: 600; font-size: 0.8rem; color: var(--c-cat-label); flex: 1; }
                .cat-arrow { font-size: 0.65rem; color: var(--c-arrow); transition: transform 0.15s; }
                .cat-children { overflow: hidden; padding-left: 12px; border-left: 2px solid var(--c-border);
                                margin-left: 8px; display: none; }
                .cat-children.open { display: block; }
                /* ---- Main ---- */
                main { flex: 1; padding: 2rem 3rem; max-width: 900px; overflow-y: auto; }
                main h1 { font-size: 2rem; margin-bottom: 1rem; }
                main h2 { font-size: 1.5rem; margin: 1.5rem 0 0.75rem; border-bottom: 1px solid var(--c-h2-border); padding-bottom: 0.25rem; }
                main h3 { font-size: 1.2rem; margin: 1.25rem 0 0.5rem; }
                main h4 { font-size: 1rem; margin: 1rem 0 0.5rem; }
                main p { margin: 0.75rem 0; line-height: 1.7; }
                main ul, main ol { margin: 0.75rem 0 0.75rem 1.5rem; line-height: 1.7; }
                main pre { background: var(--c-pre-bg); border: 1px solid var(--c-border); border-radius: 6px;
                            padding: 1rem; overflow-x: auto; margin: 1rem 0; }
                main code { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 0.875em; }
                main p code, main li code, main td code { background: var(--c-code-bg); padding: 1px 5px; border-radius: 3px; }
                main table { border-collapse: collapse; width: 100%%; margin: 1rem 0; }
                main th, main td { border: 1px solid var(--c-border); padding: 8px 12px; text-align: left; }
                main th { background: var(--c-th-bg); font-weight: 600; }
                main blockquote { border-left: 4px solid var(--c-bq-border); padding-left: 1rem; color: var(--c-bq-text); margin: 1rem 0; }
                main img { max-width: 100%%; }
                /* ---- Admonitions ---- */
                .admonition { border-radius: 6px; padding: 0.75rem 1rem; margin: 1rem 0; }
                .admonition-title { font-weight: 700; font-size: 0.875rem; text-transform: uppercase;
                                    letter-spacing: 0.05em; margin-bottom: 0.5rem; }
                .admonition-body > *:last-child { margin-bottom: 0; }
              </style>
              <script>(function(){
                var t=localStorage.getItem('md2html-theme');
                if(t&&t!=='default') document.documentElement.setAttribute('data-theme',t);
              })();</script>
            </head>
            <body>
            """.formatted(title));

        // Top navbar
        sb.append("<header>\n");
        sb.append("  <a class=\"site-title\" href=\"").append(prefix).append("index.html\">").append(escapeHtml(siteName)).append("</a>\n");
        sb.append("  <nav class=\"top\">\n");
        for (SiteNode section : root.children()) {
            if (!section.isDir()) continue; // skip top-level .md files like intro.md
            String sectionPath = "/" + section.label(); // not reliable; use href prefix instead
            // Determine if this section is active: currentPath starts with the section's subtree
            boolean active = topSection != null &&
                topSection.equals("/" + dirNameForSection(section, root));
            String href = section.href() != null ? prefix + section.href().replaceFirst("^/", "") : "#";
            sb.append("    <a href=\"").append(href).append("\"")
              .append(active ? " class=\"active\"" : "").append(">")
              .append(escapeHtml(section.label())).append("</a>\n");
        }
        sb.append("  </nav>\n");
        sb.append("  <button id=\"rebuild-btn\" title=\"Rebuild this project\">&#x21BB; Rebuild</button>\n");
        sb.append("  <select id=\"theme-sel\">\n");
        sb.append("    <option value=\"default\">Default</option>\n");
        sb.append("    <option value=\"warm\">Warm</option>\n");
        sb.append("    <option value=\"blue\">Blue</option>\n");
        sb.append("    <option value=\"green\">Green</option>\n");
        sb.append("    <option value=\"red\">Red</option>\n");
        sb.append("  </select>\n");
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
                if (section.isDir() && topSection.equals("/" + dirNameForSection(section, root))) {
                    sidebarNodes = section.children();
                    break;
                }
            }
        }
        renderSidebar(sb, sidebarNodes, prefix, currentPath);
        sb.append("</nav>\n");

        // Main content
        sb.append("<main>\n<h1>").append(title).append("</h1>\n");
        sb.append(content);
        sb.append("</main>\n</div>\n");

        sb.append("""
            <script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
            <script>mermaid.initialize({startOnLoad: true});</script>
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"
              onload="renderMathInElement(document.body, {delimiters: [
                {left:'$$',right:'$$',display:true},{left:'$',right:'$',display:false}]});"></script>
            <script>
            document.querySelectorAll('.cat-header').forEach(function(header) {
              header.addEventListener('click', function() {
                var children = this.nextElementSibling;
                var arrow = this.querySelector('.cat-arrow');
                children.classList.toggle('open');
                arrow.textContent = children.classList.contains('open') ? '▼' : '▶';
              });
            });
            (function() {
              var sel = document.getElementById('theme-sel');
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
                btn.addEventListener('click', function() {
                  btn.disabled = true; btn.textContent = 'Building\u2026';
                  fetch('/api/build/YADOC_PROJECT', {method: 'POST'})
                    .then(function(r) { return r.json(); })
                    .then(function(j) {
                      btn.textContent = j.status === 'ok'
                        ? '\u2713 Done (' + j.ms + 'ms)' : '\u2717 Error';
                      setTimeout(function() {
                        btn.textContent = '\u21BB Rebuild'; btn.disabled = false;
                      }, 3000);
                    })
                    .catch(function() {
                      btn.textContent = '\u21BB Rebuild'; btn.disabled = false;
                    });
                });
              })();
              var SEARCH_URL = 'YADOC_SEARCH_URL';
              function doSearch(q) {
                fetch(SEARCH_URL + '?q=' + encodeURIComponent(q))
                  .then(function(r) { return r.json(); })
                  .then(function(data) {
                    results.innerHTML = '';
                    if (!data.length) {
                      results.innerHTML = '<div class="sr-empty">No results.</div>';
                    } else {
                      data.forEach(function(item) {
                        var a = document.createElement('a');
                        a.className = 'sr-item'; a.href = item.path;
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
                }).filter(function(s) { return s.length > 0; }).join(' \u203a ');
              }
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
            </script>
            </body></html>
            """);

        return sb.toString()
            .replace("YADOC_SEARCH_URL", prefix + "search")
            .replace("YADOC_PROJECT", siteName);
    }

    // Map a top-level SiteNode back to the actual directory name on disk.
    // We derive it from the first href segment of the section.
    private String dirNameForSection(SiteNode section, SiteNode root) {
        // The first segment of the section's href is the directory name
        if (section.href() == null) return "";
        String h = section.href().replaceFirst("^/", "");
        int slash = h.indexOf('/');
        return slash >= 0 ? h.substring(0, slash) : h.replace(".html", "");
    }

    private void renderSidebar(StringBuilder sb, List<SiteNode> nodes, String prefix, String currentPath) {
        sb.append("<ul>\n");
        for (SiteNode node : nodes) {
            if (node.isDir()) {
                sb.append("<li>\n");
                sb.append("  <div class=\"cat-header\">")
                  .append("<span class=\"cat-label\">").append(escapeHtml(node.label())).append("</span>")
                  .append("<span class=\"cat-arrow\">▶</span>")
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

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
