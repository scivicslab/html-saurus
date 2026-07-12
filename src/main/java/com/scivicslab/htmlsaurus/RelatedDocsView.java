package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Shared rendering for the related-documents and semantic-search features, used
 * by both the portal server ({@link PortalServer}) and the single-project server
 * ({@link SearchServer}) so the JSON shape, the standalone pages, and the
 * injected "Related (semantic)" widget stay identical across modes.
 *
 * <p>All inputs are hit lists of {@code {path,title,summary}} maps; the caller is
 * responsible for producing them (TF-IDF via Lucene, doc-to-doc neighbours or
 * query-to-doc search via {@link SemanticIndex}) with URLs already in the
 * server's own scheme.
 */
final class RelatedDocsView {

    private RelatedDocsView() {}

    /** Writes a hit list as a JSON array of {@code {id,title,path,srcPath,summary}} objects.
     *  {@code id} (doc id) and {@code srcPath} (absolute source .md path) are emitted when present
     *  in the hit map; absent keys serialize as JSON null. */
    static void writeJson(HttpExchange ex, List<Map<String, String>> hits) throws IOException {
        var sb = new StringBuilder("[");
        boolean first = true;
        for (var hit : hits) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
              .append("\"id\":").append(HttpUtils.jsonStr(hit.get("id"))).append(",")
              .append("\"title\":").append(HttpUtils.jsonStr(hit.get("title"))).append(",")
              .append("\"path\":").append(HttpUtils.jsonStr(hit.get("path"))).append(",")
              .append("\"srcPath\":").append(HttpUtils.jsonStr(hit.get("srcPath"))).append(",")
              .append("\"summary\":").append(HttpUtils.jsonStr(hit.get("summary")))
              .append("}");
        }
        sb.append("]");
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    /** Renders the standalone related-documents HTML page (used by {@code /related} and {@code /related-semantic}). */
    static String pageHtml(String heading, String docPath, List<Map<String, String>> hits) {
        var sb = new StringBuilder();
        sb.append(pageOpen(heading + ": " + (docPath == null ? "" : docPath)));
        if (docPath != null && !docPath.isBlank()) {
            sb.append("<p class=\"source-link\">Related documents for: <a href=\"")
              .append(HttpUtils.escapeHtml(docPath)).append("\">")
              .append(HttpUtils.escapeHtml(docPath)).append("</a></p>\n");
        }
        appendResults(sb, hits, "related document");
        sb.append("</main>\n</body>\n</html>\n");
        return sb.toString();
    }

    /**
     * Renders the semantic-search results page (used by {@code /search-semantic}),
     * including the shared "one form, three search types" query box.
     *
     * @param supportsTfidf whether the TF-IDF search type is offered (the caller's server
     *                      registers {@code /find-related}); {@link SearchServer} (single-project
     *                      mode) does not, so it passes {@code false} to omit that radio
     */
    static String searchResultsPage(String query, List<Map<String, String>> hits, boolean supportsTfidf) {
        var sb = new StringBuilder();
        sb.append(pageOpen("Semantic search"));
        sb.append("""
            <form onsubmit="doSearch(); return false;"
                  style="display:flex;gap:.5rem;margin-bottom:.75rem;flex-wrap:wrap;align-items:flex-start;">
              <textarea id="search-input" name="q" rows="6"
                        placeholder="Search by meaning, or paste a paragraph…"
                        style="flex:1;min-width:200px;padding:.5rem .75rem;border:1px solid #ccc;
                               border-radius:6px;font-size:.9rem;resize:vertical;font-family:inherit;
                               line-height:1.5;">%s</textarea>
              <button type="submit"
                      style="padding:.5rem 1rem;border:none;border-radius:6px;background:#2e8555;color:#fff;
                             font-size:.9rem;cursor:pointer;">Search</button>
            </form>
            <div style="margin-bottom:1.5rem;font-size:.85rem;color:#666;display:flex;gap:.9rem;align-items:center;">
            """.formatted(HttpUtils.escapeHtml(query == null ? "" : query)));
        sb.append(searchTypeRadios("embedding", supportsTfidf));
        sb.append("</div>\n");
        if (query != null && !query.isBlank()) {
            appendResults(sb, hits, "result");
        }
        sb.append(searchWidgetScript());
        sb.append("</main>\n</body>\n</html>\n");
        return sb.toString();
    }

    /**
     * Renders the {@code search-type} radio group (Keyword / TF-IDF / Embedding), pre-selecting
     * {@code selected} and omitting the TF-IDF radio when {@code supportsTfidf} is {@code false}.
     */
    static String searchTypeRadios(String selected, boolean supportsTfidf) {
        var sb = new StringBuilder();
        sb.append("<label><input type=\"radio\" name=\"search-type\" value=\"fulltext\"")
          .append("fulltext".equals(selected) ? " checked" : "").append(">Keyword</label>\n");
        if (supportsTfidf) {
            sb.append("<label><input type=\"radio\" name=\"search-type\" value=\"tfidf\"")
              .append("tfidf".equals(selected) ? " checked" : "").append(">TF-IDF</label>\n");
        }
        sb.append("<label><input type=\"radio\" name=\"search-type\" value=\"embedding\"")
          .append("embedding".equals(selected) ? " checked" : "").append(">Embedding</label>\n");
        return sb.toString();
    }

