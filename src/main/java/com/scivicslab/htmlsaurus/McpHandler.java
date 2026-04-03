package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
 *   <li>{@code rebuild-site} — regenerate HTML and search index</li>
 * </ul>
 */
class McpHandler {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "html-saurus";
    private static final String SERVER_VERSION = "1.4.0";

    private final Path docsDir;
    private final LuceneSearcher searcher;
    private final Runnable rebuild;
    private final Map<String, LuceneSearcher> localeSearchers;

    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionCounter = new AtomicLong();

    /**
     * @param docsDir         the docs/ directory containing raw Markdown source files
     * @param searcher        the default Lucene searcher for full-text search
     * @param rebuild         callback to trigger a full rebuild (build + reindex)
     * @param localeSearchers locale-specific searchers (may be empty)
     */
    McpHandler(Path docsDir, LuceneSearcher searcher, Runnable rebuild,
               Map<String, LuceneSearcher> localeSearchers) {
        this.docsDir = docsDir;
        this.searcher = searcher;
        this.rebuild = rebuild;
        this.localeSearchers = localeSearchers != null ? localeSearchers : Map.of();
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
                {"type":"object","properties":{},"required":[]}""")
        ) + "]}";
    }

    private String handleToolsCall(Map<String, Object> params) throws Exception {
        if (params == null) return errorJson(-32602, "Missing params");
        String toolName = McpJsonParser.getString(params, "name");
        Map<String, Object> args = McpJsonParser.getMap(params, "arguments");
        if (args == null) args = Map.of();

        return switch (toolName) {
            case "search-docs"    -> toolSearchDocs(args);
            case "list-documents" -> toolListDocuments(args);
            case "read-document"  -> toolReadDocument(args);
            case "edit-document"  -> toolEditDocument(args);
            case "rebuild-site"   -> toolRebuildSite();
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

        LuceneSearcher s = (locale != null && !locale.isEmpty() && localeSearchers.containsKey(locale))
            ? localeSearchers.get(locale) : searcher;

        var hits = s.search(query, maxResults,
            new String[]{"title_idx", "doc_id_idx", "body"},
            Map.of("title_idx", 3.0f, "doc_id_idx", 5.0f, "body", 1.0f));

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
