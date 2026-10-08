package com.scivicslab.htmlsaurus;

import java.nio.file.Path;

/**
 * Entry point for production mode: build one Docusaurus project and serve it to the internet.
 *
 * <p>Usage: {@code java -jar html-saurus-production-server.jar <project> [--port N] [--threads N]}
 *
 * <p>No option selects a mode. Which mode html-saurus ran in used to come from {@code --production}
 * / {@code --portal-mode} / {@code --serve} and the way they combined, and one jar held the code
 * for all of them. A mode is a jar now: running this one is production mode, and nothing on the
 * command line can turn it into another.
 *
 * <p>The pages and the index are rebuilt on every start, before the port opens, so a container that
 * ships only the Markdown serves what that Markdown says. It also means an index left behind by an
 * older Lucene is replaced rather than read. {@code --threads} is how fast that start-up build
 * goes.
 */
public final class ProductionServerMain {

    private ProductionServerMain() {}

    public static void main(String[] args) throws Exception {
        Options o = new Options("html-saurus-production-server")
                .value("--port")
                // Page-conversion parallelism for the build this server does at start-up. 0 keeps
                // SiteBuilder's own default of 4 (BuildParallelization_260822_oo01), deliberately
                // not Runtime.availableProcessors() so a shared machine is not saturated by a
                // default no one chose.
                .value("--threads")
                .obsolete("--production", false, "running this jar is production mode")
                .obsolete("--serve", false, "this jar always serves")
                .obsolete("--portal-mode", false, "the portal is html-saurus-portal-server")
                // The figures beside the Markdown are drawn by a tool a published site does not
                // carry, so the step skips itself here and there is nothing to turn off.
                .obsolete("--no-diagrams", false, "this jar has no figure tool to run")
                .parse(args);

        Path projectDir = o.path();
        int port = o.number("--port", 8080);
        int threads = o.number("--threads", 0);

        Path docsDir = projectDir.resolve("docs");
        Path outDir = projectDir.resolve("static-html");
        Path indexDir = projectDir.resolve("search-index");

        System.out.println("=== html-saurus production server ===");
        System.out.println("  project : " + projectDir);
        System.out.println("  port    : " + port);
        System.out.println("  args    : " + String.join(" ", args));
        System.out.println("=====================================");

        BuildStages.build(docsDir, outDir, true, threads);
        BuildStages.reindexAll(projectDir, true);

        new SearchServer(outDir, indexDir, port).start();
    }
}
