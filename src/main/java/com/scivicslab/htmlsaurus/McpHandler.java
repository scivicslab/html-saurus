package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * MCP (Model Context Protocol) handler for html-saurus.
 *
 * <p>Exposes document operations as MCP tools over JSON-RPC 2.0 at the {@code /mcp} endpoint.
 * This allows LLMs connected through an MCP Gateway to search, read, edit, and rebuild
 * documentation without human intervention.
 *
 * <h3>Supported tools:</h3>
 * <ul>
 *   <li>{@code search-docs} — full-text search using Lucene</li>
 *   <li>{@code list-documents} — list all documents in the navigation tree</li>
 *   <li>{@code read-document} — read raw Markdown source of a document</li>
 *   <li>{@code edit-document} — write or update a Markdown document</li>
 *   <li>{@code related-documents} — find similar documents using TF-IDF (MoreLikeThis)</li>
 *   <li>{@code find-related-documents} — TF-IDF (MoreLikeThis) similar documents, from pasted text
 *       rather than an existing document's path</li>
 *   <li>{@code search-docs-semantic} — embedding-based semantic search by query</li>
 *   <li>{@code related-documents-semantic} — precomputed embedding-based neighbours of a document</li>
 *   <li>{@code prerequisite-documents} — find the documents this one requires, from its
 *       {@code ### 前提文書} section (a directed, author-declared relation, not similarity)</li>
 *   <li>{@code upload-pdf} — import a PDF into the docs directory</li>
 *   <li>{@code rebuild-site} — regenerate HTML and search index</li>
 * </ul>
 */
class McpHandler {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "html-saurus";
    private static final String SERVER_VERSION = "1.4.0";

    private final Path docsDir;
    private final ActorRef<LuceneSearcher> searcher;
    private final Runnable rebuild;
    private final Map<String, ActorRef<LuceneSearcher>> localeSearchers;
    private final KeywordMap.Resolver docRefResolver;
    private final BiFunction<String, String, List<Map<String, String>>> textRelatedResolver;
    private final Function<String, List<Map<String, String>>> semanticQueryResolver;
    private final Function<String, List<Map<String, String>>> semanticRelatedResolver;

    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionCounter = new AtomicLong();

    /**
     * @param docsDir                 the docs/ directory containing raw Markdown source files
     * @param searcher                the default Lucene searcher actor for full-text search
     * @param rebuild                 callback to trigger a full rebuild (build + reindex)
     * @param localeSearchers         locale-specific searcher actors (may be empty)
     * @param docRefResolver          resolves a document id or path fragment to a hit map
     *                                ({@code id,title,path,srcPath,summary}); same resolver the
     *                                portal's {@code /api/resolve}/{@code /api/siblings}/
     *                                {@code /api/prerequisites} use
     * @param textRelatedResolver     TF-IDF (MoreLikeThis) hits for pasted text, given
     *                                {@code (text, locale)}; same aggregation {@code /api/find-related}
     *                                uses. {@code locale} may be blank/unknown, meaning "all locales".
     * @param semanticQueryResolver   embedding-based hits for a query string; same as
     *                                {@code /api/search-semantic}
     * @param semanticRelatedResolver precomputed embedding-based neighbours for a served document
     *                                path; same as {@code /api/related-semantic}
     */
    McpHandler(Path docsDir, ActorRef<LuceneSearcher> searcher, Runnable rebuild,
               Map<String, ActorRef<LuceneSearcher>> localeSearchers, KeywordMap.Resolver docRefResolver,
               BiFunction<String, String, List<Map<String, String>>> textRelatedResolver,
               Function<String, List<Map<String, String>>> semanticQueryResolver,
               Function<String, List<Map<String, String>>> semanticRelatedResolver) {
        this.docsDir = docsDir;
        this.searcher = searcher;
        this.rebuild = rebuild;
        this.localeSearchers = localeSearchers != null ? localeSearchers : Map.of();
        this.docRefResolver = docRefResolver;
        this.textRelatedResolver = textRelatedResolver;
        this.semanticQueryResolver = semanticQueryResolver;
        this.semanticRelatedResolver = semanticRelatedResolver;
    }

    /**
     * Handles an HTTP exchange on the {@code /mcp} endpoint.
     * Only POST requests are accepted; all others receive 405.
     */
    void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtils.respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> request;
        try {
            request = McpJsonParser.parseObject(body);
        } catch (Exception e) {
            respondJsonRpc(ex, null, errorJson(-32700, "Parse error: " + e.getMessage()), null);
            return;
        }

        String method = McpJsonParser.getString(request, "method");
        Number id = McpJsonParser.getNumber(request, "id");
        Map<String, Object> params = McpJsonParser.getMap(request, "params");

        // Read session from header
        String sessionId = ex.getRequestHeaders().getFirst("Mcp-Session-Id");

        String result;
        String newSessionId = null;

        try {
            result = switch (method) {
                case "initialize" -> {
                    newSessionId = "htmlsaurus-" + sessionCounter.incrementAndGet();
                    sessions.put(newSessionId, "active");
                    yield handleInitialize();
                }
                case "notifications/initialized" -> {
                    respondNoContent(ex);
                    yield null; // already responded
                }
                case "tools/list" -> handleToolsList();
                case "tools/call" -> handleToolsCall(params);
                default -> errorJson(-32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            result = errorJson(-32603, "Internal error: " + e.getMessage());
        }

        if (result != null) {
            boolean isError = result.startsWith("{\"code\":");
            if (isError) {
                respondJsonRpc(ex, id, null, result);
            } else {
                respondJsonRpc(ex, id, result, null);
            }

            // Set session header for initialize response
            if (newSessionId != null) {
                // Already sent via respondJsonRpc with header - need to handle differently
            }
        }
    }

    // ---- MCP method handlers ----

    private String handleInitialize() {
        return """
            {"protocolVersion":"%s","capabilities":{"tools":{}},"serverInfo":{"name":"%s","version":"%s"}}"""
            .formatted(PROTOCOL_VERSION, SERVER_NAME, SERVER_VERSION);
    }

    private String handleToolsList() {
        return "{\"tools\":[" + String.join(",",
            toolDef("search-docs",
                "Search documents using full-text search. Returns titles, paths, and summaries.",
                """
                {"type":"object","properties":{"query":{"type":"string","description":"Search query"},"locale":{"type":"string","description":"Locale code (e.g. ja, en). Optional."},"max_results":{"type":"integer","description":"Maximum results to return (default 20)"}},"required":["query"]}"""),
            toolDef("list-documents",
                "List all Markdown documents in the docs directory with their relative paths.",
                """
                {"type":"object","properties":{"directory":{"type":"string","description":"Subdirectory to list (relative to docs root). Optional, defaults to root."}},"required":[]}"""),
            toolDef("read-document",
                "Read the raw Markdown source of a document by its relative path.",
                """
                {"type":"object","properties":{"path":{"type":"string","description":"Relative path to the .md file (e.g. 'ai-toolkit/010_concepts/010_AiToolkitConcept_260403_oo01/010_AiToolkitConcept_260403_oo01.md')"}},"required":["path"]}"""),
            toolDef("edit-document",
                "Create or update a Markdown document. Writes the given content to the specified path.",
                """
                {"type":"object","properties":{"path":{"type":"string","description":"Relative path to the .md file"},"content":{"type":"string","description":"The full Markdown content to write"}},"required":["path","content"]}"""),
            toolDef("rebuild-site",
                "Regenerate static HTML and search index from the current Markdown sources.",
                """
                {"type":"object","properties":{},"required":[]}"""),
            toolDef("related-documents",
                "Find documents similar to a given document using TF-IDF (MoreLikeThis). Useful for discovering related topics.",
                """
                {"type":"object","properties":{"path":{"type":"string","description":"Relative path to the .md file to find related documents for"},"max_results":{"type":"integer","description":"Maximum results to return (default 5)"}},"required":["path"]}"""),
            toolDef("upload-pdf",
                "Import a PDF file into the docs directory. Copies the PDF, extracts text, writes a companion .md file with YAML frontmatter (title, authors, year, journal), and triggers a rebuild.",
                """
                {"type":"object","properties":{"source_path":{"type":"string","description":"Absolute path to the PDF file on the filesystem"},"dest_path":{"type":"string","description":"Relative path within docs/ where the PDF should be saved (e.g. 'papers/attention.pdf')"}},"required":["source_path","dest_path"]}"""),
            toolDef("prerequisite-documents",
                "Find the documents that must be understood before this one, from its \"### 前提文書\" section. This is a directed, author-declared \"read this first\" relation, not a similarity score: it can point to a document that shares no vocabulary with this one, and it does not imply the reverse relation.",
                """
                {"type":"object","properties":{"id":{"type":"string","description":"Document id, or a fragment of its path, identifying the document whose prerequisites to look up"}},"required":["id"]}"""),
            toolDef("find-related-documents",
                "Find documents similar to a given piece of text using TF-IDF (MoreLikeThis). Useful for discovering related topics before a document has been saved anywhere (e.g. a draft or fragment).",
                """
                {"type":"object","properties":{"text":{"type":"string","description":"The text to find related documents for"},"locale":{"type":"string","description":"Locale code (e.g. ja, en). Optional."},"max_results":{"type":"integer","description":"Maximum results to return (default 5)"}},"required":["text"]}"""),
            toolDef("search-docs-semantic",
                "Search documents using embedding-based semantic similarity. Finds conceptually related documents even when the query does not share the same keywords as the document text.",
                """
                {"type":"object","properties":{"query":{"type":"string","description":"Search query"},"max_results":{"type":"integer","description":"Maximum results to return (default 20)"}},"required":["query"]}"""),
            toolDef("related-documents-semantic",
                "Find documents that are semantically similar to a given document, using precomputed embedding vectors. Complements related-documents (TF-IDF) with a different, meaning-based notion of similarity.",
                """
                {"type":"object","properties":{"path":{"type":"string","description":"Relative path to the .md file to find related documents for"},"max_results":{"type":"integer","description":"Maximum results to return (default 5)"}},"required":["path"]}""")
        ) + "]}";
    }

    private String handleToolsCall(Map<String, Object> params) throws Exception {
        if (params == null) return errorJson(-32602, "Missing params");
        String toolName = McpJsonParser.getString(params, "name");
        Map<String, Object> args = McpJsonParser.getMap(params, "arguments");
        if (args == null) args = Map.of();

        return switch (toolName) {
            case "search-docs"        -> toolSearchDocs(args);
            case "list-documents"     -> toolListDocuments(args);
            case "read-document"      -> toolReadDocument(args);
            case "edit-document"      -> toolEditDocument(args);
            case "rebuild-site"       -> toolRebuildSite();
            case "related-documents"  -> toolRelatedDocuments(args);
            case "upload-pdf"     -> toolUploadPdf(args);
            case "prerequisite-documents" -> toolPrerequisiteDocuments(args);
            case "find-related-documents" -> toolFindRelatedDocuments(args);
            case "search-docs-semantic" -> toolSearchDocsSemantic(args);
            case "related-documents-semantic" -> toolRelatedDocumentsSemantic(args);
            case null -> errorJson(-32602, "Missing tool name");
            default -> errorJson(-32602, "Unknown tool: " + toolName);
        };
    }

    // ---- Tool implementations ----

    private String toolSearchDocs(Map<String, Object> args) throws Exception {
        String query = McpJsonParser.getString(args, "query");
        if (query == null || query.isBlank()) {
            return toolError("Query is required");
        }
        String locale = McpJsonParser.getString(args, "locale");
        Number maxNum = McpJsonParser.getNumber(args, "max_results");
        int maxResults = maxNum != null ? maxNum.intValue() : 20;

        String[] fields = {"title_idx", "doc_id_idx", "path_tokens", "meta", "body"};
        Map<String, Float> boosts = Map.of("title_idx", 3.0f, "doc_id_idx", 5.0f,
                                           "path_tokens", 5.0f, "meta", 2.0f, "body", 1.0f);

        List<LuceneSearcher.Hit> hits;
        if (locale != null && !locale.isEmpty() && localeSearchers.containsKey(locale)) {
            // Locale explicitly specified: use that searcher only
            ActorRef<LuceneSearcher> locRef = localeSearchers.get(locale);
            hits = locRef.ask(s -> { try { return s.search(query, maxResults, fields, boosts); } catch (Exception e) { throw new RuntimeException(e); } }).join();
        } else {
            // No locale: aggregate across default + all project searchers
            List<ActorRef<LuceneSearcher>> all = new java.util.ArrayList<>();
            if (searcher != null) all.add(searcher);
            all.addAll(localeSearchers.values());

            var seen = new java.util.LinkedHashSet<String>();
            hits = new java.util.ArrayList<>();
            for (ActorRef<LuceneSearcher> sRef : all) {
                for (LuceneSearcher.Hit h : sRef.ask(s -> { try { return s.search(query, maxResults, fields, boosts); } catch (Exception e) { throw new RuntimeException(e); } }).join()) {
                    if (seen.add(h.path())) {
                        hits.add(h);
                        if (hits.size() >= maxResults) break;
                    }
                }
                if (hits.size() >= maxResults) break;
            }
        }

        var sb = new StringBuilder();
        if (hits.isEmpty()) {
            sb.append("No results found for: ").append(query);
        } else {
            sb.append("Found ").append(hits.size()).append(" result(s):\n\n");
            for (var hit : hits) {
                sb.append("- **").append(hit.title()).append("**\n");
                sb.append("  Path: ").append(hit.path()).append("\n");
                if (!hit.summary().isEmpty()) {
                    sb.append("  Summary: ").append(hit.summary()).append("\n");
                }
                sb.append("\n");
            }
        }
        return toolResult(sb.toString());
    }

    private String toolListDocuments(Map<String, Object> args) throws IOException {
        String subdir = McpJsonParser.getString(args, "directory");
        Path target = docsDir;
        if (subdir != null && !subdir.isEmpty()) {
            target = docsDir.resolve(subdir).normalize();
            if (!target.startsWith(docsDir)) {
                return toolError("Path traversal not allowed");
            }
        }
        if (!Files.isDirectory(target)) {
            return toolError("Directory not found: " + (subdir != null ? subdir : "docs/"));
        }

        var sb = new StringBuilder();
        sb.append("Documents in: ").append(subdir != null && !subdir.isEmpty() ? subdir : "(root)").append("\n\n");

        try (Stream<Path> walk = Files.walk(target)) {
            walk.filter(p -> p.toString().endsWith(".md"))
                .sorted()
                .forEach(p -> {
                    Path rel = docsDir.relativize(p);
                    try {
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        String title = extractTitle(content);
                        sb.append("- ").append(rel);
                        if (title != null) sb.append("  (").append(title).append(")");
                        sb.append("\n");
                    } catch (IOException e) {
                        sb.append("- ").append(rel).append("  [read error]\n");
                    }
                });
        }

        return toolResult(sb.toString());
    }

    private String toolReadDocument(Map<String, Object> args) throws IOException {
        String pathStr = McpJsonParser.getString(args, "path");
        if (pathStr == null || pathStr.isEmpty()) {
            return toolError("Path is required");
        }

        Path file = docsDir.resolve(pathStr).normalize();
        if (!file.startsWith(docsDir)) {
            return toolError("Path traversal not allowed");
        }
        if (!Files.exists(file)) {
            return toolError("File not found: " + pathStr);
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        return toolResult(content);
    }

    private String toolEditDocument(Map<String, Object> args) throws IOException {
        String pathStr = McpJsonParser.getString(args, "path");
        String content = McpJsonParser.getString(args, "content");
        if (pathStr == null || pathStr.isEmpty()) {
            return toolError("Path is required");
        }
        if (content == null) {
            return toolError("Content is required");
        }

        Path file = docsDir.resolve(pathStr).normalize();
        if (!file.startsWith(docsDir)) {
            return toolError("Path traversal not allowed");
        }

        // Create parent directories if needed
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);

        return toolResult("Document saved: " + pathStr + " (" + content.length() + " characters)");
    }

    private String toolRebuildSite() {
        long start = System.currentTimeMillis();
        rebuild.run();
        long ms = System.currentTimeMillis() - start;
        return toolResult("Site rebuilt successfully in " + ms + " ms.");
    }

    private String toolRelatedDocuments(Map<String, Object> args) throws Exception {
        String pathStr = McpJsonParser.getString(args, "path");
        if (pathStr == null || pathStr.isBlank()) {
            return toolError("Path is required");
        }
        Number maxNum = McpJsonParser.getNumber(args, "max_results");
        int maxResults = maxNum != null ? maxNum.intValue() : 5;

        // Try default searcher first, then all project/locale searchers until the doc is found
        List<ActorRef<LuceneSearcher>> candidates = new java.util.ArrayList<>();
        if (searcher != null) candidates.add(searcher);
        candidates.addAll(localeSearchers.values());

        List<LuceneSearcher.Hit> hits = List.of();
        for (ActorRef<LuceneSearcher> sRef : candidates) {
            hits = sRef.ask(s -> { try { return s.moreLikeThis(pathStr, maxResults); } catch (Exception e) { throw new RuntimeException(e); } }).join();
            if (!hits.isEmpty()) break;
        }

        var sb = new StringBuilder();
        if (hits.isEmpty()) {
            sb.append("No related documents found for: ").append(pathStr);
        } else {
            sb.append("Related documents (").append(hits.size()).append("):\n\n");
            for (var hit : hits) {
                sb.append("- **").append(hit.title()).append("**\n");
                sb.append("  Path: ").append(hit.path()).append("\n");
                if (!hit.summary().isEmpty()) {
                    sb.append("  Summary: ").append(hit.summary()).append("\n");
                }
                sb.append("\n");
            }
        }
        return toolResult(sb.toString());
    }

    private String toolPrerequisiteDocuments(Map<String, Object> args) throws IOException {
        String ref = McpJsonParser.getString(args, "id");
        if (ref == null || ref.isBlank()) {
            return toolError("Id is required");
        }
        if (docRefResolver == null) {
            return toolError("prerequisite-documents is not available in single-project mode");
        }
        Map<String, String> self = docRefResolver.resolve(ref);
        if (self == null) {
            return toolError("Document not found: " + ref);
        }

        List<Map<String, String>> prereqs = new java.util.ArrayList<>();
        String srcPath = self.getOrDefault("srcPath", "");
        if (!srcPath.isBlank()) {
            String content = Files.readString(Path.of(srcPath), StandardCharsets.UTF_8);
            for (String docRef : PrerequisiteSection.extractRefs(content)) {
                Map<String, String> hit = docRefResolver.resolve(docRef);
                if (hit != null) prereqs.add(hit);
            }
        }

        var sb = new StringBuilder();
        if (prereqs.isEmpty()) {
            sb.append("No prerequisite documents found for: ").append(ref);
        } else {
            sb.append("Prerequisite documents (").append(prereqs.size()).append("):\n\n");
            for (var hit : prereqs) {
                sb.append("- **").append(hit.getOrDefault("title", "")).append("**\n");
                sb.append("  Path: ").append(hit.getOrDefault("path", "")).append("\n");
                String summary = hit.getOrDefault("summary", "");
                if (!summary.isEmpty()) {
                    sb.append("  Summary: ").append(summary).append("\n");
                }
                sb.append("\n");
            }
        }
        return toolResult(sb.toString());
    }

    private String toolFindRelatedDocuments(Map<String, Object> args) {
        String text = McpJsonParser.getString(args, "text");
        if (text == null || text.isBlank()) {
            return toolError("Text is required");
        }
        String locale = McpJsonParser.getString(args, "locale");
        Number maxNum = McpJsonParser.getNumber(args, "max_results");
        int maxResults = maxNum != null ? maxNum.intValue() : 5;

        List<Map<String, String>> hits = textRelatedResolver != null
            ? textRelatedResolver.apply(text, locale) : List.of();
        if (hits.size() > maxResults) hits = hits.subList(0, maxResults);

        var sb = new StringBuilder();
        if (hits.isEmpty()) {
            sb.append("No related documents found for: ").append(text);
        } else {
            sb.append("Related documents (").append(hits.size()).append("):\n\n");
            for (var hit : hits) {
                sb.append("- **").append(hit.getOrDefault("title", "")).append("**\n");
                sb.append("  Path: ").append(hit.getOrDefault("path", "")).append("\n");
                String summary = hit.getOrDefault("summary", "");
                if (!summary.isEmpty()) {
                    sb.append("  Summary: ").append(summary).append("\n");
                }
                sb.append("\n");
            }
        }
        return toolResult(sb.toString());
    }

    private String toolSearchDocsSemantic(Map<String, Object> args) {
        String query = McpJsonParser.getString(args, "query");
        if (query == null || query.isBlank()) {
            return toolError("Query is required");
        }
        Number maxNum = McpJsonParser.getNumber(args, "max_results");
        int maxResults = maxNum != null ? maxNum.intValue() : 20;

        List<Map<String, String>> hits = semanticQueryResolver != null
            ? semanticQueryResolver.apply(query) : List.of();
        if (hits.size() > maxResults) hits = hits.subList(0, maxResults);

        var sb = new StringBuilder();
        if (hits.isEmpty()) {
            sb.append("No results found for: ").append(query);
        } else {
            sb.append("Found ").append(hits.size()).append(" result(s):\n\n");
            for (var hit : hits) {
                sb.append("- **").append(hit.getOrDefault("title", "")).append("**\n");
                sb.append("  Path: ").append(hit.getOrDefault("path", "")).append("\n");
                String summary = hit.getOrDefault("summary", "");
                if (!summary.isEmpty()) {
                    sb.append("  Summary: ").append(summary).append("\n");
                }
                sb.append("\n");
            }
        }
        return toolResult(sb.toString());
    }

    private String toolRelatedDocumentsSemantic(Map<String, Object> args) {
        String pathStr = McpJsonParser.getString(args, "path");
        if (pathStr == null || pathStr.isBlank()) {
            return toolError("Path is required");
        }
        Number maxNum = McpJsonParser.getNumber(args, "max_results");
        int maxResults = maxNum != null ? maxNum.intValue() : 5;

        List<Map<String, String>> hits = semanticRelatedResolver != null
            ? semanticRelatedResolver.apply(pathStr) : List.of();
        if (hits.size() > maxResults) hits = hits.subList(0, maxResults);

        var sb = new StringBuilder();
        if (hits.isEmpty()) {
            sb.append("No related documents found for: ").append(pathStr);
        } else {
            sb.append("Related documents (").append(hits.size()).append("):\n\n");
            for (var hit : hits) {
                sb.append("- **").append(hit.getOrDefault("title", "")).append("**\n");
                sb.append("  Path: ").append(hit.getOrDefault("path", "")).append("\n");
                String summary = hit.getOrDefault("summary", "");
                if (!summary.isEmpty()) {
                    sb.append("  Summary: ").append(summary).append("\n");
                }
                sb.append("\n");
            }
        }
        return toolResult(sb.toString());
    }

    private String toolUploadPdf(Map<String, Object> args) throws IOException {
        String sourcePath = McpJsonParser.getString(args, "source_path");
        String destPath   = McpJsonParser.getString(args, "dest_path");
        if (sourcePath == null || sourcePath.isBlank()) {
            return toolError("source_path is required");
        }
        if (destPath == null || destPath.isBlank()) {
            return toolError("dest_path is required");
        }
        if (!destPath.toLowerCase().endsWith(".pdf")) {
            return toolError("dest_path must end with .pdf");
        }

        Path src = Path.of(sourcePath).normalize();
        if (!Files.exists(src)) {
            return toolError("Source file not found: " + sourcePath);
        }

        Path dest = docsDir.resolve(destPath).normalize();
        if (!dest.startsWith(docsDir)) {
            return toolError("Path traversal not allowed");
        }

        Files.createDirectories(dest.getParent());
        Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        String filename = dest.getFileName().toString();
        String stem = filename.substring(0, filename.length() - 4);
        Path mdPath = dest.getParent().resolve(stem + ".md");
        try {
            String md = PdfExtractor.extract(dest);
            Files.writeString(mdPath, md, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("PDF extraction failed for " + filename + ": " + e.getMessage());
            Files.writeString(mdPath,
                "---\ntitle: \"" + stem + "\"\nsource_pdf: \"" + filename + "\"\n---\n\n(Text extraction failed)",
                StandardCharsets.UTF_8);
        }

        long start = System.currentTimeMillis();
        rebuild.run();
        long ms = System.currentTimeMillis() - start;

        return toolResult("PDF imported: " + destPath + " → " + stem + ".md (rebuilt in " + ms + " ms)");
    }

    // ---- Helpers ----

    /** Extracts the title from YAML frontmatter or the first heading. */
    private String extractTitle(String markdown) {
        if (markdown.startsWith("---")) {
            int end = markdown.indexOf("---", 3);
            if (end > 0) {
                String fm = markdown.substring(3, end);
                for (String line : fm.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("title:")) {
                        String val = line.substring(6).trim();
                        if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
                        if (val.startsWith("'") && val.endsWith("'")) val = val.substring(1, val.length() - 1);
                        return val;
                    }
                }
            }
        }
        // Fallback: first # heading
        for (String line : markdown.split("\n")) {
            if (line.startsWith("# ")) return line.substring(2).trim();
        }
        return null;
    }

    private static String toolDef(String name, String description, String inputSchema) {
        return "{\"name\":" + HttpUtils.jsonStr(name)
             + ",\"description\":" + HttpUtils.jsonStr(description)
             + ",\"inputSchema\":" + inputSchema + "}";
    }

    private static String toolResult(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + HttpUtils.jsonStr(text) + "}],\"isError\":false}";
    }

    private static String toolError(String message) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + HttpUtils.jsonStr(message) + "}],\"isError\":true}";
    }

    private static String errorJson(int code, String message) {
        return "{\"code\":" + code + ",\"message\":" + HttpUtils.jsonStr(message) + "}";
    }

    private void respondJsonRpc(HttpExchange ex, Number id, String result, String error) throws IOException {
        var sb = new StringBuilder("{\"jsonrpc\":\"2.0\"");
        if (id != null) {
            sb.append(",\"id\":").append(id.longValue());
        } else {
            sb.append(",\"id\":null");
        }
        if (error != null) {
            sb.append(",\"error\":").append(error);
        } else {
            sb.append(",\"result\":").append(result);
        }
        sb.append("}");

        // Set session ID header if we have one in the sessions map
        String lastSession = sessions.keySet().stream().reduce((a, b) -> b).orElse(null);
        if (lastSession != null) {
            ex.getResponseHeaders().set("Mcp-Session-Id", lastSession);
        }

        HttpUtils.respond(ex, 200, "application/json", sb.toString());
    }

    private void respondNoContent(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }
}
