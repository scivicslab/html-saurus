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
        docIdsCommand();
        externalLinksCommand();
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
        stageCommand("links", """
                Reports the same-site links in the built pages under static-html/ that resolve to
                nothing, or only in a different letter case. Reads the output of html and makes no
                request. Run html first when the pages are stale.

                For example:
                $ java -jar html-saurus-build-only.jar links -d ~/works/nigsc_homepage2 --strict
                """);
        stageCommand("all", """
                Runs html, then index, then embedding, in that order.

                For example:
                $ java -jar html-saurus-build-only.jar all -d ~/works -a
                """);
    }

    /**
     * Asks every external site the built pages link to whether the page is still there. A command
     * of its own, because the time goes on other people's servers: a build must not wait for them,
     * and must not fail because one of them is down.
     */
    private void externalLinksCommand() {
        Options opts = new Options();
        opts.addOption(Option.builder("d").longOpt("dir").hasArg(true).argName("dir")
                .desc("The Docusaurus project whose built pages (static-html/) are read, or the directory to scan with -a.")
                .required(true).build());
        opts.addOption(Option.builder("a").longOpt("all-projects").hasArg(false)
                .desc("Treat -d as a directory holding many projects, and read every one.")
                .required(false).build());
        opts.addOption(Option.builder().longOpt("interval").hasArg(true).argName("seconds")
                .desc("The least time between two requests to one host (default: 3).")
                .required(false).build());
        opts.addOption(Option.builder().longOpt("strict").hasArg(false)
                .desc("Exit with status 1 when a page is gone (404, 410); other findings never fail.")
                .required(false).build());

        String description = """
                Reports external links whose page is gone, and ones that moved for good.

                Links to the site's own address (url: in the Docusaurus config) are not asked here: they
                are internal links, checked against the built pages by the links command.

                Hosts are asked side by side and one request at a time per host, with a pause between
                requests to the same host. 404 and 410 are reported as GONE. 301 and 308 are reported as
                MOVED with the new address. Refusals, rate limits, server errors, timeouts and unknown
                hosts say little about the page and are reported as UNSURE; they never fail the run.
                Reads the output of the html stage and changes nothing.

                For example:
                $ java -jar html-saurus-build-only.jar external-links -d ~/works/nigsc_homepage2 --strict
                """;

        cmds.addCommand("external-links", opts, description, (CommandLine cl) -> {
            Path dir = Path.of(cl.getOptionValue("dir")).toAbsolutePath();
            long intervalMs = (long) (Double.parseDouble(cl.getOptionValue("interval", "3")) * 1000);
            List<Path> projects;
            try {
                projects = cl.hasOption("all-projects") ? Projects.findProjects(dir) : List.of(dir);
            } catch (IOException e) {
                System.err.println("Could not read " + dir + ": " + e.getMessage());
                System.exit(1);
                return;
            }
            LinkProbe probe = new LinkProbe();
            int gone = 0;
            for (Path p : projects) {
                java.util.Map<String, List<String>> urls;
                try {
                    urls = ExternalLinks.collect(p.resolve("static-html"), ExternalLinks.ownHostOf(p));
                } catch (IOException e) {
                    System.err.println("Could not read " + p.resolve("static-html") + ": " + e.getMessage());
                    System.exit(1);
                    return;
                }
                System.out.println(p.getFileName() + ": " + urls.size() + " external address(es) to ask");
                List<ExternalLinkChecker.Result> results = ExternalLinkChecker.check(urls, probe, intervalMs);
                long unsure = results.stream().filter(r -> r.outcome().kind() == ExternalLinkChecker.Kind.UNCERTAIN).count();
                long moved = results.stream().filter(r -> r.outcome().kind() == ExternalLinkChecker.Kind.MOVED).count();
                long broken = results.stream().filter(r -> r.outcome().kind() == ExternalLinkChecker.Kind.BROKEN).count();
                System.out.println("  " + broken + " gone, " + moved + " moved, " + unsure + " unsure, "
                        + (results.size() - broken - moved - unsure) + " ok");
                for (ExternalLinkChecker.Result r : results) {
                    String label = switch (r.outcome().kind()) {
                        case BROKEN -> "GONE   ";
                        case MOVED -> "MOVED  ";
                        case UNCERTAIN -> "UNSURE ";
                        case OK -> null;
                    };
                    if (label == null) continue;
                    String what = switch (r.outcome().kind()) {
                        case BROKEN -> "HTTP " + r.outcome().status();
                        case MOVED -> "-> " + r.outcome().detail();
                        default -> r.outcome().detail();
                    };
                    int shown = Math.min(3, r.pages().size());
                    String where = String.join(", ", r.pages().subList(0, shown))
                            + (r.pages().size() > shown ? " (+" + (r.pages().size() - shown) + " more)" : "");
                    System.out.println("  " + label + r.url() + "  " + what + "  [" + where + "]");
                }
                gone += (int) broken;
            }
            if (gone > 0 && cl.hasOption("strict")) System.exit(1);
        });
    }

    /**
     * Finds the document ids the Markdown names that no document answers to. It asks a running
     * portal, which knows every id in every {@code doc_*} repository, so it is a command of its
     * own and not a stage of the build: a build must not depend on a portal being up.
     */
    private void docIdsCommand() {
        Options opts = new Options();
        opts.addOption(Option.builder("d").longOpt("dir").hasArg(true).argName("dir")
                .desc("The Docusaurus project to read, or the directory to scan with -a.")
                .required(true).build());
        opts.addOption(Option.builder("a").longOpt("all-projects").hasArg(false)
                .desc("Treat -d as a directory holding many projects, and read every one.")
                .required(false).build());
        opts.addOption(Option.builder().longOpt("portal").hasArg(true).argName("url")
                .desc("The running portal to ask (default: $PORTAL_URL, or http://localhost:28001).")
                .required(false).build());
        opts.addOption(Option.builder().longOpt("strict").hasArg(false)
                .desc("Exit with status 1 when an id has no document (default: report only).")
                .required(false).build());

        String description = """
                Reports each document id written in the Markdown that no document answers to.

                An id is written in the text, not as a link, so nothing in the built pages shows
                one that points nowhere. This asks the portal's /api/resolve about every id. An id
                added since the portal last indexed is reported until the portal is updated.
                Fenced code blocks and the front matter are not read.

                For example:
                $ java -jar html-saurus-build-only.jar doc-ids -d ~/works/doc_Base010
                """;

        cmds.addCommand("doc-ids", opts, description, (CommandLine cl) -> {
            Path dir = Path.of(cl.getOptionValue("dir")).toAbsolutePath();
            String portal = cl.getOptionValue("portal",
                    System.getenv().getOrDefault("PORTAL_URL", "http://localhost:28001"));
            List<Path> projects;
            try {
                projects = cl.hasOption("all-projects") ? Projects.findProjects(dir) : List.of(dir);
            } catch (IOException e) {
                System.err.println("Could not read " + dir + ": " + e.getMessage());
                System.exit(1);
                return;
            }
            PortalResolver resolver = new PortalResolver(portal);
            int unknownTotal = 0;
            for (Path p : projects) {
                DocIdChecker.Report report;
                try {
                    report = DocIdChecker.check(p, resolver);
                } catch (IOException | java.io.UncheckedIOException e) {
                    System.err.println("Could not ask the portal at " + portal + ": " + e.getMessage());
                    System.exit(2);
                    return;
                }
                System.out.println(p.getFileName() + ": " + report.idsChecked() + " id(s) checked, "
                        + report.unknown().size() + " without a document");
                for (DocIdChecker.Unknown u : report.unknown()) {
                    int shown = Math.min(3, u.places().size());
                    String where = String.join(", ", u.places().subList(0, shown))
                            + (u.places().size() > shown ? " (+" + (u.places().size() - shown) + " more)" : "");
                    System.out.println("  UNKNOWN_DOC_ID  " + u.id() + "  " + where);
                }
                unknownTotal += report.unknown().size();
            }
            if (unknownTotal > 0 && cl.hasOption("strict")) System.exit(1);
        });
    }

    /** The five commands differ only in which stages they run; the options are the same. */
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

        // The two stages that write pages are the two that check them.
        if (name.equals("html") || name.equals("all")) LinkPolicy.addOptions(opts);

        if (name.equals("links")) {
            opts.addOption(Option.builder()
                    .longOpt("strict")
                    .hasArg(false)
                    .desc("Exit with status 1 when any broken link is found (default: report only).")
                    .required(false)
                    .build());
        }

        cmds.addCommand(name, opts, description, (CommandLine cl) -> {
            if (cl.hasOption("no-diagrams")) BuildStages.skipDiagrams(true);
            try {
                BuildStages.linkPolicy(LinkPolicy.from(cl));
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                System.exit(1);
                return;
            }
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

            if (name.equals("links")) {
                int broken = 0;
                for (Path p : projects) {
                    int n = BuildStages.reportBrokenLinks(p.resolve("static-html"), p, 0);
                    if (n > 0) broken += n;
                }
                if (broken > 0 && cl.hasOption("strict")) System.exit(1);
                return;
            }

            boolean all = name.equals("all");
            if (all || name.equals("html")) {
                List<Path> failed = new java.util.ArrayList<>();
                for (Path p : projects) {
                    if (!BuildStages.build(p.resolve("docs"), p.resolve("static-html"), production, threads)) {
                        failed.add(p);
                    }
                }
                // Every project is built first, so one run lists every broken link. The stages
                // that read the pages do not run on pages that failed.
                if (!failed.isEmpty()) {
                    System.err.println("Build failed for " + failed.size() + " of " + projects.size()
                            + " project(s); the later stages did not run:");
                    failed.forEach(f -> System.err.println("  " + f));
                    System.exit(1);
                    return;
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
