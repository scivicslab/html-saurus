package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.scivicslab.jobregistry.Job;

/**
 * One export: every document under one directory of a doc project, as one PDF (ExportToPdf_261119_oo01).
 *
 * <p>Runs as a batch job. It rebuilds the project's HTML so the PDF is not made from old pages, plans the parts from
 * the sidebar tree, prints the combined HTML with Chrome until the page numbers in its table of contents are the pages
 * the parts are on, and finishes the PDF with PDFBox: page numbers and bookmarks.</p>
 */
final class PdfExportJob {

    /** Rebuilds a project's HTML. */
    @FunctionalInterface
    interface HtmlBuild {
        /** @return true when the HTML was built; false when the build failed */
        boolean rebuildHtml() throws Exception;
    }

    /** How long one printing may take. */
    static final Duration PRINT_TIMEOUT = Duration.ofMinutes(10);

    /**
     * How many times the combined HTML is printed at most. The first printing shows placeholders in the table of
     * contents; the second shows the pages the first one found; a third is only needed when the numbers moved the parts.
     */
    static final int PRINT_ATTEMPTS = 3;

    /** The name every work directory of an export starts with. */
    static final String WORK_DIRECTORY_PREFIX = "html-saurus-export-";

    private static final Logger LOGGER = Logger.getLogger(PdfExportJob.class.getName());

    private final String project;
    private final Path docsDir;
    private final Path staticDir;
    private final String dirRel;
    private final Path workDir;
    private final HtmlToPdfPrinter printer;
    private final HtmlBuild rebuild;
    private final Lock projectLock;

    /**
     * @param project     the doc project's name
     * @param docsDir     the project's {@code docs/}
     * @param staticDir   the project's {@code static-html/}
     * @param dirRel      the directory to export, relative to {@code docs/}; empty for all of it
     * @param workDir     the directory the export works in and keeps its PDF in
     * @param printer     prints the combined HTML
     * @param rebuild     rebuilds the project's HTML
     * @param projectLock held from the rebuild until the combined HTML is read from the built pages, so that two exports
     *                    of one project do not rebuild under each other
     */
    PdfExportJob(String project, Path docsDir, Path staticDir, String dirRel, Path workDir, HtmlToPdfPrinter printer,
                 HtmlBuild rebuild, Lock projectLock) {
        this.project = project;
        this.docsDir = docsDir;
        this.staticDir = staticDir;
        this.dirRel = dirRel == null ? "" : dirRel;
        this.workDir = workDir;
        this.printer = printer;
        this.rebuild = rebuild;
        this.projectLock = projectLock;
    }

    /** @return the file name of the PDF: {@code <project>-<directory with / as ->.pdf}, or {@code <project>.pdf} */
    static String fileName(String project, String dirRel) {
        String d = dirRel == null ? "" : dirRel.replace('\\', '/').replaceAll("^/+|/+$", "");
        String name = d.isEmpty() ? project : project + "-" + d.replace('/', '-');
        return name.replaceAll("[^\\p{L}\\p{N}._-]", "_") + ".pdf";
    }

