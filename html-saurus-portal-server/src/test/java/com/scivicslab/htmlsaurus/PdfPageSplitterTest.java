package com.scivicslab.htmlsaurus;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link PdfPageSplitter}: pure PDFBox logic, no external OCR service. */
class PdfPageSplitterTest {

    private static byte[] multiPagePdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void pageCount_returnsActualPageCount() throws IOException {
        assertEquals(5, PdfPageSplitter.pageCount(multiPagePdf(5)));
    }

    @Test
    void singlePage_extractsExactlyOnePage() throws IOException {
        byte[] onePagePdf = PdfPageSplitter.singlePage(multiPagePdf(5), 2);
        assertEquals(1, PdfPageSplitter.pageCount(onePagePdf));
    }

    @Test
    void singlePage_firstAndLastIndexBothWork() throws IOException {
        byte[] pdf = multiPagePdf(3);
        assertEquals(1, PdfPageSplitter.pageCount(PdfPageSplitter.singlePage(pdf, 0)));
        assertEquals(1, PdfPageSplitter.pageCount(PdfPageSplitter.singlePage(pdf, 2)));
    }

    /** Marker decides from Creator/Producer whether to trust the text layer, so the page sent to
     *  it must name the same program the whole document does. */
    @Test
    void singlePage_keepsCreatorAndProducer() throws IOException {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            doc.getDocumentInformation().setCreator("Readiris Build 6377");
            doc.getDocumentInformation().setProducer("Readiris Build 6377");
            doc.save(out);
            pdf = out.toByteArray();
        }
        try (PDDocument one = Loader.loadPDF(PdfPageSplitter.singlePage(pdf, 1))) {
            assertEquals("Readiris Build 6377", one.getDocumentInformation().getCreator());
            assertEquals("Readiris Build 6377", one.getDocumentInformation().getProducer());
        }
    }
}
