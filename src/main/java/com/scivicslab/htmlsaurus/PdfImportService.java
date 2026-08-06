package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.util.List;

/**
 * Converts a page range of a PDF into one Markdown document via OCR, one page at a time (see
 * {@link OcrClient}, {@link PdfPageSplitter}). Pure conversion logic — no filesystem or HTTP I/O —
 * so it can be unit tested with a stub {@link OcrClient}.
 *
 * <p>Long PDFs are imported as several of these page ranges (e.g. 10 pages each), not as one
 * whole-document call: the caller (see {@code PortalServer}'s import endpoints) drives one HTTP
 * request per range so an import can show progress and be resumed after a failure, instead of
 * risking one very long request that times out on a large book.
 */
final class PdfImportService {

    private PdfImportService() {}

    /**
     * OCRs pages {@code [fromPage, toPage)} (0-based, exclusive end) and returns them as one
     * Markdown document with YAML frontmatter identifying the source file, page range, and OCR
     * backend used.
     */
    static String importPageRange(byte[] pdfBytes, OcrClient ocr, int fromPage, int toPage,
                                   String sourceFilename, String title)
            throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        for (int page = fromPage; page < toPage; page++) {
            byte[] onePage = PdfPageSplitter.singlePage(pdfBytes, page);
            List<String> paragraphs = ocr.ocrPage(onePage);
            for (String p : paragraphs) {
                if (!body.isEmpty()) body.append("\n\n");
                body.append(p);
            }
        }
        String frontmatter = "---\n"
            + "title: " + yamlQuote(title) + "\n"
            + "source_pdf: " + yamlQuote(sourceFilename) + "\n"
            + "pages: " + yamlQuote((fromPage + 1) + "-" + toPage) + "\n"
            + "ocr_backend: " + yamlQuote(ocr.backendId()) + "\n"
            + "---\n\n";
        return frontmatter + body;
    }

    /** Filename for the Markdown file covering pages {@code [fromPage, toPage)} (0-based). */
    static String batchFilename(String stem, int fromPage, int toPage) {
        return String.format("%s_p%03d-%03d.md", stem, fromPage + 1, toPage);
    }

    private static String yamlQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
