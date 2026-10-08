package com.scivicslab.htmlsaurus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for portal mode: serve every Docusaurus project under one root, on the machine the
 * documents are written on.
 *
 * <p>Usage: {@code java -jar html-saurus-portal-server.jar <works-dir> [--port N] [--threads N]}
 *
 * <p>This is the mode with the endpoints that reach into the machine: importing a PDF, reading and
 * writing the Markdown over MCP, rebuilding a project. None of that is reachable from the published
 * site, because none of it is in that jar.
 *
 * <p>No option selects a mode. Which mode html-saurus ran in used to come from {@code --production}
 * / {@code --portal-mode} / {@code --serve} and the way they combined. A mode is a jar now.
 */
public final class PortalServerMain {

    private PortalServerMain() {}

    public static void main(String[] args) throws Exception {
        Options o = new Options("html-saurus-portal-server")
                .value("--port")
                // Page-conversion parallelism for every build this server runs: the ones at
                // start-up, and the ones someone starts from the Projects tab. 0 keeps
                // SiteBuilder's own default of 4 (BuildParallelization_260822_oo01).
                .value("--threads")
                .obsolete("--portal-mode", false, "running this jar is portal mode")
                .obsolete("--serve", false, "this jar always serves")
                .obsolete("--production", false, "the published site is html-saurus-production-server")
                // Turning the figures off is a build-time choice, and the builds here are started
                // by someone watching them, not by the command line that started the server.
                .obsolete("--no-diagrams", false, "use html-saurus-build-only to build without figures")
                .parse(args);

        Path worksDir = o.path();
        int port = o.number("--port", 8080);
        int threads = o.number("--threads", 0);

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
