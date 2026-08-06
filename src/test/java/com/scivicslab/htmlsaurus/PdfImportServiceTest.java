package com.scivicslab.htmlsaurus;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link PdfImportService}: {@link PdfImportService#ocrOnePage} against a stub
 *  {@link OcrClient}, and {@link PdfImportService#assembleDocument} as pure conversion logic. */
class PdfImportServiceTest {

    /** Returns the page index it was asked to OCR, as its one paragraph — makes assertions on
     * page ordering trivial without depending on any real OCR server. */
    static class StubOcrClient implements OcrClient {
        int calls = 0;
        @Override public String backendId() { return "stub"; }
        @Override public List<String> ocrPage(byte[] onePagePdfBytes) {
            calls++;
            return List.of("page-" + calls);
        }
    }

    private static byte[] multiPagePdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A pageBodies list sized for {@code totalPages}, with pages {@code [fromPage,toPage)}
     *  filled from {@code stub} (one {@link PdfImportService#ocrOnePage} call each) and the rest
     *  left {@code null} — mirrors how {@code PortalServer} only OCRs pages as the browser asks. */
    private static List<String> ocrRange(byte[] pdf, StubOcrClient stub, int totalPages,
                                          int fromPage, int toPage) throws Exception {
        List<String> pageBodies = new ArrayList<>(totalPages);
        for (int i = 0; i < totalPages; i++) pageBodies.add(null);
        for (int page = fromPage; page < toPage; page++) {
            pageBodies.set(page, PdfImportService.ocrOnePage(pdf, stub, page));
        }
        return pageBodies;
    }

    @Test
    void ocrOnePage_callsOcrExactlyOnce() throws Exception {
        StubOcrClient stub = new StubOcrClient();
        PdfImportService.ocrOnePage(multiPagePdf(10), stub, 4);
        assertEquals(1, stub.calls, "one call per ocrOnePage invocation");
    }

    @Test
    void ocrOnePage_joinsMultipleParagraphsWithBlankLine() throws Exception {
        OcrClient twoParagraphs = new OcrClient() {
            @Override public String backendId() { return "stub"; }
            @Override public List<String> ocrPage(byte[] onePagePdfBytes) { return List.of("first", "second"); }
        };
        String body = PdfImportService.ocrOnePage(multiPagePdf(1), twoParagraphs, 0);
        assertEquals("first\n\nsecond", body);
    }

    @Test
    void assembleDocument_frontmatterHasTitleSourceAndPages() throws Exception {
        byte[] pdf = multiPagePdf(20);
        StubOcrClient stub = new StubOcrClient();
        List<String> pageBodies = ocrRange(pdf, stub, 20, 10, 20);
        String md = PdfImportService.assembleDocument(pageBodies, 10, 20, "book.pdf", "My Book", stub.backendId());
        assertTrue(md.startsWith("---\n"), "must start with YAML frontmatter");
        assertTrue(md.contains("title: \"My Book\""));
        assertTrue(md.contains("source_pdf: \"book.pdf\""));
        assertTrue(md.contains("pages: \"11-20\""), "1-based, inclusive display range");
        assertTrue(md.contains("ocr_backend: \"stub\""));
    }

    @Test
    void assembleDocument_bodyContainsParagraphsInPageOrder() throws Exception {
        byte[] pdf = multiPagePdf(3);
        StubOcrClient stub = new StubOcrClient();
        List<String> pageBodies = ocrRange(pdf, stub, 3, 0, 3);
        String md = PdfImportService.assembleDocument(pageBodies, 0, 3, "book.pdf", "Book", stub.backendId());
        int i1 = md.indexOf("page-1");
        int i2 = md.indexOf("page-2");
        int i3 = md.indexOf("page-3");
        assertTrue(i1 >= 0 && i1 < i2 && i2 < i3, "paragraphs must appear in page order");
    }

    @Test
    void assembleDocument_onlyUsesPagesWithinTheGivenRange() throws Exception {
        // Pages outside [fromPage,toPage) are left null in pageBodies (not yet OCR'd by the
        // browser) — assembleDocument must not touch them.
        byte[] pdf = multiPagePdf(5);
        StubOcrClient stub = new StubOcrClient();
        List<String> pageBodies = ocrRange(pdf, stub, 5, 2, 4);
        String md = PdfImportService.assembleDocument(pageBodies, 2, 4, "book.pdf", "Book", stub.backendId());
        assertFalse(md.contains("null"), "must not stringify the un-OCR'd null entries outside the range");
    }

    @Test
    void batchFilename_padsPageNumbersAndUsesOneBasedDisplay() {
        assertEquals("book_p001-010.md", PdfImportService.batchFilename("book", 0, 10));
        assertEquals("book_p011-020.md", PdfImportService.batchFilename("book", 10, 20));
    }
}
