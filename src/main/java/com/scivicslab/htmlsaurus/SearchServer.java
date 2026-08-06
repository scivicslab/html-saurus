package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP development server for a single Docusaurus project.
 *
 * <p>Serves static HTML files, provides a full-text search API endpoint ({@code /search?q=...}),
 * and exposes a build trigger endpoint ({@code POST /api/build-all}) for on-demand rebuilds.
 */
public class SearchServer {

    private final Path staticDir;
    private final Path docsDir;
    private final int port;
    private final Runnable rebuild;
    private final boolean production;
    private final ActorSystem searcherSystem = new ActorSystem("searcher-system");
    private final ActorRef<LuceneSearcher> searcher;
    private final Map<String, ActorRef<LuceneSearcher>> localeSearchers = new HashMap<>();
    /** Semantic related-docs keyed by served path; empty when no vectors are available. */
    private final Map<String, List<Map<String, String>>> semanticRelated;
    /** In-memory semantic index (document vectors) for query-to-doc search; may be null. */
    private final SemanticIndex semanticIndex;
    /** Embedding client used to embed search queries at request time. */
    private final EmbeddingClient embed;
    /** Generation client used for on-demand paragraph translation. */
    private final TranslationClient translate;
    /** On-disk cache of past translations for this project. */
    private final TranslationCache translationCache;
    /** Maximum results returned by semantic query search. */
    private static final int SEARCH_TOP_N = 20;

