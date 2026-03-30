package com.scivicslab.htmlsaurus;

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
                main { flex: 1; padding: 2rem 3rem; max-width: 900px; overflow-y: auto; min-width: 0; }
                /* ---- Right-side TOC ---- */
                aside.toc { width: 220px; min-width: 220px; position: sticky; top: 60px;
                            height: calc(100vh - 60px); overflow-y: auto; padding: 1.5rem 0.5rem 1rem 0;
                            border-left: 1px solid var(--c-border); font-size: 0.8rem; }
                aside.toc .toc-title { font-weight: 600; font-size: 0.75rem; text-transform: uppercase;
                                       letter-spacing: 0.05em; color: var(--c-cat-label);
                                       padding: 0 0.75rem; margin-bottom: 0.5rem; }
                aside.toc ul { list-style: none; }
                aside.toc li { margin: 0; }
                aside.toc a { display: block; padding: 3px 0.75rem; color: var(--c-text); opacity: 0.7;
                              text-decoration: none; border-left: 2px solid transparent; line-height: 1.4; }
                aside.toc a:hover { opacity: 1; }
                aside.toc a.toc-active { opacity: 1; border-left-color: var(--c-act-text); color: var(--c-act-text); font-weight: 600; }
                aside.toc li.toc-h3 a { padding-left: 1.5rem; font-size: 0.76rem; }
                @media (max-width: 1100px) { aside.toc { display: none; } }
                /* ---- Copy buttons ---- */
                .copy-bar { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
                .copy-btn { background: var(--c-sidebar-bg); border: 1px solid var(--c-border); border-radius: 4px;
                            padding: 4px 10px; font-size: 0.75rem; cursor: pointer; color: var(--c-text);
                            transition: background 0.15s; }
                .copy-btn:hover { background: var(--c-hover); }
                .copy-btn.copied { background: #2e8555; color: #fff; border-color: #2e8555; }
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
        sb.append("<div class=\"copy-bar\">");
        sb.append("<button class=\"copy-btn\" id=\"copy-text-btn\" title=\"Copy as plain text\">&#x1F4CB; Text</button>");
        sb.append("<button class=\"copy-btn\" id=\"copy-md-btn\" title=\"Copy as Markdown\">&#x1F4DD; Markdown</button>");
        sb.append("</div>\n");
        sb.append(content);
        sb.append("</main>\n");

        // Right-side TOC: extract h2/h3 headings from content HTML
        sb.append(buildToc(content));
        sb.append("</div>\n");

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
            // Copy buttons
            (function() {
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

    private static final Pattern HEADING_PATTERN = Pattern.compile(
        "<h([23])\\s[^>]*id=\"([^\"]+)\"[^>]*>(.*?)</h[23]>", Pattern.DOTALL);

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

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
