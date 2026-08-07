package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Calls an external OCR HTTP server to turn one scanned PDF page into paragraphs of text (and,
 * for backends that support it, the figures/pictures on that page).
 *
 * <p>Backends differ in their strengths (mirrors the same split used by quarkus-exdb2's own
 * {@code OcrClient}):
 * <ul>
 *   <li>{@link YomiTokuOcrClient} — Japanese documents, correct column order on multi-column
 *       layouts, runs locally/on a GPU host with no math support, no image extraction</li>
 *   <li>{@link MarkerOcrClient} — math-heavy/LaTeX-preserving, GPU-hosted; on two-column
 *       Japanese/English books it merges the columns into one line, so prefer YomiToku for those;
 *       extracts embedded figures</li>
 * </ul>
 *
 * <p>Always called with a single-page PDF (see {@link PdfPageSplitter}), never a whole document —
 * this keeps each HTTP call small and lets an import be resumed/retried page by page.
 */
interface OcrClient {

    /** One page's OCR result: its text, split into paragraphs, in reading order, and any figures
     *  the backend extracted, keyed by the filename the backend itself assigned (referenced by
     *  matching {@code ![](<filename>)} tags inside the paragraphs) — empty if the backend does
     *  not extract images, or the page has none. */
    record Result(List<String> paragraphs, Map<String, byte[]> images) {}

    /** Stable identifier used in UI dropdowns and API parameters (e.g. {@code "yomitoku"}). */
    String backendId();

    /**
     * OCRs one page, already extracted as a standalone single-page PDF.
     *
     * @param onePagePdfBytes the bytes of a PDF containing exactly one page
     */
    Result ocrPage(byte[] onePagePdfBytes) throws IOException, InterruptedException;
}
