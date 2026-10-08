package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import static com.scivicslab.htmlsaurus.Endpoint.Visibility.PUBLIC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The server production mode runs: one built Docusaurus project, served to the internet.
 *
 * <p>It answers three paths and declares no others — the built pages under {@code /}, and
 * {@code /search} for the default locale and each other locale that has an index. There is no
 * flag that turns anything else on. The endpoints that read, write and rebuild the project live
 * in {@code PortalServer}, in another module, so this jar does not contain them.
 *
 * <p>Every path goes through {@link #register}, which records it in {@link #endpoints()} and
 * demands an {@link Endpoint.Visibility} with no default. {@code EndpointSurfaceTest} reads that
 * record, and also checks that {@code createContext} appears nowhere else in this file.
 */
public class SearchServer {

    private final Path staticDir;
    private final int port;
    private final ActorSystem searcherSystem = new ActorSystem("searcher-system");
    private final ActorRef<LuceneSearcher> searcher;
    private final Map<String, ActorRef<LuceneSearcher>> localeSearchers = new HashMap<>();
    /** Every path declared in start(), whether or not this deployment registered its handler. */
    private final List<Endpoint> endpoints = new ArrayList<>();
    /** Hits per page on the results page, matching the portal's own results page. */
    private static final int HITS_PER_PAGE = 20;
    /** How deep the results page goes; hits beyond this are not reachable by paging. */
    private static final int MAX_HITS = 200;

    /**
     * @param staticDir directory holding the generated static HTML
     * @param indexDir  directory holding the Lucene index, with one subdirectory per extra locale
     * @param port      HTTP port to listen on
     */
    public SearchServer(Path staticDir, Path indexDir, int port) {
        this.staticDir = staticDir;
        this.port = port;
        this.searcher = searcherSystem.actorOf("default", new LuceneSearcher(indexDir));
        // Load locale-specific indexes from search-index/<locale>/
        try {
            if (Files.isDirectory(indexDir)) {
                Files.list(indexDir)
                    .filter(Files::isDirectory)
                    .forEach(locDir -> {
                        String loc = locDir.getFileName().toString();
                        localeSearchers.put(loc, searcherSystem.actorOf(loc, new LuceneSearcher(locDir)));
                    });
            }
        } catch (IOException e) {
            System.err.println("Warning: could not scan locale indexes: " + e.getMessage());
        }
    }

    /**
     * Creates and starts the HTTP server.
     *
     * @return the started {@link HttpServer} instance (caller may call {@code stop(0)} when done)
     * @throws IOException if the server socket cannot be opened
     */
    public HttpServer start() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        register(server, "/search", PUBLIC, () -> this::handleSearch);
        // An English page's search box submits to /en/search, the results page that sits beside
        // the English pages. Without a handler there the request reached the static file, whose
        // results marker nothing had replaced, so the page listed nothing whatever was asked.
        for (String loc : localeSearchers.keySet()) {
            register(server, "/" + loc + "/search", PUBLIC, () -> this::handleSearch);
        }

        register(server, "/", PUBLIC, () -> this::handleStatic);

        server.setExecutor(null);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(searcherSystem::terminate));
        System.out.println("Serving at http://localhost:" + server.getAddress().getPort());
        System.out.println("Press Ctrl+C to stop.");
        return server;
    }

    /**
     * Declares one path this server knows about and, when this deployment may answer it, registers
     * its handler.
     *
     * <p>Every path goes through here, and every call states its {@link Endpoint.Visibility}, so
     * whether a public site answers a path is written at the line that adds the path instead of
     * following from which {@code if} block the line sits in. {@link #endpoints()} hands the
     * declarations to the test that checks the production surface, which therefore covers a new
     * path from the moment it is written rather than when someone remembers to copy it.
     *
     * <p>The handler arrives as a supplier so a public deployment does not construct the handlers
     * it will not register.
     */
    private void register(HttpServer server, String path, Endpoint.Visibility visibility,
                          java.util.function.Supplier<HttpHandler> handler) {
        endpoints.add(new Endpoint(path, visibility));
        if (visibility != PUBLIC) return;
        server.createContext(path, handler.get());
    }

    /** Every path this server declared, in the order it declared them. */
    List<Endpoint> endpoints() {
        return List.copyOf(endpoints);
    }

    // ---- Search endpoint ----------------------------------------

    /**
     * Handles {@code GET /search?q=...} requests. Extracts the query string from the
     * URL parameters and returns search results as a JSON array with CORS headers.
     *
     * <p>{@link HttpServer#createContext} matches by path prefix, not exact path, and {@code
     * /search} is the one context registered unconditionally (open in production — see
     * {@code ProductionModeSpec_260806_oo01}, doc_SCIVICS002, {@code 010_concepts}). Without this
     * guard, a request to a longer path sharing the same prefix (e.g. {@code /search-semantic},
     * whose own context is registered only in dev mode) would silently fall through to this
     * handler instead of 404ing, exposing search results under a path meant to be closed.
     */
    private void handleSearch(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        boolean trailingSlash = path.endsWith("/");
        String stem = trailingSlash ? path.substring(0, path.length() - 1) : path;
        String pathLocale = "";
        if (!"/search".equals(stem)) {
            String[] seg = stem.split("/");   // "", locale, "search"
            if (seg.length == 3 && "search".equals(seg[2]) && localeSearchers.containsKey(seg[1])) {
                pathLocale = seg[1];
            } else {
                respond(ex, 404, "text/plain", "Not Found");
                return;
            }
        }
        String accepts = ex.getRequestHeaders().getFirst("Accept");
        boolean wantsPage = accepts != null && accepts.contains("text/html");
        // The results page's links are relative to the search/ directory, so a browser has to be
        // standing in it. A program asking for JSON stays where it asked, redirect-free.
        if (wantsPage && !trailingSlash) {
            String query = ex.getRequestURI().getRawQuery();
            ex.getResponseHeaders().set("Location", stem + "/" + (query == null ? "" : "?" + query));
            ex.sendResponseHeaders(301, -1);
            ex.close();
            return;
        }
        String q = HttpUtils.queryParam(ex, "q");
        // The address states the locale twice over: in the path, for a reader who followed the
        // English pages, and in the locale parameter, for the script that asks for JSON.
        String locale = !pathLocale.isEmpty() ? pathLocale : HttpUtils.queryParam(ex, "locale");
        ActorRef<LuceneSearcher> sRef = (!locale.isEmpty() && localeSearchers.containsKey(locale))
            ? localeSearchers.get(locale) : searcher;

        // A browser states that it wants a page; a program asking for the same address states
        // nothing, or asks for JSON, and keeps getting JSON. The address is the one Docusaurus
        // uses for its results page, so a reader who lands on it sees results rather than data.
        if (wantsPage) {
            String page = searchResultsPage(q, locale, pageNumber(ex), sRef);
            if (page != null) {
                HttpUtils.respond(ex, 200, "text/html; charset=UTF-8", page);
                return;
            }
        }
        HttpUtils.respond(ex, 200, "application/json; charset=UTF-8", search(q, sRef));
    }

    /**
     * Renders the search results into the page {@code SiteBuilder} built for them, so the results
     * arrive wearing the site's navbar, sidebar and colours. Returns null when that page is absent,
     * which is every build made before this page existed; the caller then answers with JSON as it
     * always did.
     */
    private String searchResultsPage(String q, String locale, int pageNo, ActorRef<LuceneSearcher> sRef) {
        Path page = (locale != null && !locale.isBlank() && localeSearchers.containsKey(locale))
            ? staticDir.resolve(locale).resolve("search").resolve("index.html")
            : staticDir.resolve("search").resolve("index.html");
        if (!Files.isRegularFile(page)) return null;
        String html;
        try {
            html = Files.readString(page);
        } catch (IOException e) {
            return null;
        }
        return html
            .replace(SiteBuilder.SEARCH_RESULTS_MARKER, searchResultsHtml(q, locale, pageNo, sRef))
            .replace("id=\"search-input\" name=\"q\" type=\"search\" placeholder=\"Search...\" autocomplete=\"off\" value=\"\"",
                     "id=\"search-input\" name=\"q\" type=\"search\" placeholder=\"Search...\" autocomplete=\"off\" value=\""
                     + HttpUtils.escapeHtml(q) + "\"");
    }

    /** Reads the {@code page} parameter, counting from 1; anything unreadable is page 1. */
    private static int pageNumber(HttpExchange ex) {
        try {
            int n = Integer.parseInt(HttpUtils.queryParam(ex, "page"));
            return n < 1 ? 1 : n;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * The results themselves: a count, then one block per hit with its title, path and summary,
     * and, when the hits do not fit on one page, the paginator the blog listing pages use.
     */
    private String searchResultsHtml(String q, String locale, int pageNo, ActorRef<LuceneSearcher> sRef) {
        if (q == null || q.isBlank()) {
            return "<p class=\"search-hint\">Type a query in the box above.</p>";
        }
        List<LuceneSearcher.Hit> hits;
        try {
            hits = sRef.ask(s -> { try { return s.search(q, MAX_HITS,
                LuceneQueryBuilder.fields(),
                LuceneQueryBuilder.BOOSTS);
            } catch (Exception e) { throw new RuntimeException(e); } }).join();
        } catch (Exception e) {
            System.err.println("Search error: " + e.getMessage());
            hits = List.of();
        }
        int total = hits.size();
        int totalPages = Math.max(1, (total + HITS_PER_PAGE - 1) / HITS_PER_PAGE);
        int current = Math.min(pageNo, totalPages);
        int from = (current - 1) * HITS_PER_PAGE;
        int to = Math.min(from + HITS_PER_PAGE, total);

        var sb = new StringBuilder();
        sb.append("<p class=\"search-count\"><strong>").append(total)
          .append("</strong> result(s) for &quot;").append(HttpUtils.escapeHtml(q)).append("&quot;</p>\n");
        if (hits.isEmpty()) {
            sb.append("<p class=\"search-none\">No results.</p>\n");
            return sb.toString();
        }
        for (var hit : hits.subList(from, to)) {
            // The whole box is the link, as in the portal's result list: pointing at it highlights
            // the box. page.css keeps the site's link underline off it, so the path and the summary
            // do not draw as link text.
            sb.append("<a class=\"search-hit\" href=\"").append(HttpUtils.escapeHtml(hit.path())).append("\">\n");
            sb.append("  <div class=\"search-hit-title\">").append(HttpUtils.escapeHtml(hit.title())).append("</div>\n");
            sb.append("  <div class=\"search-hit-path\">").append(HttpUtils.escapeHtml(hit.path())).append("</div>\n");
            // The summary marks the matched words with <b>; everything else is escaped.
            sb.append("  <div class=\"search-hit-summary\">").append(escapeKeepingBold(hit.summary())).append("</div>\n");
            sb.append("</a>\n");
        }
        appendPaginator(sb, q, locale, current, totalPages);
        return sb.toString();
    }

    /**
     * Appends the paginator, in the markup and the classes the blog listing pages use, so both
     * kinds of listing page carry the same control. Nothing is appended for a single page.
     */
    private static void appendPaginator(StringBuilder sb, String q, String locale,
                                        int current, int totalPages) {
        if (totalPages < 2) return;
        sb.append("<nav class=\"search-paginator\" aria-label=\"Search result pages\">\n");
        if (current > 1) {
            sb.append("  <a href=\"").append(pageHref(q, locale, current - 1))
              .append("\" class=\"paginator-prev\">\u2190 Prev</a>\n");
        } else {
            sb.append("  <span class=\"paginator-prev paginator-disabled\">\u2190 Prev</span>\n");
        }
        sb.append("  <span class=\"paginator-pages\">");
        for (int k = 1; k <= totalPages; k++) {
            if (k == current) {
                sb.append("<span class=\"paginator-current\">").append(k).append("</span>");
            } else {
                sb.append("<a href=\"").append(pageHref(q, locale, k))
                  .append("\" class=\"paginator-page\">").append(k).append("</a>");
            }
        }
        sb.append("</span>\n");
        if (current < totalPages) {
            sb.append("  <a href=\"").append(pageHref(q, locale, current + 1))
              .append("\" class=\"paginator-next\">Next \u2192</a>\n");
        } else {
            sb.append("  <span class=\"paginator-next paginator-disabled\">Next \u2192</span>\n");
        }
        sb.append("</nav>\n");
    }

    /** The address of one page of results, carrying the query and the locale it was asked in. */
    private static String pageHref(String q, String locale, int pageNo) {
        var href = new StringBuilder("?q=").append(URLEncoder.encode(q, StandardCharsets.UTF_8));
        if (locale != null && !locale.isBlank()) {
            href.append("&amp;locale=").append(URLEncoder.encode(locale, StandardCharsets.UTF_8));
        }
        return href.append("&amp;page=").append(pageNo).toString();
    }

    /** Escapes the text, then restores the {@code <b>} pairs the highlighter put around matches. */
    private static String escapeKeepingBold(String s) {
        return HttpUtils.escapeHtml(s == null ? "" : s)
            .replace("&lt;b&gt;", "<b>")
            .replace("&lt;/b&gt;", "</b>");
    }

    /**
     * Executes a Lucene full-text search against the index directory.
     * Searches across title, document ID, and body fields with boosted weighting.
     * Returns up to 20 results as a JSON array of objects with title, path, and summary.
     *
     * <p>Never emits {@code srcPath} (the Markdown's path on the server's own disk)
     * mode: {@code /search} is the one endpoint left reachable from an internet-facing deployment
     * (see {@code ProductionModeSpec_260806_oo01}, doc_SCIVICS002, {@code 010_concepts}), and a
     * public reader has no use for a path on the server's local disk.
     *
     * @param queryStr the user's search query; blank returns an empty array
     * @return JSON array string of search results
     */
    private String search(String queryStr, ActorRef<LuceneSearcher> sRef) {
        try {
            var hits = sRef.ask(s -> { try { return s.search(queryStr, 20,
                LuceneQueryBuilder.fields(),
                LuceneQueryBuilder.BOOSTS);
            } catch (Exception e) { throw new RuntimeException(e); } }).join();
            var sb = new StringBuilder("[");
            boolean first = true;
            for (var hit : hits) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                  .append("\"title\":").append(HttpUtils.jsonStr(hit.title())).append(",")
                  .append("\"path\":").append(HttpUtils.jsonStr(hit.path())).append(",");
                sb.append("\"summary\":").append(HttpUtils.jsonStr(hit.summary()))
                  .append("}");
            }
            return sb.append("]").toString();
        } catch (Exception e) {
            System.err.println("Search error: " + e.getMessage());
            return "[]";
        }
    }

    // ---- Static file endpoint -----------------------------------

    /**
     * Serves static files from the output directory. Maps the request path to a local file,
     * validates that it does not escape the static directory (path traversal protection),
     * and returns the file content with an appropriate Content-Type header.
     */
    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        // Strip the whole run of leading slashes, not just one. Path.resolve returns its argument
        // verbatim when that argument is absolute, so a request path that still begins with "/"
        // after the strip makes the lookup start at the filesystem root instead of staticDir.
        Path file = staticDir.resolve(path.replaceFirst("^/+", "")).normalize();
        if (!file.startsWith(staticDir)) { respond(ex, 403, "text/plain", "Forbidden"); return; }
        if (Files.isDirectory(file)) {
            Path index = file.resolve("index.html").normalize();
            if (!index.startsWith(staticDir) || !Files.exists(index)) {
                respond(ex, 404, "text/html",
                    "<html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head><body><h1>404 Not Found</h1><p>" + HttpUtils.escapeHtml(path) + "</p></body></html>");
                return;
            }
            // Serve the page only under the "/"-terminated URL. Without the slash the browser
            // resolves the page's relative references one directory too high, so same-directory
            // images, PDFs and sibling links all come back 404.
            if (!path.endsWith("/")) {
                HttpUtils.redirect(ex, HttpUtils.withTrailingSlash(ex.getRequestURI()));
                return;
            }
            file = index;
        } else if (!Files.exists(file)) {
            respond(ex, 404, "text/html",
                "<html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head><body><h1>404 Not Found</h1><p>" + HttpUtils.escapeHtml(path) + "</p></body></html>");
            return;
        }

        // The page goes out as it was built. Production mode used to inject the semantic
        // related-docs widget here as well, and that widget asks /api/related-semantic, which
        // production mode does not answer: every page on the public site made one request that
        // always came back 404. The widget belongs to portal mode, which does answer it.
        HttpUtils.respond(ex, 200, HttpUtils.contentType(file.toString()), Files.readAllBytes(file));
    }

    private void respond(HttpExchange ex, int code, String ct, String body) throws IOException {
        HttpUtils.respond(ex, code, ct, body);
    }
}
