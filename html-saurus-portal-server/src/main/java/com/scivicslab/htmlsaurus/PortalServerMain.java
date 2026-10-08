package com.scivicslab.htmlsaurus;

import com.scivicslab.pluggablecli.CommandRepository;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for portal mode: serve every Docusaurus project under one root, on the machine the
 * documents are written on.
 *
 * <p>Usage: {@code java -jar html-saurus-portal-server.jar serve -d <works-dir> [-p N] [-t N]}
 *
 * <p>This is the mode with the endpoints that reach into the machine: importing a PDF, reading and
 * writing the Markdown over MCP, rebuilding a project. None of that is reachable from the published
 * site, because none of it is in that jar.
 *
 * <p>No option selects a mode. Which mode html-saurus ran in used to come from {@code --production}
 * / {@code --portal-mode} / {@code --serve} and the way they combined. A mode is a jar now.
 */
public final class PortalServerMain {

    private static final String SYNOPSIS =
            "java -jar html-saurus-portal-server-<VERSION>.jar <command> <options>";

    private final CommandRepository cmds = new CommandRepository();

    public static void main(String[] args) {
        PortalServerMain app = new PortalServerMain();
        app.setupCommands();
        // Only on failure: a server returns from run() with its HttpServer threads still going,
        // and System.exit(0) would take them with it.
        int status = CliRunner.run(app.cmds, SYNOPSIS, args);
        if (status != 0) System.exit(status);
    }

    private void setupCommands() {
        serveCommand();
    }

    private void serveCommand() {
        Options opts = new Options();

        opts.addOption(Option.builder("d")
                .longOpt("dir")
                .hasArg(true)
                .argName("dir")
                .desc("The directory to scan for Docusaurus projects.")
                .required(true)
                .build());

        opts.addOption(Option.builder("p")
                .longOpt("port")
                .hasArg(true)
                .argName("port")
                .desc("Port to listen on (default: 8080).")
                .required(false)
                .build());

        opts.addOption(Option.builder("t")
                .longOpt("threads")
                .hasArg(true)
                .argName("threads")
                .desc("""
                        Pages converted in parallel by every build this server runs (default: 4), \
                        both the ones at start-up and the ones started from the Projects tab.""")
                .required(false)
                .build());

        String description = """
                Serves every Docusaurus project under one directory, to its author.

                A project with no static-html/ is built at start-up, and one whose search-index/
                this build of Lucene cannot read is rebuilt. Serving begins before the embedding
                vectors are refreshed, because that can take minutes and the port has to open.

                This is the server with the endpoints that reach into the machine it runs on:
                /mcp reads and writes files and rebuilds projects without asking for a credential,
                and the Import tab fetches PDFs, Word files, web pages and video transcripts. It
                binds 0.0.0.0.

                For example:
                $ java -jar html-saurus-portal-server.jar serve -d ~/works -p 28001
                """;

        cmds.addCommand("serve", opts, description, (CommandLine cl) -> {
            Path worksDir = Path.of(cl.getOptionValue("dir")).toAbsolutePath();
            int port = Integer.parseInt(cl.getOptionValue("port", "8080"));
            int threads = Integer.parseInt(cl.getOptionValue("threads", "0"));
            try {
                serve(worksDir, port, threads);
            } catch (Exception e) {
                System.err.println("Portal failed to start: " + e.getMessage());
                System.exit(1);
            }
        });
    }

    private void serve(Path worksDir, int port, int threads) throws Exception {
        List<Path> projects = Projects.findProjects(worksDir);
        System.out.println("=== html-saurus portal ===");
        System.out.println("  root     : " + worksDir);
        System.out.println("  projects : " + projects.size());
        System.out.println("  port     : " + port);
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
