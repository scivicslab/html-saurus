package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.util.List;

/**
 * Calls an external OCR HTTP server to turn one scanned PDF page into paragraphs of text.
 *
 * <p>Backends differ in their strengths (mirrors the same split used by quarkus-exdb2's own
 * {@code OcrClient}):
 * <ul>
 *   <li>{@link YomiTokuOcrClient} — Japanese documents, correct column order on multi-column
 *       layouts, runs locally/on a GPU host with no math support</li>
 *   <li>{@link MarkerOcrClient} — math-heavy/LaTeX-preserving, GPU-hosted; on two-column
 *       Japanese/English books it merges the columns into one line, so prefer YomiToku for those</li>
 * </ul>
 *
 * <p>Always called with a single-page PDF (see {@link PdfPageSplitter}), never a whole document —
 * this keeps each HTTP call small and lets an import be resumed/retried page by page.
 */
interface OcrClient {

    /** Stable identifier used in UI dropdowns and API parameters (e.g. {@code "yomitoku"}). */
    String backendId();

    /**
     * OCRs one page, already extracted as a standalone single-page PDF.
     *
     * @param onePagePdfBytes the bytes of a PDF containing exactly one page
     * @return the page's text, split into paragraphs, in reading order
     */
    List<String> ocrPage(byte[] onePagePdfBytes) throws IOException, InterruptedException;
}
