package com.scivicslab.htmlsaurus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for portal mode: serve every Docusaurus project under one root, on the machine the
 * documents are written on.
 *
 * <p>Usage: {@code java -jar html-saurus-portal-server.jar <works-dir> [--port N] [--threads N]
 * [--no-diagrams]}
 *
 * <p>This is the mode with the endpoints that reach into the machine: importing a PDF, reading
 * and writing the Markdown over MCP, rebuilding a project. None of that is reachable from the
 * public site, because none of it is in that jar.
 *
 * <p>There is no option that selects a mode. Which mode html-saurus runs in used to come from
 * {@code --production} / {@code --portal-mode} / {@code --serve} and the way they combined, and
 * one jar held the code for all of them. A mode is now a jar.
 */
public final class PortalServerMain {

    private PortalServerMain() {}

    public static void main(String[] args) throws Exception {
        Path worksDir = null;
        int port = 8080;
        // 0 = unspecified: SiteBuilder keeps its own default (4 -- see
        // BuildParallelization_260822_oo01, deliberately not Runtime.availableProcessors() so a
        // shared machine isn't saturated by default).
        int threads = 0;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--threads") && i + 1 < args.length) threads = Integer.parseInt(args[++i]);
            else if (args[i].equals("--no-diagrams")) BuildStages.skipDiagrams(true);
            else if (!args[i].startsWith("--")) worksDir = Path.of(args[i]).toAbsolutePath();
            // --portal-mode and --serve chose a mode when one jar held every mode. Running this
            // jar is the mode now. They are accepted and ignored so a stored command line (the
            // AI workspace tool entry passes both) keeps working.
            else if (args[i].equals("--portal-mode") || args[i].equals("--serve")) { }
            else if (args[i].startsWith("--")) {
                System.err.println("Ignoring unknown option " + args[i]);
            }
        }
        if (worksDir == null) worksDir = Path.of("").toAbsolutePath();

        List<Path> projects = Projects.findProjects(worksDir);
        System.out.println("=== html-saurus portal ===");
        System.out.println("  root     : " + worksDir);
        System.out.println("  projects : " + projects.size());
        System.out.println("  port     : " + port);
        System.out.println("  args     : " + String.join(" ", args));
        System.out.println("==========================");
        if (projects.isEmpty()) {
            // Still start the (empty) portal so the port binds and the process stays up for the
            // supervisor (AI workspace marks the tool Failed if the port never opens). Projects
            // added under worksDir later appear on restart.
            System.err.println("No Docusaurus projects found under " + worksDir);
        }

        for (Path p : projects) {
            System.out.println("  [" + p.getFileName() + "]");
            Path staticDir = p.resolve("static-html");
            Path indexDir = p.resolve("search-index");
            if (!Files.isDirectory(staticDir)) {
                BuildStages.build(p.resolve("docs"), staticDir, false, threads);
            }
            // Not "is it there" but "can this build read it": a Lucene major-version upgrade and
            // an interrupted write both leave a directory that every query then throws on.
            if (!SearchIndexer.isUsableIndex(indexDir)) {
                BuildStages.reindexAll(p, false);
            }
        }

        // Bind the port and start serving FIRST, using whatever semantic vectors are already
        // cached. Building the whole corpus' embeddings (ensureSemanticVectors) can take many
        // minutes; running it before the bind kept the port closed long enough for the process
        // supervisor (quarkus AI workspace) to mark html-saurus Failed. start() is non-blocking
        // and the HttpServer serves on its own thread pool, so full-text search is available
        // immediately while this (main) thread refreshes the semantic vectors. The refreshed
        // vectors take effect on the next restart; after the first build they are cached, so
        // subsequent restarts bind and have semantic search ready quickly.
        SemanticIndex semanticIndex = SemanticIndex.load(projects, BuildStages.SEMANTIC_TOP_K);
        new PortalServer(worksDir, projects, port, semanticIndex, threads).start();
        System.out.println("Portal serving on http://0.0.0.0:" + port
                + "  (full-text ready; refreshing semantic vectors, effective next restart...)");
        BuildStages.ensureSemanticVectors(projects);
        System.out.println("Semantic vectors refresh complete.");
    }
}
