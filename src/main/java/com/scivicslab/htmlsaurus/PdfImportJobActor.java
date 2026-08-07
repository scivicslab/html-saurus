package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The POJO body of one PDF-import background job, run as a named child actor in
 * {@code PortalServer}'s shared {@code ActorSystem} instead of a raw {@code Thread} or
 * {@code ExecutorService} task.
 *
 * <p>This follows {@code Quarkus_260807_oo01}'s lesson from {@code quarkus-chat-ui3}'s
 * {@code TuringWorkflowRunner} bug: that code ran each workflow on a bare
 * {@code Thread.ofVirtual()} and mutated {@code System.out} (a JVM-wide shared mutable field)
 * outside any actor mailbox, so two concurrent runs raced and corrupted each other's output. A
 * PDF import job has the same shape of risk — its own progress fields ({@link #currentPage},
 * {@link #lastFile}, {@link #totalImages}, {@link #state}) are mutated repeatedly while a
 * separate HTTP thread polls them — so every read and write of this actor's state must go
 * through its own actor mailbox ({@code tell}/{@code ask}), never touched directly by an HTTP
 * handler thread.
 *
 * <p>Starts itself via {@link #start()} (sent as {@code ref.tell(PdfImportJobActor::start)}) and
 * self-chains: after OCR-ing one page it {@code tell}s itself again to process the next page, so
 * the whole page-by-page loop runs on this actor's own thread — independent of any browser
 * connection staying open, and independent of the HTTP request that started it having already
 * returned. Progress is read via {@link #snapshot()}, called through {@code ask} so it is
 * serialized against the same mailbox as the writes.
 */
class PdfImportJobActor {

    /** A read-only snapshot of job progress, safe to hand out from {@link #snapshot()}. */
    record Status(int currentPage, int totalPages, String lastFile, int totalImages,
                   String state, String error) {}

    private final byte[] pdfBytes;
    private final Path destDir;
    private final String stem;
    private final OcrClient ocr;
    private final int pagesPerFile;
    private final int totalPages;
    private final String title;
    /** {@code "<project>/docs/<destPath>"} — how completed files are reported back, independent
     *  of this actor knowing about {@code PortalServer.Project} at all. */
    private final String fileDisplayPrefix;
    /** Invoked once, after the last batch is written (rebuilds the project's HTML/index). Not
     *  invoked if the job errors or is stopped early. */
    private final Runnable onDone;

    private final String[] pageMarkdown;
    private final List<Map<String, byte[]>> pageImages;

    private ActorRef<PdfImportJobActor> self;
    private int currentPage = 0;
    private int batchStart = 0;
    private int totalImages = 0;
    private String lastFile = "";
    private String state = "running"; // running | done | error | stopped
    private String error;
    private volatile boolean stopRequested = false;

    PdfImportJobActor(byte[] pdfBytes, Path destDir, String stem, OcrClient ocr, int pagesPerFile,
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

    /** Must be called once, right after the actor is registered, before {@link #start()}. */
    void setSelf(ActorRef<PdfImportJobActor> self) {
        this.self = self;
    }

    /** Kicks off page-by-page processing. Call via {@code ref.tell(PdfImportJobActor::start)}. */
    void start() {
        processNextPage();
    }

    /** Requests the job stop before its next page starts. Already-written batches are kept. */
    void requestStop() {
        this.stopRequested = true;
    }

    /** Read-only progress snapshot for the polling {@code GET /api/import/pdf/status}. */
    Status snapshot() {
        return new Status(currentPage, totalPages, lastFile, totalImages, state, error);
    }

    private void processNextPage() {
        if (!"running".equals(state)) return; // already finished/errored/stopped — ignore stray ticks
        if (stopRequested) {
            state = "stopped";
            return;
        }
        try {
            PdfImportService.PageResult result = PdfImportService.ocrOnePage(pdfBytes, ocr, currentPage);
            pageMarkdown[currentPage] = result.markdown();
            pageImages.set(currentPage, result.images());
            currentPage++;

            boolean lastPageOfBatch = (currentPage - batchStart) >= pagesPerFile || currentPage == totalPages;
            if (lastPageOfBatch) {
                writeBatch(batchStart, currentPage);
                batchStart = currentPage;
            }

            if (currentPage >= totalPages) {
                state = "done";
                onDone.run();
            } else {
                self.tell(PdfImportJobActor::processNextPage);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state = "error";
            error = "OCR call interrupted";
        } catch (Exception e) {
            state = "error";
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
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
