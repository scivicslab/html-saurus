package com.scivicslab.htmlsaurus;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link PdfImportService}: pure conversion logic, stub {@link OcrClient}. */
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

    @Test
    void importPageRange_callsOcrOncePerPageInRange() throws Exception {
        StubOcrClient stub = new StubOcrClient();
        PdfImportService.importPageRange(multiPagePdf(10), stub, 2, 5, "book.pdf", "Book");
        assertEquals(3, stub.calls, "pages [2,5) is 3 pages");
    }

    @Test
    void importPageRange_frontmatterHasTitleSourceAndPages() throws Exception {
        String md = PdfImportService.importPageRange(multiPagePdf(20), new StubOcrClient(),
                10, 20, "book.pdf", "My Book");
        assertTrue(md.startsWith("---\n"), "must start with YAML frontmatter");
        assertTrue(md.contains("title: \"My Book\""));
        assertTrue(md.contains("source_pdf: \"book.pdf\""));
        assertTrue(md.contains("pages: \"11-20\""), "1-based, inclusive display range");
        assertTrue(md.contains("ocr_backend: \"stub\""));
    }

    @Test
    void importPageRange_bodyContainsParagraphsInPageOrder() throws Exception {
        String md = PdfImportService.importPageRange(multiPagePdf(3), new StubOcrClient(),
                0, 3, "book.pdf", "Book");
        int i1 = md.indexOf("page-1");
        int i2 = md.indexOf("page-2");
        int i3 = md.indexOf("page-3");
        assertTrue(i1 >= 0 && i1 < i2 && i2 < i3, "paragraphs must appear in page order");
    }

    @Test
    void batchFilename_padsPageNumbersAndUsesOneBasedDisplay() {
        assertEquals("book_p001-010.md", PdfImportService.batchFilename("book", 0, 10));
        assertEquals("book_p011-020.md", PdfImportService.batchFilename("book", 10, 20));
    }
}
