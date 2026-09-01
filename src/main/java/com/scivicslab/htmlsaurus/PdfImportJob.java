package com.scivicslab.htmlsaurus;

import com.scivicslab.jobregistry.Job;

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

    /** What the import has produced so far: the newest file written, and how many images in total. */
    record Result(String lastFile, int totalImages) {}

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

    private final String[] pageMarkdown;
    private final List<Map<String, byte[]>> pageImages;

    private int totalImages = 0;
    private String lastFile = "";

    PdfImportJob(byte[] pdfBytes, Path destDir, String stem, OcrClient ocr, int pagesPerFile,
                  int totalPages, String title, String fileDisplayPrefix, Runnable onDone) {
        this.pdfBytes = pdfBytes;
        this.destDir = destDir;
        this.stem = stem;
        this.ocr = ocr;
        this.pagesPerFile = pagesPerFile;
        this.totalPages = totalPages;
        this.title = title;
        this.fileDisplayPrefix = fileDisplayPrefix;
        this.onDone = onDone;
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
     * OCRs every page in turn, writing a file each time a batch is complete.
     *
     * @param job the registry's handle on this run, used to report progress and to notice a stop
     * @throws Exception to fail the job; the registry records the message
     */
    void run(Job<Result> job) throws Exception {
        job.progress(0, totalPages);
        job.result(new Result("", 0));
        int batchStart = 0;
        for (int page = 0; page < totalPages; page++) {
            if (job.isTerminal()) {
                return;   // stop() got here first; keep what is already written
            }
            PdfImportService.PageResult result = PdfImportService.ocrOnePage(pdfBytes, ocr, page);
            pageMarkdown[page] = result.markdown();
            pageImages.set(page, result.images());

            int donePages = page + 1;
            boolean lastPageOfBatch = (donePages - batchStart) >= pagesPerFile || donePages == totalPages;
            if (lastPageOfBatch) {
                writeBatch(batchStart, donePages);
                batchStart = donePages;
            }
            job.progress(donePages, totalPages);
            job.result(new Result(lastFile, totalImages));
        }
        onDone.run();
    }

    private void writeBatch(int fromPage, int toPage) throws java.io.IOException {
        String markdown = PdfImportService.assembleDocument(
                java.util.Arrays.asList(pageMarkdown), fromPage, toPage, stem + ".pdf", title, ocr.backendId());
        Map<String, byte[]> images = new LinkedHashMap<>();
        for (int page = fromPage; page < toPage; page++) images.putAll(pageImages.get(page));

        String filename = PdfImportService.batchFilename(stem, fromPage, toPage);
        String batchStem = filename.substring(0, filename.length() - 3); // strip ".md"
        // One document = one directory (HtmlSaurus_260806_oo01): images sit alongside the .md,
        // no subdirectory.
        Path batchDocDir = destDir.resolve(batchStem);
        Files.createDirectories(batchDocDir);
        Files.writeString(batchDocDir.resolve(filename), markdown, StandardCharsets.UTF_8);
        for (var e : images.entrySet()) {
            Files.write(batchDocDir.resolve(e.getKey()), e.getValue());
        }

        totalImages += images.size();
        lastFile = fileDisplayPrefix + "/" + batchStem + "/" + filename;
    }
}