    /**
     * Shared dispatcher script for the "one form, three search types" widget (each search page
     * provides a {@code #search-input} text field and a {@code search-type} radio group; a
     * {@code lang} radio group is optional and only consulted for the {@code fulltext} type).
     * Server endpoints:
     * <pre>
     *   fulltext  -&gt; GET  /search?q=&amp;lang=      (Lucene full-text)
     *   tfidf     -&gt; POST /find-related (text)     (Lucene MoreLikeThis)
     *   embedding -&gt; GET  /search-semantic?q=      (vector similarity)
     * </pre>
     */
    static String searchWidgetScript() {
        return """
            <script>
            function doSearch() {
              const text = document.getElementById('search-input').value.trim();
              if (!text) { return; }
              const typeEl = document.querySelector('input[name="search-type"]:checked');
              const type = typeEl ? typeEl.value : 'fulltext';
              if (type === 'embedding') {
                window.open('/search-semantic?q=' + encodeURIComponent(text), '_blank');
              } else if (type === 'tfidf') {
                const form = document.createElement('form');
                form.method = 'POST'; form.action = '/find-related'; form.target = '_blank';
                form.style.display = 'none';
                const tInput = document.createElement('input');
                tInput.type = 'hidden'; tInput.name = 'text'; tInput.value = text;
                form.appendChild(tInput);
                document.body.appendChild(form);
                form.submit();
                document.body.removeChild(form);
              } else {
                const langEl = document.querySelector('input[name="lang"]:checked');
                const lang = langEl ? langEl.value : 'ja';
                window.open('/search?q=' + encodeURIComponent(text) + '&lang=' + encodeURIComponent(lang), '_blank');
              }
            }
            // #search-input is now a <textarea> (so it can hold a pasted paragraph); plain Enter
            // must keep inserting a newline, so Shift+Enter is the submit shortcut instead
            // (mirrors the homepage widget).
            document.getElementById('search-input').addEventListener('keydown', function(e) {
              if (e.key === 'Enter' && e.shiftKey) { e.preventDefault(); doSearch(); }
            });
            </script>
            """;
    }

    /** Returns the page chrome (doctype, head, CSS, header, open main) with the given title. */
    private static String pageOpen(String title) {
        return """
            <!DOCTYPE html>
            <html lang="ja">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>%s</title>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                       background: #f6f7f8; min-height: 100vh; }
                header { background: #1c1e21; color: #fff; padding: 1rem 2rem;
                         display: flex; align-items: center; gap: 1.5rem; }
                header a.home { color: #fff; text-decoration: none; font-weight: 700; font-size: 1.1rem; }
                header a.home:hover { color: #aaa; }
                main { max-width: 860px; margin: 2rem auto; padding: 0 1.5rem; }
                .source-link { font-size: 0.875rem; color: #555; margin-bottom: 1.5rem; }
                .source-link a { color: #2e8555; text-decoration: none; }
                .source-link a:hover { text-decoration: underline; }
                .result-count { color: #666; font-size: 0.875rem; margin-bottom: 1.5rem; }
                .result-count strong { color: #1c1e21; }
                .result { background: #fff; border: 1px solid #e3e4e5; border-radius: 8px;
                          padding: 1rem 1.25rem; margin-bottom: 0.75rem;
                          text-decoration: none; display: block; color: inherit;
                          transition: box-shadow 0.15s, border-color 0.15s; }
                .result:hover { box-shadow: 0 3px 10px rgba(0,0,0,0.08); border-color: #3578e5; }
                .result-project { font-size: 0.72rem; font-weight: 700; color: #3578e5;
                                  text-transform: uppercase; letter-spacing: 0.05em; }
                .result-title { font-size: 1rem; font-weight: 600; color: #1c1e21; margin: 0.2rem 0; }
                .result-breadcrumb { font-size: 0.75rem; color: #3578e5; margin-bottom: 0.3rem; }
                .result-summary { font-size: 0.82rem; color: #666; line-height: 1.5; }
                .no-results { color: #888; text-align: center; padding: 3rem; }
              </style>
            </head>
            <body>
            <header>
              <a class="home" href="/">Documentation Portal</a>
            </header>
            <main>
            """.formatted(HttpUtils.escapeHtml(title));
    }

