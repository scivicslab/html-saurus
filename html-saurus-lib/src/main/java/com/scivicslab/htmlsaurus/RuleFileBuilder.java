package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Rebuilds the Markdown that rule files generate, just before the HTML is written.
 *
 * <p>A document written in rst-on-lisp is a {@code <name>.lisp} beside a {@code <name>.md} of the same
 * base name. The rule file is the source: a converter reads it, checks it against the shared grammar and
 * writes the Markdown. Running that here means a document and its Markdown cannot drift apart: one
 * html-saurus run produces both.</p>
 *
 * <p>The work is done by {@code rst-on-lisp/bin/make-markdown}, which compares timestamps and rebuilds
 * only what is out of date. It leaves alone any {@code .md} that is newer than its rule file, so a file
 * someone edited by hand is never overwritten. html-saurus does not depend on the tool being installed:
 * when the script or a rule file is absent, this step is skipped.</p>
 */
final class RuleFileBuilder {

    private static final Logger LOG = Logger.getLogger(RuleFileBuilder.class.getName());

    /** Where the tool lives. Read per call so a test (or a different checkout) can point elsewhere. */
    private static Path tool() {
        return Path.of(System.getProperty("html-saurus.rule-file-tool",
                System.getProperty("user.home") + "/works/rst-on-lisp/bin/make-markdown"));
    }

    /** How long to wait before giving up on the tool. */
    private static final long TIMEOUT_MINUTES = 10;

    private RuleFileBuilder() {
    }

    /**
     * Rebuilds every out-of-date Markdown file under {@code docsDir}.
     *
     * <p>Does nothing, and reports nothing, when the directory holds no rule file. Failure to run the
     * tool is reported but never fails the build: stale Markdown is better than no site.</p>
     *
     * @param docsDir the docs directory about to be converted to HTML
     */
    static void rebuild(Path docsDir) {
        rebuild(docsDir, Duration.ofMinutes(TIMEOUT_MINUTES));
    }

    /** As {@link #rebuild(Path)}, giving the tool {@code timeout} to finish. */
    static void rebuild(Path docsDir, Duration timeout) {
        if (!hasRuleFiles(docsDir)) {
            return;
        }
        Path tool = tool();
        if (!Files.isExecutable(tool)) {
            LOG.warning("Rule files found under " + docsDir + " but " + tool
                    + " is not executable; leaving the .md files as they are.");
            return;
        }
        try {
            ExternalProcess.Result r = ExternalProcess.run(
                    List.of(tool.toString(), docsDir.toString()), null, timeout);
            if (r.timedOut()) {
                LOG.warning("make-markdown did not finish within " + timeout + " for " + docsDir);
                return;
            }
            r.output().lines().filter(l -> !l.isBlank()).forEach(l -> System.out.println("  rule file : " + l));
            if (r.exitCode() != 0) {
                LOG.warning("make-markdown exited with " + r.exitCode() + " for " + docsDir);
            }
        } catch (IOException e) {
            LOG.warning("Could not run " + tool + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * True when at least one rule file exists under {@code docsDir}. A {@code .lisp} counts only when a
     * {@code .md} of the same base name sits beside it: the other {@code .lisp} files in a document's
     * directory are code blocks the document shows, not sources of Markdown.
     */
    private static boolean hasRuleFiles(Path docsDir) {
        if (!Files.isDirectory(docsDir)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(docsDir)) {
            return files.anyMatch(RuleFileBuilder::isRuleFile);
        } catch (IOException e) {
            return false;
        }
    }

    /** True when {@code p} is a {@code .lisp} with a {@code .md} of the same base name beside it. */
    private static boolean isRuleFile(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".lisp")
                && Files.isRegularFile(p.resolveSibling(name.substring(0, name.length() - 5) + ".md"));
    }
}
