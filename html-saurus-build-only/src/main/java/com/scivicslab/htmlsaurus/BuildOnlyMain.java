package com.scivicslab.htmlsaurus;

import com.scivicslab.pluggablecli.CommandRepository;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for build-only mode: run one stage and stop. No server is started and no port is
 * opened.
 *
 * <p>Usage: {@code java -jar html-saurus-build-only.jar <html|index|embedding|all> -d <path>}
 *
 * <p>The three stages were {@code --html}, {@code --index} and {@code --embedding}, with "name
 * none of them and all three run" as an unwritten rule. They are commands now, and that rule has
 * a name: {@code all}.
 *
 * <p>A stage reads what the one before it wrote: the index reflects the built pages, and the
 * embedding cache is keyed off the index. Running one at a time is what lets an HTML refresh go
 * ahead without waiting on the embedding server, or failing because it is down.
 */
public final class BuildOnlyMain {

    private static final String SYNOPSIS =
            "java -jar html-saurus-build-only-<VERSION>.jar <command> <options>";

    private final CommandRepository cmds = new CommandRepository();

    public static void main(String[] args) {
        BuildOnlyMain app = new BuildOnlyMain();
        app.setupCommands();
        // Only on failure: a server returns from run() with its HttpServer threads still going,
        // and System.exit(0) would take them with it.
        int status = CliRunner.run(app.cmds, SYNOPSIS, args);
        if (status != 0) System.exit(status);
    }

    private void setupCommands() {
        stageCommand("html", """
                Converts the Markdown to static HTML under static-html/.

                For example:
                $ java -jar html-saurus-build-only.jar html -d ~/works/nigsc_homepage2
                """);
        stageCommand("index", """
                Builds the Lucene full-text index under search-index/, for every locale the
                project declares. Reads the built pages, so run html first when they are stale.
                """);
        stageCommand("embedding", """
                Builds the embedding vectors under search-embedding/, asking the embedding
                server named by EMBEDDING_SERVER_URL. Keyed off search-index/, so run index
                first when it is stale. Reports and returns when that server is unreachable.
                """);
        stageCommand("all", """
                Runs html, then index, then embedding, in that order.

                For example:
                $ java -jar html-saurus-build-only.jar all -d ~/works -a
                """);
    }

    /** The four commands differ only in which stages they run; the options are the same. */
    private void stageCommand(String name, String description) {
        Options opts = new Options();

        opts.addOption(Option.builder("d")
                .longOpt("dir")
                .hasArg(true)
                .argName("dir")
                .desc("The Docusaurus project to build, or the directory to scan with -a.")
                .required(true)
                .build());

        opts.addOption(Option.builder("a")
                .longOpt("all-projects")
                .hasArg(false)
                .desc("Treat -d as a directory holding many projects, and build every one.")
                .required(false)
                .build());

        opts.addOption(Option.builder("t")
                .longOpt("threads")
                .hasArg(true)
                .argName("threads")
                .desc("Pages converted in parallel (default: 4).")
                .required(false)
                .build());

        opts.addOption(Option.builder()
                .longOpt("authoring-controls")
                .hasArg(false)
                .desc("""
                        Put the Rebuild button and the Source / Copy list on each page. They are \
                        for someone editing the Markdown, so a build leaves them out unless asked.""")
                .required(false)
                .build());

        opts.addOption(Option.builder()
                .longOpt("no-diagrams")
                .hasArg(false)
                .desc("""
                        Skip redrawing the figures beside the Markdown. make-diagrams is given ten \
                        minutes, which is the wait this is the way out of.""")
                .required(false)
                .build());

        cmds.addCommand(name, opts, description, (CommandLine cl) -> {
            if (cl.hasOption("no-diagrams")) BuildStages.skipDiagrams(true);
            Path dir = Path.of(cl.getOptionValue("dir")).toAbsolutePath();
            int threads = Integer.parseInt(cl.getOptionValue("threads", "0"));
            boolean production = !cl.hasOption("authoring-controls");

            List<Path> projects;
            try {
                projects = cl.hasOption("all-projects") ? Projects.findProjects(dir) : List.of(dir);
            } catch (IOException e) {
                System.err.println("Could not read " + dir + ": " + e.getMessage());
                System.exit(1);
                return;
            }
            if (projects.isEmpty()) {
                System.err.println("No Docusaurus projects found under " + dir);
                System.exit(1);
                return;
            }

            System.out.println("=== html-saurus build ===");
            System.out.println("  dir      : " + dir);
            System.out.println("  projects : " + projects.size());
            System.out.println("  stage    : " + name);
            System.out.println("=========================");

            boolean all = name.equals("all");
            if (all || name.equals("html")) {
                for (Path p : projects) {
                    BuildStages.build(p.resolve("docs"), p.resolve("static-html"), production, threads);
                }
            }
            if (all || name.equals("index")) {
                for (Path p : projects) {
                    BuildStages.reindexAll(p, production);
                }
            }
            if (all || name.equals("embedding")) {
                // Across all projects at once; non-fatal if the embedding server is unreachable.
                BuildStages.ensureSemanticVectors(projects);
            }
        });
    }

}
