package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Entry point for html-saurus. Supports two modes of operation:
 *
 * <ul>
 *   <li><b>Single-project mode</b> (default): converts one Docusaurus project's
 *       {@code docs/} directory into static HTML, optionally serving it.</li>
 *   <li><b>Portal mode</b> ({@code --portal-mode}): discovers all Docusaurus projects
 *       under a root directory and serves them through a unified portal with cross-project search.</li>
 * </ul>
 *
 * <p>Usage: {@code java -jar html-saurus.jar [path] [--serve] [--portal-mode] [--port N] [--production]}
 */
public class Main {

    /**
     * Parses command-line arguments and launches either single-project or portal mode.
     *
     * @param args command-line arguments
     * @throws Exception if an I/O error or build failure occurs
     */
    public static void main(String[] args) throws Exception {
        Path rootDir = null;
        boolean serve = false;
        boolean portalMode = false;
        boolean production = false;
        int port = 8080;

        // Independent build-step selectors. When any of these is given, html-saurus runs
        // ONLY the selected steps (in dependency order html -> index -> embedding) and exits,
        // without starting a server. This lets the three build stages — static HTML, the
        // Lucene full-text index, and the embedding (RAG) vectors — be produced separately,
        // e.g. so an HTML refresh does not have to wait on (or fail because of) the embedding
        // server. When none is given, behaviour is unchanged.
        boolean stepHtml = false;
        boolean stepIndex = false;
        boolean stepEmbedding = false;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--serve")) serve = true;
            else if (args[i].equals("--portal-mode")) portalMode = true;
            else if (args[i].equals("--production")) production = true;
            else if (args[i].equals("--html")) stepHtml = true;
            else if (args[i].equals("--index")) stepIndex = true;
            else if (args[i].equals("--embedding") || args[i].equals("--embed")) stepEmbedding = true;
            else if (!args[i].startsWith("--")) rootDir = Path.of(args[i]).toAbsolutePath();
        }

        // --production implies --serve
        if (production) serve = true;

        if (rootDir == null) rootDir = Path.of("").toAbsolutePath();

        boolean explicitSteps = stepHtml || stepIndex || stepEmbedding;

        // Startup mode summary — always printed so callers (e.g. service-portal) can verify options
        String mode = portalMode ? "PORTAL" : "SINGLE";
        String action;
        if (explicitSteps) {
            List<String> steps = new ArrayList<>();
            if (stepHtml) steps.add("html");
            if (stepIndex) steps.add("index");
            if (stepEmbedding) steps.add("embedding");
            action = "build-steps(" + String.join("+", steps) + ")";
        } else {
            action = production ? "build+serve(production)" : serve ? "build+serve" : "build-only";
        }
        System.out.println("=== html-saurus startup ===");
        System.out.println("  mode    : " + mode);
        System.out.println("  rootDir : " + rootDir);
        System.out.println("  action  : " + action);
        System.out.println("  port    : " + (!explicitSteps && (serve || production) ? String.valueOf(port) : "n/a"));
        System.out.println("  args    : " + String.join(" ", args));
        System.out.println("===========================");

        // Explicit per-step build: run only the selected stages and exit (never serves).
        if (explicitSteps) {
            runSteps(rootDir, portalMode, production, stepHtml, stepIndex, stepEmbedding);
            return;
        }

        if (portalMode) {
            runPortal(rootDir, port, serve, production);
        } else {
            runSingle(rootDir, port, serve, production);
        }