    /**
     * Makes the PDF. The job ends in error when the HTML cannot be built, the printed PDF does not say where a part
     * starts, or the page numbers do not settle within {@link #PRINT_ATTEMPTS} printings.
     *
     * @param job the registry's handle on this run, used to report progress and to notice a stop
     */
    void exportToPdf(Job<ExportOutcome> job) throws Exception {
        job.result(ExportOutcome.startedIn(workDir));
        int steps = 3 + PRINT_ATTEMPTS;
        Path html = workDir.resolve("export.html");
        Path printed = workDir.resolve("printed.pdf");
        try {
            job.progress(0, steps);
            PdfExportPlan plan;
            PdfExportHtml.Prepared combined;
            projectLock.lockInterruptibly();
            try {
                job.phase("Building the HTML");
                if (!rebuild.rebuildHtml()) {
                    throw new IllegalStateException("The HTML of " + project + " could not be built; the portal's log says why");
                }
                if (job.isTerminal()) return;
                job.progress(1, steps);
                job.phase("Collecting the documents");
                SiteNode root = new NavTreeBuilder(docsDir, new MarkdownConverter(), null, null, null).build();
                plan = PdfExportPlan.planOfDirectory(root, dirRel, project);
                if (plan.parts().isEmpty()) throw new IllegalStateException("No document is under docs/" + dirRel);
                String source = project + "/docs" + (dirRel.isBlank() ? "" : "/" + dirRel.replaceAll("^/+|/+$", ""));
                combined = PdfExportHtml.prepare(plan, source, staticDir);
            } finally {
                projectLock.unlock();
            }
            if (job.isTerminal()) return;

            Map<Integer, Integer> shown = Map.of();
            PdfExportFinish.PrintedPages found = null;
            for (int attempt = 1; found == null; attempt++) {
                job.progress(1 + attempt, steps);
                job.phase("Printing (" + attempt + " of at most " + PRINT_ATTEMPTS + ")");
                Files.writeString(html, combined.render(shown), StandardCharsets.UTF_8);
                printer.printHtmlToPdf(html, printed, workDir, PRINT_TIMEOUT);
                PdfExportFinish.PrintedPages now = PdfExportFinish.readPrintedPages(printed, plan);
                if (job.isTerminal()) return;
                if (now.parts().equals(shown)) {
                    found = now;
                } else if (attempt == PRINT_ATTEMPTS) {
                    throw new IllegalStateException("The page numbers in the table of contents did not settle after "
                            + PRINT_ATTEMPTS + " printings, so the PDF would send a reader to the wrong pages");
                } else {
                    shown = now.parts();
                }
            }

            job.progress(steps - 1, steps);
            job.phase("Adding page numbers and bookmarks");
            Path out = workDir.resolve(fileName(project, dirRel));
            int total = PdfExportFinish.stampPageNumbersAndBookmarks(printed, out, plan, found.parts(), found.contentsPage());
            String warning = mathWarning(html, printed);
            job.progress(steps, steps);
            job.phase(total + " pages");
            job.result(new ExportOutcome(workDir, out, total, plan.documentCount(), warning));
            LOGGER.log(Level.INFO, "Export done: project {0}, directory ''{1}'', {2} pages{3}",
                    new Object[] {project, dirRel, total, warning.isEmpty() ? "" : "; " + warning});
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Export failed: project {0}, directory ''{1}'': {2}", new Object[] {project, dirRel, e.toString()});
            throw e;
        } finally {
            deleteIntermediateFiles();
        }
    }

    /**
     * @return a warning when the combined HTML has math and the printed PDF still has it as it was written, which is
     *         what is left when the KaTeX script could not be loaded; empty otherwise
     */
    private static String mathWarning(Path html, Path printed) throws IOException {
        String text = Files.readString(html, StandardCharsets.UTF_8);
        if (PdfExportFinish.textContainsAny(printed, PdfExportHtml.mathExpressions(text))) {
            return "Math is printed as it was written ($...$): the KaTeX script could not be loaded from its CDN.";
        }
        return "";
    }

    /** Deletes what printing leaves in the work directory, whether the export ended well, in error, or by a stop. */
    private void deleteIntermediateFiles() {
        try {
            Files.deleteIfExists(workDir.resolve("export.html"));
            Files.deleteIfExists(workDir.resolve("printed.pdf"));
            Files.deleteIfExists(workDir.resolve("chrome.log"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not delete a file of {0}: {1}", new Object[] {workDir, e.getMessage()});
        }
        deleteDirectory(workDir.resolve("chrome-profile"));
    }

    /**
     * Deletes the work directories of earlier exports that the portal that made them is no longer around to delete.
     *
     * @param temporaryDirectory the directory the work directories are made in
     * @param olderThan          directories not modified for this long are deleted; a younger one may belong to an export
     *                           of another portal running on the same machine
     */
    static void deleteStaleWorkDirectories(Path temporaryDirectory, Duration olderThan) {
        Instant limit = Instant.now().minus(olderThan);
        try (Stream<Path> children = Files.list(temporaryDirectory)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                if (!child.getFileName().toString().startsWith(WORK_DIRECTORY_PREFIX) || !Files.isDirectory(child)) continue;
                if (Files.getLastModifiedTime(child).toInstant().isBefore(limit)) deleteDirectory(child);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not look for old export directories in {0}: {1}",
                    new Object[] {temporaryDirectory, e.getMessage()});
        }
    }

    /**
     * Deletes a directory and everything under it; what cannot be deleted is left.
     *
     * @param directory the directory; nothing happens when it is {@code null} or does not exist
     */
    static void deleteDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) return;
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not delete {0}: {1}", new Object[] {path, e.getMessage()});
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not walk {0}: {1}", new Object[] {directory, e.getMessage()});
        }
    }
}