    /**
     * @param staticDir     directory containing the generated static HTML files
     * @param indexDir      directory containing the Lucene search index
     * @param port          HTTP port to listen on
     * @param rebuild       callback to trigger a full rebuild (build + reindex)
     * @param production    {@code true} to disable the {@code /api/build-all} endpoint
     * @param docsDir       source directory containing raw Markdown files (for MCP tools)
     * @param semanticIndex in-memory semantic neighbour index (may be {@code null})
     */
    public SearchServer(Path staticDir, Path indexDir, int port, Runnable rebuild,
                        boolean production, Path docsDir, SemanticIndex semanticIndex) {
        this.staticDir = staticDir;
        this.docsDir = docsDir;
        this.port = port;
        this.rebuild = rebuild;
        this.production = production;
        // Single-project mode: served URLs have no project prefix, so the key is the bare path.
        this.semanticRelated = semanticIndex == null ? Map.of()
                : semanticIndex.servedMap((projectName, path) -> path);
        this.semanticIndex = semanticIndex;
        this.embed = new EmbeddingClient(System.getenv("EMBEDDING_SERVER_URL"));
        this.translate = new TranslationClient(System.getenv("TRANSLATION_SERVER_URL"));
        this.translationCache = TranslationCache.load(docsDir.getParent());
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
     * Creates and starts the HTTP server, registering handlers for build, search,
     * and static file endpoints.
     *
     * @return the started {@link HttpServer} instance (caller may call {@code stop(0)} when done)
     * @throws IOException if the server socket cannot be opened
     */
    public HttpServer start() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        if (!production) {
            server.createContext("/api/build-all", this::handleBuild);
        }
        server.createContext("/search", this::handleSearch);
        if (!production) {
            server.createContext("/api/related", this::handleRelated);
            server.createContext("/api/find-related", this::handleFindRelated);
            server.createContext("/api/related-semantic", this::handleRelatedSemantic);
            server.createContext("/related-semantic", this::handleRelatedSemanticPage);
            server.createContext("/api/search-semantic", this::handleSearchSemantic);
            server.createContext("/search-semantic", this::handleSearchSemanticPage);
            server.createContext("/api/translate", this::handleTranslate);
        }
        if (!production) {
            // MCP endpoint for LLM tool access (development mode only — exposes unauthenticated
            // file read/write/rebuild tools that must not be reachable from a production deployment)
            // Single-project mode has no id/path-fragment resolver (no /api/resolve equivalent),
            // so prerequisite-documents reports itself unavailable here rather than resolving.
            // find-related-documents also ignores locale here, matching handleFindRelated's own
            // behavior (a single project has one default searcher, not per-locale aggregation).
            java.util.function.BiFunction<String, String, List<Map<String, String>>> textRelatedResolver =
                (text, locale) -> tfidfRelatedText(text);
            java.util.function.Function<String, List<Map<String, String>>> semanticQueryResolver = this::semanticSearch;
            java.util.function.Function<String, List<Map<String, String>>> semanticRelatedResolver =
                p -> semanticRelated.getOrDefault(p, List.of());
            // Single-project mode only supports the "all" stage (matching /api/build-all); the
            // project argument is accepted but ignored, since there is only one implicit project.
            McpHandler.StageBuilder stageBuilder = (project, stage) -> {
                if (!"all".equals(stage)) return null;
                long start = System.currentTimeMillis();
                rebuild.run();
                return System.currentTimeMillis() - start;
            };
            var mcpHandler = new McpHandler(docsDir, searcher, rebuild, localeSearchers, null,
                textRelatedResolver, semanticQueryResolver, semanticRelatedResolver,
                null, null, stageBuilder, null, null, null, this::translateCore);
            server.createContext("/mcp", mcpHandler::handle);
        }
        server.createContext("/", this::handleStatic);
        server.setExecutor(null);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(searcherSystem::terminate));
        System.out.println("Serving at http://localhost:" + server.getAddress().getPort());
        System.out.println("Press Ctrl+C to stop.");
        return server;
    }

    // ---- Build endpoint -----------------------------------------

    /**
     * Handles {@code POST /api/build-all} requests. Triggers a full rebuild and returns
     * the elapsed time in a JSON response. Non-POST requests receive a 405 response.
     */
    private void handleBuild(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        long start = System.currentTimeMillis();
        rebuild.run();
        long ms = System.currentTimeMillis() - start;
        respond(ex, 200, "application/json", "{\"status\":\"ok\",\"ms\":" + ms + "}");
    }

    // ---- Translation endpoint -------------------------------------

    /**
     * Translates one block of text, checking {@link #translationCache} first and only calling
     * {@link #translate} on a cache miss. Returns {@code null} on failure. Shared by
     * {@link #handleTranslate} (REST) and the MCP {@code translate} tool.
     */
    private String translateCore(String text, String targetLang) {
        String key = TranslationCache.key(text, targetLang);
        String cached = translationCache.get(key);
        String result = cached != null ? cached : translate.translate(text, targetLang);
        if (result != null && cached == null) {
            translationCache.put(key, result);
        }
        return result;
    }

    /**
     * Handles {@code POST /api/translate?lang=English}. The request body is the raw source
     * text of one block (paragraph, heading, list item, or table row). Checks
     * {@link #translationCache} first and only calls {@link #translate} on a cache miss.
     */
    private void handleTranslate(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtils.respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String targetLang = HttpUtils.queryParam(ex, "lang");
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (text.isEmpty() || targetLang.isEmpty()) {
            HttpUtils.respond(ex, 400, "text/plain", "Missing text or lang");
            return;
        }
        String result = translateCore(text, targetLang);
        if (result == null) {
            HttpUtils.respond(ex, 502, "application/json", "{\"error\":\"translation failed\"}");
            return;
        }
        HttpUtils.respond(ex, 200, "application/json; charset=UTF-8",
            "{\"translation\":" + HttpUtils.jsonStr(result) + "}");
    }

    // ---- Search endpoint ----------------------------------------

    /**
     * Handles {@code GET /search?q=...} requests. Extracts the query string from the
     * URL parameters and returns search results as a JSON array with CORS headers.
     */
    private void handleSearch(HttpExchange ex) throws IOException {
        String q = HttpUtils.queryParam(ex, "q");
        String locale = HttpUtils.queryParam(ex, "locale");
        ActorRef<LuceneSearcher> sRef = (!locale.isEmpty() && localeSearchers.containsKey(locale))
            ? localeSearchers.get(locale) : searcher;
        HttpUtils.respond(ex, 200, "application/json; charset=UTF-8", search(q, sRef));
    }

    /**
     * Executes a Lucene full-text search against the index directory.
     * Searches across title, document ID, and body fields with boosted weighting.
     * Returns up to 20 results as a JSON array of objects with title, path, and summary.
     *
     * @param queryStr the user's search query; blank returns an empty array
     * @return JSON array string of search results
     */
    private String search(String queryStr, ActorRef<LuceneSearcher> sRef) {
        try {
            var hits = sRef.ask(s -> { try { return s.search(queryStr, 20,
                new String[]{"title_idx", "doc_id_idx", "path_tokens", "meta", "body"},
                Map.of("title_idx", 3.0f, "doc_id_idx", 5.0f, "path_tokens", 5.0f, "meta", 2.0f, "body", 1.0f));
            } catch (Exception e) { throw new RuntimeException(e); } }).join();
            var sb = new StringBuilder("[");
            boolean first = true;
            for (var hit : hits) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                  .append("\"title\":").append(HttpUtils.jsonStr(hit.title())).append(",")
                  .append("\"path\":").append(HttpUtils.jsonStr(hit.path())).append(",")
                  .append("\"srcPath\":").append(HttpUtils.jsonStr(hit.srcPath())).append(",")
                  .append("\"summary\":").append(HttpUtils.jsonStr(hit.summary()))
                  .append("}");
            }
            return sb.append("]").toString();
        } catch (Exception e) {
            System.err.println("Search error: " + e.getMessage());
            return "[]";
        }
    }

    // ---- TF-IDF related-docs endpoints (API only; no standalone page) --------

    /** GET /api/related?path=... — TF-IDF (MoreLikeThis) doc-to-doc neighbours as JSON. */
    private void handleRelated(HttpExchange ex) throws IOException {
        RelatedDocsView.writeJson(ex, tfidfRelated(HttpUtils.queryParam(ex, "path")));
    }

    /** POST /api/find-related — body is plain pasted text; TF-IDF (MoreLikeThis) matches as JSON. */
    private void handleFindRelated(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtils.respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).strip();
        RelatedDocsView.writeJson(ex, tfidfRelatedText(text));
    }

    /** Documents similar to the one at {@code docPath}, via {@link LuceneSearcher#moreLikeThis}. */
    private List<Map<String, String>> tfidfRelated(String docPath) {
        if (docPath == null || docPath.isBlank()) return List.of();
        try {
            return toMaps(searcher.ask(s -> {
                try { return s.moreLikeThis(docPath, 20); }
                catch (Exception e) { throw new RuntimeException(e); }
            }).join());
        } catch (Exception e) {
            System.err.println("related error: " + e.getMessage());
            return List.of();
        }
    }

    /** Documents similar to pasted {@code text}, via {@link LuceneSearcher#moreLikeThisText}. */
    private List<Map<String, String>> tfidfRelatedText(String text) {
        if (text == null || text.isBlank()) return List.of();
        try {
            return toMaps(searcher.ask(s -> {
                try { return s.moreLikeThisText(text, 20); }
                catch (Exception e) { throw new RuntimeException(e); }
            }).join());
        } catch (Exception e) {
            System.err.println("find-related error: " + e.getMessage());
            return List.of();
        }
    }

    /** Converts {@link LuceneSearcher.Hit} records to the {title,path,srcPath,summary} map shape. */
    private static List<Map<String, String>> toMaps(List<LuceneSearcher.Hit> hits) {
        List<Map<String, String>> out = new ArrayList<>(hits.size());
        for (var hit : hits) {
            out.add(Map.of("title", hit.title(), "path", hit.path(),
                    "srcPath", hit.srcPath(), "summary", hit.summary()));
        }
        return out;
    }

    // ---- Semantic related-docs endpoints ------------------------

    /** GET /api/related-semantic?path=... — precomputed embedding-based neighbours as JSON. */
    private void handleRelatedSemantic(HttpExchange ex) throws IOException {
        RelatedDocsView.writeJson(ex, semanticRelated.getOrDefault(HttpUtils.queryParam(ex, "path"), List.of()));
    }

    /** GET /related-semantic?path=... — standalone HTML page of embedding-based related docs. */
    private void handleRelatedSemanticPage(HttpExchange ex) throws IOException {
        String docPath = HttpUtils.queryParam(ex, "path");
        HttpUtils.respond(ex, 200, "text/html; charset=UTF-8",
                RelatedDocsView.pageHtml("Related (semantic)", docPath,
                        semanticRelated.getOrDefault(docPath, List.of())));
    }

    /**
     * GET /api/search-semantic?q=... or POST /api/search-semantic (body = pasted text) —
     * query-to-doc semantic search as JSON. POST avoids the URL-length limit GET has when
     * the query is a pasted paragraph rather than a few keywords.
     */
    private void handleSearchSemantic(HttpExchange ex) throws IOException {
        String q = "POST".equalsIgnoreCase(ex.getRequestMethod())
                ? new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).strip()
                : HttpUtils.queryParam(ex, "q");
        RelatedDocsView.writeJson(ex, semanticSearch(q));
    }

    /** GET /search-semantic?q=... — semantic search results page (with a query box). */
    private void handleSearchSemanticPage(HttpExchange ex) throws IOException {
        String q = HttpUtils.queryParam(ex, "q");
        HttpUtils.respond(ex, 200, "text/html; charset=UTF-8",
                RelatedDocsView.searchResultsPage(q, semanticSearch(q), false));
    }

    /** Embeds the query and ranks all documents by cosine; empty if no index/query or the embed server is down. */
    private List<Map<String, String>> semanticSearch(String q) {
        if (semanticIndex == null || q == null || q.isBlank()) {
            return List.of();
        }
        float[] qv = embed.embed(q);
        if (qv == null) {
            return List.of();
        }
        return semanticIndex.search(qv, SEARCH_TOP_N, (projectName, path) -> path);
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

        Path file = staticDir.resolve(path.replaceFirst("^/", "")).normalize();
        if (!file.startsWith(staticDir)) { respond(ex, 403, "text/plain", "Forbidden"); return; }
        if (Files.isDirectory(file)) {
            file = file.resolve("index.html").normalize();
            if (!file.startsWith(staticDir) || !Files.exists(file)) {
                respond(ex, 404, "text/html",
                    "<html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head><body><h1>404 Not Found</h1><p>" + HttpUtils.escapeHtml(path) + "</p></body></html>");
                return;
            }
        } else if (!Files.exists(file)) {
            respond(ex, 404, "text/html",
                "<html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head><body><h1>404 Not Found</h1><p>" + HttpUtils.escapeHtml(path) + "</p></body></html>");
            return;
        }

        byte[] body = Files.readAllBytes(file);
        String ct = HttpUtils.contentType(file.toString());
        if (ct.startsWith("text/html")) {
            // Inject the semantic related-docs widget; the key is the bare served path.
            String html = new String(body, StandardCharsets.UTF_8);
            html = RelatedDocsView.injectBeforeBodyEnd(html, RelatedDocsView.semanticWidget(path));
            body = html.getBytes(StandardCharsets.UTF_8);
        }
        HttpUtils.respond(ex, 200, ct, body);
    }

    private void respond(HttpExchange ex, int code, String ct, String body) throws IOException {
        HttpUtils.respond(ex, code, ct, body);
    }
}