        if (serve || production) {
            registerWithGateway(port);
        }
    }

    /**
     * Runs the explicitly selected build stages independently, then returns (no server is started).
     *
     * <p>The three stages are produced by separate subsystems and can be run in any combination:
     * <ul>
     *   <li>{@code html} — static HTML generation ({@link SiteBuilder}), written to {@code static-html/}</li>
     *   <li>{@code index} — Lucene full-text index ({@link SearchIndexer}), written to {@code search-index/}</li>
     *   <li>{@code embedding} — embedding/RAG vectors ({@link SemanticIndexer}), written to
     *       {@code search-embedding/}; requires the shared embedding server (warns and skips if unreachable)</li>
     * </ul>
     * Selected stages run in dependency order (html, then index, then embedding) because the index
     * reflects the built docs and the embedding cache is keyed off the index. In portal mode the
     * html and index stages run per discovered project; the embedding stage processes all projects together.
     *
     * @param rootDir      single project directory, or (in portal mode) the root containing many projects
     * @param portalMode   whether to discover multiple projects under {@code rootDir}
     * @param production   whether to use production-mode clean URLs
     * @param doHtml       run the static HTML stage
     * @param doIndex      run the Lucene index stage
     * @param doEmbedding  run the embedding (RAG) vector stage
     * @throws IOException if project discovery fails
     */
    static void runSteps(Path rootDir, boolean portalMode, boolean production,
                         boolean doHtml, boolean doIndex, boolean doEmbedding) throws IOException {
        List<Path> projects = portalMode ? findProjects(rootDir) : List.of(rootDir);
        if (projects.isEmpty()) {
            System.err.println("No Docusaurus projects found under " + rootDir);
            return;
        }

        if (doHtml) {
            for (Path p : projects) {
                build(p.resolve("docs"), p.resolve("static-html"), production);
            }
        }
        if (doIndex) {
            for (Path p : projects) {
                reindexAll(p, production);
            }
        }
        if (doEmbedding) {
            // Operates across all projects at once; non-fatal if the embedding server is unreachable.
            ensureSemanticVectors(projects);
        }
    }

    private static void registerWithGateway(int port) {
        String gatewayUrl = System.getenv("MCP_GATEWAY_URL");
        if (gatewayUrl == null || gatewayUrl.isBlank()) return;

        String name = "html-saurus-" + port;
        String url = "http://localhost:" + port;
        String body = "{\"name\":\"" + name + "\",\"url\":\"" + url
                + "\",\"description\":\"Documentation Portal (port " + port + ")\"}";

        Thread.ofVirtual().start(() -> {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5)).build();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(gatewayUrl + "/api/servers"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .build();
                java.net.http.HttpResponse<String> response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                System.out.println("Registered with gateway: " + name + " -> HTTP " + response.statusCode());
            } catch (Exception e) {
                System.err.println("Failed to register with gateway: " + e.getMessage());
            }
        });
    }

    /**
     * Runs in single-project mode: builds, indexes, and optionally serves one Docusaurus project.
     *
     * @param projectDir root directory of the Docusaurus project (must contain {@code docs/})
     * @param port       HTTP port for the development server
     * @param serve      whether to start a development server
     * @param production whether to run in production mode
     * @throws Exception if an I/O error occurs
     */
    private static void runSingle(Path projectDir, int port, boolean serve,
                                   boolean production) throws Exception {
        Path docsDir  = projectDir.resolve("docs");
        Path outDir   = projectDir.resolve("static-html");
        Path indexDir = projectDir.resolve("search-index");

        System.out.println("project : " + projectDir);
        build(docsDir, outDir, production);

        if (serve) {
            // Build search index only when serving; build-only mode does not need it.
            reindexAll(projectDir, production);
            ensureSemanticVectors(List.of(projectDir));
            SemanticIndex semanticIndex = SemanticIndex.load(List.of(projectDir), SEMANTIC_TOP_K);
            Runnable rebuild = () -> { build(docsDir, outDir, false); reindexAll(projectDir, false); };
            new SearchServer(outDir, indexDir, port, rebuild, production, docsDir, semanticIndex).start();
        }
    }

    /**
     * Runs in portal mode: discovers all Docusaurus projects under {@code worksDir},
     * builds them, and optionally serves them through a unified portal.
     *
     * @param worksDir   root directory containing multiple Docusaurus projects
     * @param port       HTTP port for the portal server
     * @param serve      whether to start the portal server
     * @param production whether to run in production mode
     * @throws Exception if an I/O error occurs
     */
    private static void runPortal(Path worksDir, int port, boolean serve,
                                   boolean production) throws Exception {
        List<Path> projects = findProjects(worksDir);
        if (projects.isEmpty()) {
            System.err.println("No Docusaurus projects found under " + worksDir);
            // In serve mode, still start the (empty) portal so the port binds and the process
            // stays up for the supervisor (AI workspace marks the tool Failed if the port never
            // opens). Docusaurus projects added under worksDir later appear on restart. Only
            // build-only mode has nothing to do when there are no projects.
            if (!serve) return;
        }

        System.out.println("Portal root : " + worksDir);
        System.out.println("Projects    : " + projects.size());

        for (Path p : projects) {
            System.out.println("  [" + p.getFileName() + "]");
            Path staticDir = p.resolve("static-html");
            Path indexDir  = p.resolve("search-index");
            if (!Files.isDirectory(staticDir)) {
                build(p.resolve("docs"), staticDir, production);
            }
            if (!Files.isDirectory(indexDir)) {
                reindexAll(p, production);
            }
        }

        if (serve) {
            // Bind the port and start serving FIRST, using whatever semantic vectors are already
            // cached. Building the whole corpus' embeddings (ensureSemanticVectors) can take many
            // minutes; running it before the bind kept the port closed long enough for the process
            // supervisor (quarkus AI workspace) to mark html-saurus Failed. start() is non-blocking
            // and the HttpServer serves on its own thread pool, so full-text search is available
            // immediately while this (main) thread refreshes the semantic vectors. The refreshed
            // vectors take effect on the next restart; after the first build they are cached, so
            // subsequent restarts bind and have semantic search ready quickly.
            SemanticIndex semanticIndex = SemanticIndex.load(projects, SEMANTIC_TOP_K);
            new PortalServer(worksDir, projects, port, production, semanticIndex).start();
            System.out.println("Portal serving on http://0.0.0.0:" + port
                    + "  (full-text ready; refreshing semantic vectors, effective next restart...)");
            ensureSemanticVectors(projects);
            System.out.println("Semantic vectors refresh complete.");
        }
    }

    /** Number of semantic neighbours kept per document. */
    static final int SEMANTIC_TOP_K = 20;

    /**
     * Ensures each project's embedding vector cache ({@code search-embedding/vectors.bin})
     * is present and not older than its {@code search-index/}, (re)building stale ones via
     * the shared embedding server. The model is NOT run in-process: each document's text is
     * sent over HTTP (default {@link EmbeddingClient#DEFAULT_BASE_URL}; override with
     * {@code EMBEDDING_SERVER_URL}).
     *
     * <p>If the embedding server is unreachable, this logs a warning and returns: stale or
     * missing projects keep whatever cached vectors they already have (possibly none), so the
     * semantic widget shows fewer/no results, while the independent TF-IDF related-docs and
     * the portal itself are unaffected.
     *
     * @param projects Docusaurus project directories whose built indexes are embedded
     */
    static void ensureSemanticVectors(List<Path> projects) {
        String url = System.getenv("EMBEDDING_SERVER_URL");
        EmbeddingClient embed = new EmbeddingClient(url);
        if (!embed.isReachable()) {
            System.err.println("WARNING: embedding server not reachable at " + embed.baseUrl()
                    + " — semantic vectors not (re)built this run; using any cached vectors. "
                    + "(TF-IDF related-docs unaffected.) Set EMBEDDING_SERVER_URL to override.");
            return;
        }
        System.out.println("Ensuring semantic vectors via " + embed.baseUrl() + " ...");
        SemanticIndexer.ensureVectors(projects, embed);
    }

    /**
     * Discovers Docusaurus projects under the given directory.
     * A directory is considered a Docusaurus project if it contains both a {@code docs/}
     * subdirectory and a {@code docusaurus.config.js} or {@code docusaurus.config.ts} file.
     *
     * @param worksDir the parent directory to scan
     * @return sorted list of paths to detected Docusaurus project directories
     * @throws IOException if the directory cannot be listed
     */
    static List<Path> findProjects(Path worksDir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(worksDir)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> Files.isDirectory(p.resolve("docs"))
                            && (Files.exists(p.resolve("docusaurus.config.js"))
                             || Files.exists(p.resolve("docusaurus.config.ts"))))
                  .sorted()
                  .forEach(result::add);
        }
        return result;
    }

    /**
     * Builds static HTML from Markdown files in the docs directory.
     *
     * @param docsDir    source directory containing Markdown files
     * @param outDir     target directory for generated HTML files
     * @param production whether to build in production mode
     */
    static void build(Path docsDir, Path outDir, boolean production) {
        try {
            Path projectDir = docsDir.getParent();
            String[] i18n = readI18nConfig(projectDir);
            String defaultLocale = i18n.length > 0 ? i18n[0] : null;

            // Collect locales that have actual docs (default + alternates with i18n content)
            List<String> availableLocales = new ArrayList<>();
            if (defaultLocale != null) availableLocales.add(defaultLocale);
            for (int i = 1; i < i18n.length; i++) {
                Path localeDocs = projectDir.resolve(
                    "i18n/" + i18n[i] + "/docusaurus-plugin-content-docs/current");
                if (hasMarkdownFiles(localeDocs)) availableLocales.add(i18n[i]);
            }
            List<String> localeList = List.copyOf(availableLocales);

            // Build default locale
            new SiteBuilder(docsDir, outDir, production, defaultLocale, defaultLocale, localeList).build();
            System.out.println("  build done : " + docsDir);

            // Build each alternate locale
            for (int i = 1; i < localeList.size(); i++) {
                String locale = localeList.get(i);
                Path localeDocs = projectDir.resolve(
                    "i18n/" + locale + "/docusaurus-plugin-content-docs/current");
                Path localeOut = outDir.resolve(locale);
                new SiteBuilder(localeDocs, localeOut, production, locale, defaultLocale, localeList).build();
                System.out.println("  build done : " + localeDocs);
            }
        } catch (IOException e) {
            System.err.println("Build failed: " + e.getMessage());
        }
    }

    /**
     * Reads i18n configuration from {@code docusaurus.config.ts} or {@code .js}.
     * Returns an array where index 0 is the defaultLocale and subsequent entries are
     * alternate locales. Returns an empty array if no i18n config is found.
     */
    static String[] readI18nConfig(Path projectDir) {
        Path config = null;
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path p = projectDir.resolve(name);
            if (Files.exists(p)) { config = p; break; }
        }
        if (config == null) return new String[0];
        try {
            String content = Files.readString(config);
            var defM = java.util.regex.Pattern.compile("defaultLocale:\\s*['\"]([^'\"]+)['\"]")
                           .matcher(content);
            if (!defM.find()) return new String[0];
            String defaultLocale = defM.group(1);

            var locM = java.util.regex.Pattern.compile("locales:\\s*\\[([^\\]]+)\\]")
                           .matcher(content);
            List<String> locales = new ArrayList<>();
            locales.add(defaultLocale);
            if (locM.find()) {
                var itemM = java.util.regex.Pattern.compile("['\"]([^'\"]+)['\"]")
                                .matcher(locM.group(1));
                while (itemM.find()) {
                    String loc = itemM.group(1);
                    if (!loc.equals(defaultLocale)) locales.add(loc);
                }
            }
            return locales.toArray(new String[0]);
        } catch (IOException e) {
            return new String[0];
        }
    }

    /** Returns true if {@code dir} exists and contains at least one {@code .md} file. */
    static boolean hasMarkdownFiles(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".md"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Rebuilds search indexes for all locales of a project.
     * Indexes the default locale into {@code search-index/} and each alternate locale
     * (when it has Markdown content) into {@code search-index/<locale>/}.
     *
     * @param projectDir root directory of the project
     * @param production whether to use production-mode clean URLs in the index
     */
    static void reindexAll(Path projectDir, boolean production) {
        String[] i18n = readI18nConfig(projectDir);
        String defaultLocale = i18n.length > 0 ? i18n[0] : null;
        Path baseIndexDir = projectDir.resolve("search-index");

        // Index default locale
        reindex(projectDir.resolve("docs"), baseIndexDir, defaultLocale, production);

        // Index alternate locales
        for (int i = 1; i < i18n.length; i++) {
            String locale = i18n[i];
            Path localeDocs = projectDir.resolve(
                "i18n/" + locale + "/docusaurus-plugin-content-docs/current");
            if (hasMarkdownFiles(localeDocs)) {
                reindex(localeDocs, baseIndexDir.resolve(locale), locale, production);
            }
        }
    }

    /**
     * Builds a Lucene full-text search index from Markdown files.
     *
     * @param docsDir  source directory containing Markdown files
     * @param indexDir target directory for the Lucene index
     */
    static void reindex(Path docsDir, Path indexDir) {
        reindex(docsDir, indexDir, null, false);
    }

    /**
     * Builds a Lucene full-text search index from Markdown files for a specific locale.
     *
     * @param docsDir    source directory containing Markdown files
     * @param indexDir   target directory for the Lucene index
     * @param locale     locale code (e.g. {@code "ja"}, {@code "en"}), or {@code null} for Japanese default
     * @param production whether to use production-mode clean URLs
     */
    static void reindex(Path docsDir, Path indexDir, String locale, boolean production) {
        try {
            Files.createDirectories(indexDir);
            new SearchIndexer(docsDir, indexDir, locale, production).index();
            System.out.println("  index done : " + indexDir);
        } catch (IOException e) {
            System.err.println("Index failed: " + e.getMessage());
        }
    }
}
