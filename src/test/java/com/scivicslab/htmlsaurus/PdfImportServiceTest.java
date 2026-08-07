package com.scivicslab.htmlsaurus;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link PdfImportService}: {@link PdfImportService#ocrOnePage} against a stub
 *  {@link OcrClient}, and {@link PdfImportService#assembleDocument} as pure conversion logic. */
class PdfImportServiceTest {

    /** Returns the page index it was asked to OCR, as its one paragraph — makes assertions on
     * page ordering trivial without depending on any real OCR server. */
    static class StubOcrClient implements OcrClient {
        int calls = 0;
        @Override public String backendId() { return "stub"; }
        @Override public Result ocrPage(byte[] onePagePdfBytes) {
            calls++;
            return new Result(List.of("page-" + calls), Map.of());
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
            pageBodies.set(page, PdfImportService.ocrOnePage(pdf, stub, page).markdown());
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
            @Override public Result ocrPage(byte[] onePagePdfBytes) {
                return new Result(List.of("first", "second"), Map.of());
            }
        };
        String body = PdfImportService.ocrOnePage(multiPagePdf(1), twoParagraphs, 0).markdown();
        assertEquals("first\n\nsecond", body);
    }

    @Test
    void ocrOnePage_noImages_returnsEmptyImageMap() throws Exception {
        PdfImportService.PageResult result = PdfImportService.ocrOnePage(multiPagePdf(1), new StubOcrClient(), 0);
        assertTrue(result.images().isEmpty());
    }

    /** Marker (and any backend that extracts images) numbers each image relative to its own call,
     *  so two different real pages can return the identical filename (e.g. both "_page_0_Picture_0.jpeg")
     *  — ocrOnePage must rename them to be unique across the whole document. */
    @Test
    void ocrOnePage_rewritesImageFilenameWithPageNumberPrefix() throws Exception {
        byte[] fakeImage = {1, 2, 3};
        OcrClient withImage = new OcrClient() {
            @Override public String backendId() { return "stub"; }
            @Override public Result ocrPage(byte[] onePagePdfBytes) {
                return new Result(List.of("caption text", "![](_page_0_Picture_0.jpeg)"),
                        Map.of("_page_0_Picture_0.jpeg", fakeImage));
            }
        };
        PdfImportService.PageResult result = PdfImportService.ocrOnePage(multiPagePdf(10), withImage, 6);
        assertEquals(1, result.images().size());
        String renamedKey = result.images().keySet().iterator().next();
        assertEquals("p7_" + "_page_0_Picture_0.jpeg", renamedKey, "1-based page number prefix");
        assertArrayEquals(fakeImage, result.images().get(renamedKey));
        assertTrue(result.markdown().contains("![](" + renamedKey + ")"),
                "markdown reference must be rewritten to the renamed filename");
        assertFalse(result.markdown().contains("(_page_0_Picture_0.jpeg)"),
                "the original, collidable filename must not remain in the markdown");
    }

    /** Two different real pages that both produce a "_page_0_Picture_0.jpeg" from the backend's
     *  point of view must end up with two distinct, non-colliding filenames once renamed. */
    @Test
    void ocrOnePage_twoPagesWithSameBackendFilename_doNotCollideAfterRename() throws Exception {
        byte[] imageA = {1};
        byte[] imageB = {2};
        OcrClient sameNameEveryCall = new OcrClient() {
            @Override public String backendId() { return "stub"; }
            @Override public Result ocrPage(byte[] onePagePdfBytes) {
                // Both pages' single-page PDF is byte-identical in this stub (no real page content),
                // so distinguish by returning imageA vs imageB is not possible here; the point under
                // test is purely that the *key* collision is resolved regardless of the bytes.
                return new Result(List.of("x"), Map.of("_page_0_Picture_0.jpeg", imageA));
            }
        };
        var pdf = multiPagePdf(2);
        PdfImportService.PageResult page0 = PdfImportService.ocrOnePage(pdf, sameNameEveryCall, 0);
        PdfImportService.PageResult page1 = PdfImportService.ocrOnePage(pdf, sameNameEveryCall, 1);
        String key0 = page0.images().keySet().iterator().next();
        String key1 = page1.images().keySet().iterator().next();
        assertNotEquals(key0, key1, "renamed filenames must differ across pages");
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
