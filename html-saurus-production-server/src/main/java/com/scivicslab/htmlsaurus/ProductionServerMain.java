package com.scivicslab.htmlsaurus;

import com.scivicslab.pluggablecli.CommandRepository;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import java.nio.file.Path;

/**
 * Entry point for production mode: build one Docusaurus project and serve it to the internet.
 *
 * <p>Usage: {@code java -jar html-saurus-production-server.jar serve <project> [-p N] [-t N]}
 *
 * <p>No option selects a mode. Which mode html-saurus ran in used to come from {@code --production}
 * / {@code --portal-mode} / {@code --serve} and the way they combined, and one jar held the code
 * for all of them. A mode is a jar now: running this one is production mode, and nothing on the
 * command line can turn it into another. {@code serve} is the one thing this jar does, and it is
 * written down because pluggable-cli reads the first word as the command — which is also what
 * gives every program here the same {@code -h}, the same help layout and the same message when an
 * option is wrong.
 *
 * <p>The pages and the index are rebuilt on every start, before the port opens, so a container that
 * ships only the Markdown serves what that Markdown says. It also means an index left behind by an
 * older Lucene is replaced rather than read.
 */
public final class ProductionServerMain {

    private static final String SYNOPSIS =
            "java -jar html-saurus-production-server-<VERSION>.jar <command> <options>";

    private final CommandRepository cmds = new CommandRepository();

    public static void main(String[] args) {
        ProductionServerMain app = new ProductionServerMain();
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
                .desc("The Docusaurus project to build and serve. Must hold docs/.")
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
                        Pages converted in parallel during the start-up build (default: 4).
                        Deliberately not one per processor, so a shared machine is not \
                        saturated by a default no one chose.""")
                .required(false)
                .build());

        String description = """
                Builds the project and serves it, for readers on the internet.

                The pages and the Lucene index are rebuilt before the port opens, so a container
                that ships only the Markdown serves what that Markdown says.

                Three paths are answered and no others: the built pages under /, and /search for
                the default locale and each other locale that has an index. The endpoints that
                read, write and rebuild a project are in html-saurus-portal-server, whose jar this
                one does not contain.

                For example:
                $ java -jar html-saurus-production-server.jar serve -d /data/nigsc_homepage2 -p 80
                """;

        cmds.addCommand("serve", opts, description, (CommandLine cl) -> {
            Path projectDir = Path.of(cl.getOptionValue("dir")).toAbsolutePath();
            int port = Integer.parseInt(cl.getOptionValue("port", "8080"));
            int threads = Integer.parseInt(cl.getOptionValue("threads", "0"));

            Path docsDir = projectDir.resolve("docs");
            Path outDir = projectDir.resolve("static-html");
            Path indexDir = projectDir.resolve("search-index");

            System.out.println("=== html-saurus production server ===");
            System.out.println("  project : " + projectDir);
            System.out.println("  port    : " + port);
            System.out.println("=====================================");

            BuildStages.build(docsDir, outDir, true, threads);
            BuildStages.reindexAll(projectDir, true);
            try {
                new SearchServer(outDir, indexDir, port).start();
            } catch (java.io.IOException e) {
                System.err.println("Could not open port " + port + ": " + e.getMessage());
                System.exit(1);
            }
        });
    }

}
