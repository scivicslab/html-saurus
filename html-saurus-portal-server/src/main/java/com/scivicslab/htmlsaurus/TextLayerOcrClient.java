package com.scivicslab.htmlsaurus;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a page from the text a PDF carries, not from a picture of it, so no OCR engine, no GPU and
 * no network are involved ({@link OcrClient} stands in for "something that turns one page into
 * Markdown paragraphs").
 *
 * <p>For a PDF made by a typesetting system the text is exact, and each glyph's font says what kind
 * of text it is ({@link TextLayerMarkdown}). A page that is only an image (a scan) has no text to
 * read: that is reported as a failure that names the remedy, instead of writing an empty file that
 * looks like a successful import.</p>
 */
final class TextLayerOcrClient implements OcrClient {

    static final String BACKEND_ID = "text-layer";

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public Result ocrPage(byte[] onePagePdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(onePagePdfBytes)) {
            PDPage page = doc.getPage(0);
            List<TextLayerMarkdown.Glyph> glyphs = glyphs(doc);
            if (glyphs.isEmpty()) {
                if (hasImage(page)) {
                    throw new IOException("this page has no text layer, only an image: use an OCR engine for it");
                }
                return new Result(List.of(), Map.of());   // a blank page
            }
            return new Result(TextLayerMarkdown.render(glyphs), Map.of());
        }
    }

    /** Every glyph of the document's first page, with its font and position. */
    static List<TextLayerMarkdown.Glyph> glyphs(PDDocument doc) throws IOException {
        List<TextLayerMarkdown.Glyph> out = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition t) {
                String font = t.getFont() == null || t.getFont().getName() == null ? ""
                    : t.getFont().getName().replaceFirst("^[A-Z]{6}\\+", "");
                float x = t.getXDirAdj();
                out.add(new TextLayerMarkdown.Glyph(t.getUnicode(), font, t.getFontSizeInPt(),
                    x, x + t.getWidthDirAdj(), t.getYDirAdj()));
            }
        };
        stripper.setStartPage(1);
        stripper.setEndPage(1);
        stripper.getText(doc);
        return out;
    }

    private static boolean hasImage(PDPage page) throws IOException {
        PDResources resources = page.getResources();
        if (resources == null) return false;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject = resources.getXObject(name);
            if (xobject instanceof PDImageXObject) return true;
        }
        return false;
    }
}
