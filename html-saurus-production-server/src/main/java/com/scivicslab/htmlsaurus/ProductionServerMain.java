package com.scivicslab.htmlsaurus;

import java.nio.file.Path;

/**
 * Entry point for production mode: build one Docusaurus project and serve it to the internet.
 *
 * <p>Usage: {@code java -jar html-saurus-production-server.jar <project> [--port N] [--threads N]
 * [--no-diagrams]}
 *
 * <p>There is no option that selects a mode. Which mode html-saurus runs in used to come from
 * {@code --production} / {@code --portal-mode} / {@code --serve} and the way they combined, and
 * one jar held the code for all of them. A mode is now a jar: running this one is production
 * mode, and nothing given on the command line can turn it into another.
 *
 * <p>The pages and the index are rebuilt on every start, before the port opens, so a container
 * that ships only the Markdown serves what that Markdown says. It also means an index left
 * behind by an older Lucene is replaced rather than read.
 */
public final class ProductionServerMain {

    private ProductionServerMain() {}

    public static void main(String[] args) throws Exception {
        Path projectDir = null;
        int port = 8080;
        // 0 = unspecified: SiteBuilder keeps its own default (4 -- see
        // BuildParallelization_260822_oo01, deliberately not Runtime.availableProcessors() so a
        // shared machine isn't saturated by default).
        int threads = 0;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--threads") && i + 1 < args.length) threads = Integer.parseInt(args[++i]);
            else if (args[i].equals("--no-diagrams")) BuildStages.skipDiagrams(true);
            else if (!args[i].startsWith("--")) projectDir = Path.of(args[i]).toAbsolutePath();
            // --production and --serve chose a mode when one jar held every mode. Running this
            // jar is the mode now. They are accepted and ignored so a stored command line keeps
            // working.
            else if (args[i].equals("--production") || args[i].equals("--serve")) { }
            else if (args[i].startsWith("--")) {
                System.err.println("Ignoring unknown option " + args[i]);
            }
        }
        if (projectDir == null) projectDir = Path.of("").toAbsolutePath();

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
