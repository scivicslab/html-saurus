package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Rebuilds the USD diagrams that live next to the Markdown files, just before the HTML is written.
 *
 * <p>A figure is a {@code <name>.jsh} file in the same directory as the document that shows it. It
 * writes {@code <name>.usda}, and a renderer draws {@code <name>.png} from that; the Markdown embeds
 * the PNG. Keeping that regeneration here means a document and its figures cannot drift apart: one
 * html-saurus run produces both.</p>
 *
 * <p>The work is done by {@code openusd-javacpp/bin/make-diagrams}, which compares timestamps and
 * rebuilds only what is out of date. With nothing to do it returns in about 0.2 s, so calling it on
 * every build costs nothing measurable. html-saurus does not depend on that tool being installed:
 * when the script or a figure is absent, this step is skipped.</p>
 */
final class DiagramBuilder {

    private static final Logger LOG = Logger.getLogger(DiagramBuilder.class.getName());

    /** Where the tool lives. Read per call so a test (or a different checkout) can point elsewhere. */
    private static Path tool() {
        return Path.of(System.getProperty("html-saurus.diagram-tool",
                System.getProperty("user.home") + "/works/openusd-javacpp/bin/make-diagrams"));
    }

    /** How long to wait before giving up on the tool. */
    private static final long TIMEOUT_MINUTES = 10;

    private DiagramBuilder() {
    }

    /**
     * Rebuilds every out-of-date figure under {@code docsDir}.
     *
     * <p>Does nothing, and reports nothing, when the directory holds no {@code .jsh} file. Failure to
     * run the tool is reported but never fails the build: a stale diagram is better than no site.</p>
     *
     * @param docsDir the docs directory about to be converted to HTML
     */
    static void rebuild(Path docsDir) {
        if (!hasFigures(docsDir)) {
            return;
        }
        Path tool = tool();
        if (!Files.isExecutable(tool)) {
            LOG.warning("Diagrams found under " + docsDir + " but " + tool
                    + " is not executable; leaving the .png files as they are.");
            return;
        }
        try {
            Process p = new ProcessBuilder(List.of(tool.toString(), docsDir.toString()))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                LOG.warning("make-diagrams did not finish within " + TIMEOUT_MINUTES + " minutes for " + docsDir);
                return;
            }
            output.lines().filter(l -> !l.isBlank()).forEach(l -> System.out.println("  diagram   : " + l));
            if (p.exitValue() != 0) {
                LOG.warning("make-diagrams exited with " + p.exitValue() + " for " + docsDir);
            }
        } catch (IOException e) {
            LOG.warning("Could not run " + tool + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True when at least one {@code .jsh} exists under {@code docsDir}. */
    private static boolean hasFigures(Path docsDir) {
        if (!Files.isDirectory(docsDir)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(docsDir)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".jsh"));
        } catch (IOException e) {
            return false;
        }
    }
}
