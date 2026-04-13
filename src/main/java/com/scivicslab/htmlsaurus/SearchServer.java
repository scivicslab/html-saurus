package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP development server for a single Docusaurus project.
 *
 * <p>Serves static HTML files, provides a full-text search API endpoint ({@code /search?q=...}),
 * and exposes a build trigger endpoint ({@code POST /api/build}) for on-demand rebuilds.
 */
public class SearchServer {

    private final Path staticDir;
    private final Path docsDir;
    private final int port;
    private final Runnable rebuild;
    private final boolean production;
    private final LuceneSearcher searcher;
    private final Map<String, LuceneSearcher> localeSearchers = new HashMap<>();

    /**
     * @param staticDir  directory containing the generated static HTML files
     * @param indexDir   directory containing the Lucene search index
     * @param port       HTTP port to listen on
     * @param rebuild    callback to trigger a full rebuild (build + reindex)
     * @param production {@code true} to disable the {@code /api/build} endpoint
     * @param docsDir    source directory containing raw Markdown files (for MCP tools)
     */
    public SearchServer(Path staticDir, Path indexDir, int port, Runnable rebuild,
                        boolean production, Path docsDir) {
        this.staticDir = staticDir;
        this.docsDir = docsDir;
        this.port = port;
        this.rebuild = rebuild;
        this.production = production;
        this.searcher = new LuceneSearcher(indexDir);
        // Load locale-specific indexes from search-index/<locale>/
        try {
            if (Files.isDirectory(indexDir)) {
                Files.list(indexDir)
                    .filter(Files::isDirectory)
                    .forEach(locDir -> localeSearchers.put(
                        locDir.getFileName().toString(), new LuceneSearcher(locDir)));
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
            server.createContext("/api/build", this::handleBuild);
            server.createContext("/api/upload", this::handleUpload);
            server.createContext("/upload", this::handleUploadPage);
        }
        server.createContext("/search", this::handleSearch);
        // MCP endpoint for LLM tool access
        var mcpHandler = new McpHandler(docsDir, searcher, rebuild, localeSearchers);
        server.createContext("/mcp", mcpHandler::handle);
        server.createContext("/", this::handleStatic);
        server.setExecutor(null);
        server.start();
        System.out.println("Serving at http://localhost:" + server.getAddress().getPort());
        System.out.println("Press Ctrl+C to stop.");
        return server;
    }

    // ---- Build endpoint -----------------------------------------

    /**
     * Handles {@code POST /api/build} requests. Triggers a full rebuild and returns
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

    // ---- Search endpoint ----------------------------------------

    /**
     * Handles {@code GET /search?q=...} requests. Extracts the query string from the
     * URL parameters and returns search results as a JSON array with CORS headers.
     */
    private void handleSearch(HttpExchange ex) throws IOException {
        String q = HttpUtils.queryParam(ex, "q");
        String locale = HttpUtils.queryParam(ex, "locale");
        LuceneSearcher s = (!locale.isEmpty() && localeSearchers.containsKey(locale))
            ? localeSearchers.get(locale) : searcher;
        HttpUtils.respond(ex, 200, "application/json; charset=UTF-8", search(q, s));
    }

    /**
     * Executes a Lucene full-text search against the index directory.
     * Searches across title, document ID, and body fields with boosted weighting.
     * Returns up to 20 results as a JSON array of objects with title, path, and summary.
     *
     * @param queryStr the user's search query; blank returns an empty array
     * @return JSON array string of search results
     */
    private String search(String queryStr, LuceneSearcher s) {
        try {
            var hits = s.search(queryStr, 20,
                new String[]{"title_idx", "doc_id_idx", "path_tokens", "meta", "body"},
                Map.of("title_idx", 3.0f, "doc_id_idx", 5.0f, "path_tokens", 5.0f, "meta", 2.0f, "body", 1.0f));
            var sb = new StringBuilder("[");
            boolean first = true;
            for (var hit : hits) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                  .append("\"title\":").append(HttpUtils.jsonStr(hit.title())).append(",")
                  .append("\"path\":").append(HttpUtils.jsonStr(hit.path())).append(",")
                  .append("\"summary\":").append(HttpUtils.jsonStr(hit.summary()))
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

        Path file = staticDir.resolve(path.replaceFirst("^/", "")).normalize();
        if (!file.startsWith(staticDir)) { respond(ex, 403, "text/plain", "Forbidden"); return; }
        if (Files.isDirectory(file)) {
            file = file.resolve("index.html").normalize();
            if (!file.startsWith(staticDir) || !Files.exists(file)) {
                respond(ex, 404, "text/html",
                    "<html><body><h1>404 Not Found</h1><p>" + HttpUtils.escapeHtml(path) + "</p></body></html>");
                return;
            }
        } else if (!Files.exists(file)) {
            respond(ex, 404, "text/html",
                "<html><body><h1>404 Not Found</h1><p>" + HttpUtils.escapeHtml(path) + "</p></body></html>");
            return;
        }

        byte[] body = Files.readAllBytes(file);
        HttpUtils.respond(ex, 200, HttpUtils.contentType(file.toString()), body);
    }

    // ---- Upload endpoint ----------------------------------------

    /**
     * Handles {@code POST /api/upload?dir=subdir&filename=paper.pdf} requests.
     * Accepts raw PDF bytes, saves the file, extracts text, writes a companion
     * {@code .md} file, and triggers a rebuild+reindex.
     */
    private void handleUpload(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String filename = HttpUtils.queryParam(ex, "filename");
        String dir      = HttpUtils.queryParam(ex, "dir");

        if (filename.isBlank() || !filename.toLowerCase().endsWith(".pdf")) {
            respond(ex, 400, "application/json", "{\"error\":\"filename must end with .pdf\"}");
            return;
        }
        // Sanitize filename — strip any path separators
        filename = Path.of(filename).getFileName().toString();

        Path targetDir = dir.isBlank() ? docsDir : docsDir.resolve(dir).normalize();
        if (!targetDir.startsWith(docsDir)) {
            respond(ex, 400, "application/json", "{\"error\":\"Invalid directory\"}");
            return;
        }
        Files.createDirectories(targetDir);

        Path pdfPath = targetDir.resolve(filename);
        byte[] pdfBytes = ex.getRequestBody().readAllBytes();
        Files.write(pdfPath, pdfBytes);

        String stem = filename.substring(0, filename.length() - 4);
        Path mdPath = targetDir.resolve(stem + ".md");
        try {
            String md = PdfExtractor.extract(pdfPath);
            Files.writeString(mdPath, md, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("PDF extraction failed for " + filename + ": " + e.getMessage());
            Files.writeString(mdPath,
                "---\ntitle: \"" + stem + "\"\nsource_pdf: \"" + filename + "\"\n---\n\n(Text extraction failed)",
                StandardCharsets.UTF_8);
        }

        rebuild.run();
        respond(ex, 200, "application/json",
            "{\"status\":\"ok\",\"pdf\":\"" + filename + "\",\"md\":\"" + stem + ".md\"}");
    }

    /**
     * Serves a minimal PDF upload page at {@code GET /upload}.
     */
    private void handleUploadPage(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        respond(ex, 200, "text/html; charset=UTF-8", uploadPageHtml(""));
    }

    static String uploadPageHtml(String projectPrefix) {
        String uploadUrl = projectPrefix + "/api/upload";
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <title>Upload PDF</title>
              <style>
                body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
                     background:#1e1e2e;color:#cdd6f4;margin:0;padding:2rem;}
                h2{margin-bottom:1rem;}
                .controls{display:flex;gap:0.75rem;margin-bottom:1rem;flex-wrap:wrap;}
                input[type=text]{padding:0.4rem 0.7rem;border-radius:4px;border:1px solid #585b70;
                  background:#313244;color:#cdd6f4;font-size:0.875rem;width:280px;}
                .drop-zone{border:2px dashed #585b70;border-radius:8px;padding:3rem 2rem;
                  text-align:center;cursor:pointer;color:#a6adc8;transition:border-color 0.2s;}
                .drop-zone.over{border-color:#a6e3a1;color:#a6e3a1;}
                #status{margin-top:1rem;font-size:0.875rem;}
              </style>
            </head>
            <body>
              <h2>Upload PDF</h2>
              <div class="controls">
                <input type="text" id="dir" placeholder="Subdirectory (e.g. papers/2024)">
              </div>
              <div class="drop-zone" id="dz">
                Drop PDF here or click to select
                <input type="file" id="fi" accept=".pdf" style="display:none">
              </div>
              <div id="status"></div>
              <script>
              const dz=document.getElementById('dz'),fi=document.getElementById('fi'),st=document.getElementById('status');
              dz.addEventListener('click',()=>fi.click());
              dz.addEventListener('dragover',e=>{e.preventDefault();dz.classList.add('over');});
              dz.addEventListener('dragleave',()=>dz.classList.remove('over'));
              dz.addEventListener('drop',e=>{e.preventDefault();dz.classList.remove('over');upload(e.dataTransfer.files[0]);});
              fi.addEventListener('change',()=>upload(fi.files[0]));
              async function upload(file){
                if(!file||!file.name.endsWith('.pdf')){st.textContent='Please select a PDF file.';return;}
                st.textContent='Uploading '+file.name+'...';
                const dir=document.getElementById('dir').value.trim();
                const url='%s?filename='+encodeURIComponent(file.name)+(dir?'&dir='+encodeURIComponent(dir):'');
                try{
                  const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/pdf'},body:file});
                  const j=await r.json();
                  st.textContent=j.status==='ok'?'Done: '+j.md:'Error: '+(j.error||'unknown');
                }catch(e){st.textContent='Error: '+e.message;}
              }
              </script>
            </body></html>
            """.formatted(uploadUrl);
    }

    private void respond(HttpExchange ex, int code, String ct, String body) throws IOException {
        HttpUtils.respond(ex, code, ct, body);
    }
}