    /** Appends the result-count line and the result cards (or a no-results message). */
    private static void appendResults(StringBuilder sb, List<Map<String, String>> hits, String noun) {
        sb.append("<p class=\"result-count\"><strong>").append(hits.size())
          .append("</strong> ").append(noun).append("(s) found</p>\n");
        if (hits.isEmpty()) {
            sb.append("<p class=\"no-results\">No ").append(noun).append("s found.</p>\n");
            return;
        }
        for (var hit : hits) {
            String href = hit.get("path");
            String displayProject = "";
            if (href != null && href.startsWith("/")) {
                int sl = href.indexOf('/', 1);
                displayProject = sl > 0 ? href.substring(1, sl) : href.substring(1);
            }
            sb.append("<a class=\"result\" href=\"").append(HttpUtils.escapeHtml(href != null ? href : "#")).append("\">\n");
            sb.append("  <div class=\"result-project\">").append(HttpUtils.escapeHtml(displayProject)).append("</div>\n");
            sb.append("  <div class=\"result-title\">").append(HttpUtils.escapeHtml(hit.get("title") != null ? hit.get("title") : "")).append("</div>\n");
            sb.append("  <div class=\"result-breadcrumb\">").append(HttpUtils.escapeHtml(breadcrumb(href))).append("</div>\n");
            sb.append("  <div class=\"result-summary\">").append(HttpUtils.escapeHtml(hit.get("summary") != null ? hit.get("summary") : "")).append("</div>\n");
            sb.append("</a>\n");
        }
    }

    /**
     * Returns the injectable "Related (semantic)" widget (green box) that fetches
     * {@code /api/related-semantic?path=...} for the given document path. Shared by
     * both servers; shows nothing if the endpoint returns an empty list.
     *
     * @param docPath the served path of the current page (the lookup key)
     * @return an HTML fragment to insert before {@code </body>}
     */
    static String semanticWidget(String docPath) {
        return """
            <style>
            #related-docs-sem{display:none;margin:1rem auto 2rem;max-width:860px;
              border-left:4px solid #2e8555;border-radius:0 6px 6px 0;
              background:#eefaf2;padding:1rem 1.25rem;font-family:sans-serif;}
            #related-docs-sem .related-header{display:flex;align-items:center;
              gap:.5rem;margin-bottom:.75rem;}
            #related-docs-sem .related-icon{font-size:1.1rem;}
            #related-docs-sem .related-title{font-size:0.8rem;font-weight:700;
              text-transform:uppercase;letter-spacing:.06em;color:#2e8555;}
            #related-docs-sem ul{list-style:none;padding:0;margin:0;display:flex;flex-wrap:wrap;gap:.5rem;}
            #related-docs-sem li a{display:block;padding:.3rem .75rem;border-radius:4px;
              border:1px solid #a7d9bf;background:#fff;color:#1d6b43;
              text-decoration:none;font-size:.85rem;}
            #related-docs-sem li a:hover{background:#d3f0e0;border-color:#2e8555;}
            </style>
            <div id="related-docs-sem">
              <div class="related-header">
                <span class="related-icon">🧠</span>
                <span class="related-title">Related Documents (semantic)</span>
              </div>
              <ul id="related-list-sem"></ul>
            </div>
            <script>
            (function(){
              var path = %s;
              fetch('/api/related-semantic?path=' + encodeURIComponent(path))
                .then(function(r){return r.json();})
                .then(function(docs){
                  var box = document.getElementById('related-docs-sem');
                  var ul = document.getElementById('related-list-sem');
                  if(!box || !ul || !docs.length) return;
                  docs.slice(0, 10).forEach(function(d){
                    var li = document.createElement('li');
                    var a = document.createElement('a');
                    a.href = d.path;
                    a.textContent = d.title || d.path;
                    a.title = d.summary || '';
                    li.appendChild(a);
                    ul.appendChild(li);
                  });
                  var footer = document.createElement('div');
                  footer.style.cssText = 'margin-top:.6rem;font-size:.8rem;';
                  var seeAll = document.createElement('a');
                  seeAll.href = '/related-semantic?path=' + encodeURIComponent(path);
                  seeAll.textContent = 'See all ' + docs.length + ' semantically related documents →';
                  seeAll.style.cssText = 'color:#1d6b43;text-decoration:none;';
                  footer.appendChild(seeAll);
                  box.appendChild(footer);
                  box.style.display = 'block';
                });
            })();
            </script>
            """.formatted(HttpUtils.jsonStr(docPath));
    }

    /** Inserts a widget fragment just before {@code </body>} (or appends if absent). */
    static String injectBeforeBodyEnd(String html, String fragment) {
        int idx = html.lastIndexOf("</body>");
        if (idx >= 0) {
            return html.substring(0, idx) + fragment + html.substring(idx);
        }
        return html + fragment;
    }

    /** Converts a page path to a breadcrumb like {@code intro › setup}. */
    private static String breadcrumb(String path) {
        if (path == null || path.isEmpty()) return "";
        return java.util.Arrays.stream(path.replaceFirst("^/", "").split("/"))
            .map(seg -> seg.replaceFirst("^\\d+_", "").replaceAll("\\.html$", ""))
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.joining(" › "));
    }
}
