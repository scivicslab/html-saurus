package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** One page's OCR result: its Markdown body (no frontmatter — that belongs to the assembled
     *  document, not a single page; see {@link #assembleDocument}) and any images the backend
     *  extracted from it, keyed by a filename already made unique across the whole document. */
    record PageResult(String markdown, Map<String, byte[]> images) {}

    /**
     * OCRs one 0-based {@code page}. An OCR backend that extracts images (see {@link OcrClient})
     * names each one relative to that single call — every call restarts from the same names (e.g.
     * Marker always starts at {@code _page_0_...}, since each call is, from its point of view, a
     * fresh one-page document) — so two different real pages can return the identical filename.
     * This renames each of this page's images by prefixing the real 1-based page number, and
     * rewrites the matching {@code ![](<name>)} references in the returned Markdown to match.
     */
    static PageResult ocrOnePage(byte[] pdfBytes, OcrClient ocr, int page) throws IOException, InterruptedException {
        byte[] onePage = PdfPageSplitter.singlePage(pdfBytes, page);
        OcrClient.Result result = ocr.ocrPage(onePage);
        String markdown = String.join("\n\n", result.paragraphs());
        if (result.images().isEmpty()) {
            return new PageResult(markdown, Map.of());
        }
        Map<String, byte[]> renamed = new LinkedHashMap<>();
        for (var entry : result.images().entrySet()) {
            String uniqueName = "p" + (page + 1) + "_" + entry.getKey();
            renamed.put(uniqueName, entry.getValue());
            markdown = markdown.replace("(" + entry.getKey() + ")", "(" + uniqueName + ")");
        }
        return new PageResult(markdown, renamed);
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
