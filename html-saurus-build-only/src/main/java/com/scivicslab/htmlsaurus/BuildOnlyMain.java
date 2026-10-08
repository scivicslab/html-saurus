package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for build-only mode: run the stages that were asked for and stop.
 *
 * <p>Usage: {@code java -jar html-saurus-build-only.jar <path> [--html] [--index] [--embedding]
 * [--all-projects] [--threads N] [--no-diagrams] [--authoring-controls]}
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
        Path rootDir = null;
        int threads = 0;
        boolean allProjects = false;
        boolean doHtml = false, doIndex = false, doEmbedding = false;
        // Whether the built pages carry the Rebuild button and the Source / Copy list. Those are
        // for someone editing the Markdown, and portal mode is what serves them, so a build asks
        // for them rather than getting them by default.
        boolean authoringControls = false;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--threads") && i + 1 < args.length) threads = Integer.parseInt(args[++i]);
            else if (args[i].equals("--all-projects")) allProjects = true;
            else if (args[i].equals("--html")) doHtml = true;
            else if (args[i].equals("--index")) doIndex = true;
            else if (args[i].equals("--embedding") || args[i].equals("--embed")) doEmbedding = true;
            else if (args[i].equals("--authoring-controls")) authoringControls = true;
            else if (args[i].equals("--no-diagrams")) BuildStages.skipDiagrams(true);
            else if (!args[i].startsWith("--")) rootDir = Path.of(args[i]).toAbsolutePath();
        }
        if (rootDir == null) rootDir = Path.of("").toAbsolutePath();
        if (!doHtml && !doIndex && !doEmbedding) doHtml = doIndex = doEmbedding = true;

        List<Path> projects = allProjects ? Projects.findProjects(rootDir) : List.of(rootDir);
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

        boolean production = !authoringControls;
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
