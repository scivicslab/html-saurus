package com.scivicslab.htmlsaurus;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The engine on real (generated) PDFs: fonts really come out of the file. */
class TextLayerOcrClientTest {

    private static byte[] pdf(PageWriter writer) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writer.write(doc, cs);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @FunctionalInterface
    private interface PageWriter {
        void write(PDDocument doc, PDPageContentStream cs) throws IOException;
    }

    private static void text(PDPageContentStream cs, Standard14Fonts.FontName font, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(new PDType1Font(font), 10);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    @Test
    void fontsInTheFileDecideCodeAndEmphasis() throws Exception {
        byte[] bytes = pdf((doc, cs) -> {
            text(cs, Standard14Fonts.FontName.TIMES_ROMAN, 72, 700, "Type the command");
            text(cs, Standard14Fonts.FontName.COURIER, 190, 700, "\\show\\kern");
            text(cs, Standard14Fonts.FontName.TIMES_BOLD, 260, 700, "now");
        });
        OcrClient.Result r = new TextLayerOcrClient().ocrPage(bytes);
        assertEquals(List.of("Type the command `\\show\\kern` **now**"), r.paragraphs());
        assertTrue(r.images().isEmpty());
    }

    @Test
    void aBlankPageGivesNoParagraphsAndIsNotAnError() throws Exception {
        OcrClient.Result r = new TextLayerOcrClient().ocrPage(pdf((doc, cs) -> { }));
        assertEquals(List.of(), r.paragraphs());
    }

    @Test
    void aPageThatIsOnlyAnImageFailsAndNamesTheRemedy() throws Exception {
        byte[] scan = pdf((doc, cs) -> {
            BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
            PDImageXObject x = LosslessFactory.createFromImage(doc, img);
            cs.drawImage(x, 50, 50, 200, 200);
        });
        IOException e = assertThrows(IOException.class, () -> new TextLayerOcrClient().ocrPage(scan));
        assertTrue(e.getMessage().contains("OCR engine"), e.getMessage());
    }

    @Test
    void theEngineIsKnownByItsId() {
        assertEquals("text-layer", new TextLayerOcrClient().backendId());
    }
}
