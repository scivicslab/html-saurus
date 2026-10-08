package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for build-only mode: run the stages that were asked for and stop.
 *
 * <p>Usage: {@code java -jar html-saurus-build-only.jar <path> [--html] [--index] [--embedding]
 * [--all-projects] [--authoring-controls] [--no-diagrams] [--threads N]}
 *
 * <p>No server is started and no port is opened. Naming no stage runs all three.
 *
 * <p>The stages run in the order html, index, embedding, whichever subset was named, because each
 * reads what the one before it wrote: the index reflects the built pages, and the embedding cache
 * is keyed off the index. Running them separately lets an HTML refresh go ahead without waiting
 * on the embedding server, or failing because it is down.
 */
public final class BuildOnlyMain {

    private BuildOnlyMain() {}

    public static void main(String[] args) throws IOException {
        Options o = new Options("html-saurus-build-only")
                .flag("--html").flag("--index").flag("--embedding").flag("--embed")
                .flag("--all-projects")
                // Whether the built pages carry the Rebuild button and the Source / Copy list.
                // Those are for someone editing the Markdown, and portal mode is what serves them,
                // so a build asks for them rather than getting them by default.
                .flag("--authoring-controls")
                // make-diagrams is given ten minutes to redraw the figures beside the Markdown.
                // This is the way out when the pages are wanted and the figures can wait.
                .flag("--no-diagrams")
                .value("--threads")
                // --portal-mode meant "every project under this directory" here, which is not what
                // it meant to the server it was named after.
                .renamed("--portal-mode", "--all-projects")
                .obsolete("--production", false,
                        "a build already leaves the authoring controls out; pass --authoring-controls to put them back")
                .obsolete("--serve", false, "this program never starts a server")
                .obsolete("--port", true, "this program never starts a server")
                .parse(args);

        if (o.is("--no-diagrams")) BuildStages.skipDiagrams(true);
        Path rootDir = o.path();
        int threads = o.number("--threads", 0);

        boolean doHtml = o.is("--html");
        boolean doIndex = o.is("--index");
        boolean doEmbedding = o.is("--embedding") || o.is("--embed");
        if (!doHtml && !doIndex && !doEmbedding) doHtml = doIndex = doEmbedding = true;

        List<Path> projects = o.is("--all-projects") ? Projects.findProjects(rootDir) : List.of(rootDir);
        if (projects.isEmpty()) {
            System.err.println("No Docusaurus projects found under " + rootDir);
            return;
        }

        List<String> stages = new ArrayList<>();
        if (doHtml) stages.add("html");
        if (doIndex) stages.add("index");
        if (doEmbedding) stages.add("embedding");
        System.out.println("=== html-saurus build ===");
        System.out.println("  root     : " + rootDir);
        System.out.println("  projects : " + projects.size());
        System.out.println("  stages   : " + String.join("+", stages));
        System.out.println("=========================");

        boolean production = !o.is("--authoring-controls");
        if (doHtml) {
            for (Path p : projects) {
                BuildStages.build(p.resolve("docs"), p.resolve("static-html"), production, threads);
            }
        }
        if (doIndex) {
            for (Path p : projects) {
                BuildStages.reindexAll(p, production);
            }
        }
        if (doEmbedding) {
            // Operates across all projects at once; non-fatal if the embedding server is unreachable.
            BuildStages.ensureSemanticVectors(projects);
        }
    }
}
