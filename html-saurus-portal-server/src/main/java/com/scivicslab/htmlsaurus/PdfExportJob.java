package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.scivicslab.jobregistry.Job;

/**
 * One export: every document under one directory of a doc project, as one PDF (ExportToPdf_261119_oo01).
 *
 * <p>Runs as a batch job. It rebuilds the project's HTML so the PDF is not made from old pages, plans the parts from
 * the sidebar tree, prints the combined HTML with headless Chrome twice (the second time with the page numbers the
 * first printing put each part on), and finishes the PDF with PDFBox: page numbers and bookmarks.</p>
 */
final class PdfExportJob {

    /** Rebuilds a project's HTML; throws when the build fails. */
    @FunctionalInterface
    interface HtmlBuild {
        void rebuildHtml() throws Exception;
    }

    /** How long one printing may take. */
    static final Duration PRINT_TIMEOUT = Duration.ofMinutes(10);

    private static final Logger LOGGER = Logger.getLogger(PdfExportJob.class.getName());

    private final String project;
    private final Path docsDir;
    private final Path staticDir;
    private final String dirRel;
    private final Path workDir;
    private final ChromePrinter printer;
    private final HtmlBuild rebuild;

    PdfExportJob(String project, Path docsDir, Path staticDir, String dirRel, Path workDir, ChromePrinter printer,
                 HtmlBuild rebuild) {
        this.project = project;
        this.docsDir = docsDir;
        this.staticDir = staticDir;
        this.dirRel = dirRel == null ? "" : dirRel;
        this.workDir = workDir;
        this.printer = printer;
        this.rebuild = rebuild;
    }

    /** @return the file name of the PDF: {@code <project>-<directory with / as ->.pdf}, or {@code <project>.pdf} */
    static String fileName(String project, String dirRel) {
        String d = dirRel == null ? "" : dirRel.replace('\\', '/').replaceAll("^/+|/+$", "");
        String name = d.isEmpty() ? project : project + "-" + d.replace('/', '-');
        return name.replaceAll("[^\\p{L}\\p{N}._-]", "_") + ".pdf";
    }

    void exportToPdf(Job<ExportOutcome> job) throws Exception {
        job.result(ExportOutcome.startedIn(workDir));
        job.progress(0, 5);
        job.phase("Building the HTML");
        rebuild.rebuildHtml();
        if (job.isTerminal()) return;

        job.progress(1, 5);
        job.phase("Collecting the documents");
        SiteNode root = new NavTreeBuilder(docsDir, new MarkdownConverter(), null, null, null).build();
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(root, dirRel, project);
        if (plan.parts().isEmpty()) throw new IllegalStateException("No document is under docs/" + dirRel);
        String source = project + "/docs" + (dirRel.isBlank() ? "" : "/" + dirRel.replaceAll("^/+|/+$", ""));
        Path html = workDir.resolve("export.html");
        Files.writeString(html, PdfExportHtml.renderCombinedHtml(plan, source, staticDir, Map.of()), StandardCharsets.UTF_8);
        if (job.isTerminal()) return;

        job.progress(2, 5);
        job.phase("Printing (1 of 2)");
        Path first = workDir.resolve("first.pdf");
        printer.printHtmlToPdf(html, first, workDir, PRINT_TIMEOUT);
        Map<Integer, Integer> pages = PdfExportFinish.readPartPages(first, plan);
        if (job.isTerminal()) return;

        job.progress(3, 5);
        job.phase("Printing (2 of 2), with the page numbers");
        Files.writeString(html, PdfExportHtml.renderCombinedHtml(plan, source, staticDir, pages), StandardCharsets.UTF_8);
        Path second = workDir.resolve("second.pdf");
        printer.printHtmlToPdf(html, second, workDir, PRINT_TIMEOUT);
        // The table of contents is the same length both times, so the parts should not move; read them again anyway.
        Map<Integer, Integer> finalPages = PdfExportFinish.readPartPages(second, plan);
        if (job.isTerminal()) return;

        job.progress(4, 5);
        job.phase("Adding page numbers and bookmarks");
        Path out = workDir.resolve(fileName(project, dirRel));
        int total = PdfExportFinish.stampPageNumbersAndBookmarks(second, out, plan, finalPages, 2);
        Files.deleteIfExists(first);
        Files.deleteIfExists(second);
        Files.deleteIfExists(html);
        deleteDirectory(workDir.resolve("chrome-profile"));
        job.progress(5, 5);
        job.phase(total + " pages");
        job.result(new ExportOutcome(workDir, out, total, plan.documentCount()));
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
