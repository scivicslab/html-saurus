package com.scivicslab.htmlsaurus;

import com.scivicslab.jobregistry.Job;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The work of one PDF import: OCR the pages one at a time, write a Markdown file every
 * {@code pagesPerFile} pages, and rebuild the project once at the end.
 *
 * <p>With a {@link ChapterSplitter.Planner} the files are not written as the pages come in. Every
 * page is OCR'd first, the planner then says where the chapters start, and the files are written
 * into one directory per part of the book ({@code Chap01}, {@code Chap02}, ...), with the page
 * numbers in their names restarting in each part. If the planner cannot decide, the flat layout is
 * written instead, so the OCR work is never lost.</p>
 *
 * <p>Runs as a {@code JobRegistry.Work} on an actor of its own, so it is independent of the browser
 * connection and of the HTTP request that started it — a reload, or closing the tab, does not stop
 * it. That actor's thread is the only writer of this import's progress; the polling request thread
 * only reads ({@code WhereJobControlBelongs_260901_oo01}).</p>
 *
 * <p>Stopping is cooperative: {@code JobRegistry.stop} marks the job terminal and interrupts this
 * thread, and the page loop checks {@link Job#isTerminal()} before starting each page. Pages
 * already written stay written.</p>
 */
class PdfImportJob {

    private final byte[] pdfBytes;
    private final Path destDir;
    private final String stem;
    private final OcrClient ocr;
    private final int pagesPerFile;
    private final int totalPages;
    private final String title;
    /** {@code "<project>/docs/<destPath>"} — how finished files are named back to the browser,
     *  so this class needs to know nothing about {@code PortalServer.Project}. */
    private final String fileDisplayPrefix;
    /** Runs once, after the last batch is written. Not run if the import fails or is stopped. */
    private final Runnable onDone;
    /** Decides the chapter boundaries after all pages are OCR'd; {@code null} writes the flat layout. */
    private final ChapterSplitter.Planner chapterPlanner;

    private final String[] pageMarkdown;
    private final List<Map<String, byte[]>> pageImages;

    private int totalImages = 0;
    private String lastFile = "";

    PdfImportJob(byte[] pdfBytes, Path destDir, String stem, OcrClient ocr, int pagesPerFile,
                  int totalPages, String title, String fileDisplayPrefix, Runnable onDone) {
        this(pdfBytes, destDir, stem, ocr, pagesPerFile, totalPages, title, fileDisplayPrefix, onDone, null);
    }

    PdfImportJob(byte[] pdfBytes, Path destDir, String stem, OcrClient ocr, int pagesPerFile,
                  int totalPages, String title, String fileDisplayPrefix, Runnable onDone,
                  ChapterSplitter.Planner chapterPlanner) {
        this.pdfBytes = pdfBytes;
        this.destDir = destDir;
        this.stem = stem;
        this.ocr = ocr;
        this.pagesPerFile = pagesPerFile;
        this.totalPages = totalPages;
        this.title = title;
        this.fileDisplayPrefix = fileDisplayPrefix;
        this.onDone = onDone;
        this.chapterPlanner = chapterPlanner;
        this.pageMarkdown = new String[totalPages];
        List<Map<String, byte[]>> images = new ArrayList<>(totalPages);
        for (int i = 0; i < totalPages; i++) images.add(null);
        this.pageImages = images;
    }

    /** @return how many pages this import has to get through */
    int totalPages() {
        return totalPages;
    }

    /**
     * OCRs every page in turn, writing a file each time a batch is complete (or, when splitting
     * into chapters, writing everything after the last page).
     *
     * @param job the registry's handle on this run, used to report progress and to notice a stop
     * @throws Exception to fail the job; the registry records the message
     */
    void run(Job<ImportOutcome> job) throws Exception {
        job.progress(0, totalPages);
        job.result(ImportOutcome.NOTHING_YET);
        boolean byChapter = chapterPlanner != null;
        int batchStart = 0;
        for (int page = 0; page < totalPages; page++) {
            if (job.isTerminal()) {
                return;   // stop() got here first; keep what is already written
            }
            if (byChapter) {
                job.phase("OCR page " + (page + 1) + "/" + totalPages);
            }
            PdfImportService.PageResult result = PdfImportService.ocrOnePage(pdfBytes, ocr, page);
            pageMarkdown[page] = result.markdown();
            pageImages.set(page, result.images());

            int donePages = page + 1;
            boolean lastPageOfBatch = (donePages - batchStart) >= pagesPerFile || donePages == totalPages;
            if (!byChapter && lastPageOfBatch) {
                writeBatch(destDir, stem, title, 0, batchStart, donePages);
                batchStart = donePages;
            }
            job.progress(donePages, totalPages);
            job.result(new ImportOutcome(lastFile, totalImages));
        }
        if (byChapter) {
            writeByChapters(job);
        }
        onDone.run();
    }

    /**
     * Asks the planner for the chapters of the book and writes each part into its own directory.
     * A planner that fails, or whose answer cannot make a layout, leaves the flat layout.
     */
    private void writeByChapters(Job<ImportOutcome> job) throws Exception {
        job.phase("Judging the chapter boundaries");
        List<ChapterSplitter.Placement> placements;
        String phase = "Writing files by chapter";
        try {
            List<ChapterSplitter.Section> sections = chapterPlanner.plan(pageMarkdown);
            placements = ChapterSplitter.layout(sections, totalPages, stem, title);
        } catch (IOException | IllegalArgumentException e) {
            placements = List.of(new ChapterSplitter.Placement("", stem, title, 0, totalPages));
            phase = "Chapter split failed (" + e.getMessage() + "); writing the flat layout";
        }
        job.phase(phase);
        for (ChapterSplitter.Placement part : placements) {
            if (job.isTerminal()) {
                return;
            }
            Path dir = part.dirName().isEmpty() ? destDir : destDir.resolve(part.dirName());
            for (int from = part.fromPage(); from < part.toPage(); from += pagesPerFile) {
                int to = Math.min(part.toPage(), from + pagesPerFile);
                writeBatch(dir, part.stem(), part.title(), part.fromPage(), from, to);
                job.result(new ImportOutcome(lastFile, totalImages));
            }
        }
    }

    /**
     * Writes pages {@code [fromPage, toPage)} as one document directory under {@code dir}.
     *
     * @param sectionStem  the stem of the file names
     * @param sectionTitle the title in the document's front matter
     * @param sectionStart the first page (0-based) of the part being written; the page numbers in
     *                     the file name count from it, while the front matter keeps the PDF's own
     *                     page numbers
     */
    private void writeBatch(Path dir, String sectionStem, String sectionTitle, int sectionStart,
                            int fromPage, int toPage) throws IOException {
        String markdown = PdfImportService.assembleDocument(
                java.util.Arrays.asList(pageMarkdown), fromPage, toPage, stem + ".pdf", sectionTitle, ocr.backendId());
        Map<String, byte[]> images = new LinkedHashMap<>();
        for (int page = fromPage; page < toPage; page++) images.putAll(pageImages.get(page));

        String filename = PdfImportService.batchFilename(sectionStem, fromPage - sectionStart, toPage - sectionStart);
        String batchStem = filename.substring(0, filename.length() - 3); // strip ".md"
        // One document = one directory (HtmlSaurus_260806_oo01): images sit alongside the .md,
        // no subdirectory.
        Path batchDocDir = dir.resolve(batchStem);
        Files.createDirectories(batchDocDir);
        // OCR returns formulas as LaTeX; the document stores them as om blocks (FormulaSource).
        Path batchFile = batchDocDir.resolve(filename);
        Files.writeString(batchFile, FormulaSource.toOmBlocks(markdown, batchFile), StandardCharsets.UTF_8);
        for (var e : images.entrySet()) {
            Files.write(batchDocDir.resolve(e.getKey()), e.getValue());
        }

        totalImages += images.size();
        lastFile = fileDisplayPrefix + "/" + destDir.relativize(batchFile).toString().replace('\\', '/');
    }
}
