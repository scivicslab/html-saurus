package com.scivicslab.htmlsaurus;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Extracts one page of a PDF as a standalone single-page PDF, for sending to an {@link OcrClient}
 * one page at a time (mirrors quarkus-exdb2's {@code PdfPageExtractor}: OCR backends are called
 * with one page per request, never a whole document, so a call stays small and an import can be
 * resumed page by page).
 */
final class PdfPageSplitter {

    private PdfPageSplitter() {}

    /** Returns the number of pages in {@code pdfBytes}. */
    static int pageCount(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return doc.getNumberOfPages();
        }
    }

    /**
     * Extracts page {@code pageIndex} (0-based) as a new single-page PDF.
     *
     * @param pdfBytes  the full document
     * @param pageIndex 0-based page index
     * @return the bytes of a new PDF containing only that page
     */
    static byte[] singlePage(byte[] pdfBytes, int pageIndex) throws IOException {
        try (PDDocument src = Loader.loadPDF(pdfBytes);
             PDDocument one = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            one.importPage(src.getPage(pageIndex));
            one.save(out);
            return out.toByteArray();
        }
    }
}
