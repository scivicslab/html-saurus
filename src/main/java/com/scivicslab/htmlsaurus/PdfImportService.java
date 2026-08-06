package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.util.List;

/**
 * Converts PDF pages to Markdown via OCR, one page at a time (see {@link OcrClient},
 * {@link PdfPageSplitter}), and assembles a range of already-OCR'd pages into one document.
 * {@link #assembleDocument} is pure conversion logic — no filesystem, HTTP, or OCR I/O — so it can
 * be unit tested without a real or stub OCR server; {@link #ocrOnePage} is the one method that
 * calls out to {@link OcrClient}.
 *
 * <p>The caller (see {@code PortalServer}'s import endpoints) drives one HTTP request per page —
 * not one request per whole document, and not even one request per output file — so the browser
 * can show progress after every single page (a page can take tens of seconds against a real OCR
 * backend) instead of only after an entire multi-page batch finishes.
 */
final class PdfImportService {

    private PdfImportService() {}

    /** OCRs one 0-based {@code page} and returns its Markdown body (no frontmatter — that belongs
     *  to the assembled document, not a single page; see {@link #assembleDocument}). */
    static String ocrOnePage(byte[] pdfBytes, OcrClient ocr, int page) throws IOException, InterruptedException {
        byte[] onePage = PdfPageSplitter.singlePage(pdfBytes, page);
        List<String> paragraphs = ocr.ocrPage(onePage);
        return String.join("\n\n", paragraphs);
    }

    /**
     * Joins the already-OCR'd bodies of pages {@code [fromPage, toPage)} (0-based, exclusive end)
     * into one Markdown document with YAML frontmatter identifying the source file, page range,
     * and OCR backend used.
     *
     * @param pageBodies each page's body as returned by {@link #ocrOnePage}, indexed absolutely by
     *                   page number (i.e. {@code pageBodies.get(fromPage)} is page {@code fromPage})
     */
    static String assembleDocument(List<String> pageBodies, int fromPage, int toPage,
                                    String sourceFilename, String title, String ocrBackendId) {
        StringBuilder body = new StringBuilder();
        for (int page = fromPage; page < toPage; page++) {
            String p = pageBodies.get(page);
            if (p == null || p.isBlank()) continue;
            if (!body.isEmpty()) body.append("\n\n");
            body.append(p);
        }
        String frontmatter = "---\n"
            + "title: " + yamlQuote(title) + "\n"
            + "source_pdf: " + yamlQuote(sourceFilename) + "\n"
            + "pages: " + yamlQuote((fromPage + 1) + "-" + toPage) + "\n"
            + "ocr_backend: " + yamlQuote(ocrBackendId) + "\n"
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
