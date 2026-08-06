package com.scivicslab.htmlsaurus;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link WordImportService}: pure POI conversion logic, no HTTP/filesystem. */
class WordImportServiceTest {

    private static byte[] docxWithHeadingParagraphAndImage() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph heading = doc.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Chapter One");

            XWPFParagraph body = doc.createParagraph();
            body.createRun().setText("This is the body text.");

            XWPFParagraph withImage = doc.createParagraph();
            XWPFRun run = withImage.createRun();
            run.setText("Caption text");
            byte[] onePixelPng = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            run.addPicture(new ByteArrayInputStream(onePixelPng), Document.PICTURE_TYPE_PNG,
                    "pixel.png", Units.toEMU(1), Units.toEMU(1));

            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void convert_headingStyleBecomesMarkdownHeading() throws Exception {
        WordImportService.Result result = WordImportService.convert(
                docxWithHeadingParagraphAndImage(), "test.docx", "Test Doc");
        assertTrue(result.markdown().contains("# Chapter One"),
                "Heading1 style must become a single '#' Markdown heading");
    }

    @Test
    void convert_plainParagraphHasNoHeadingPrefix() throws Exception {
        WordImportService.Result result = WordImportService.convert(
                docxWithHeadingParagraphAndImage(), "test.docx", "Test Doc");
        assertTrue(result.markdown().contains("This is the body text."));
        assertFalse(result.markdown().contains("# This is the body text."));
    }

    @Test
    void convert_embeddedImageIsExtractedAndReferenced() throws Exception {
        WordImportService.Result result = WordImportService.convert(
                docxWithHeadingParagraphAndImage(), "test.docx", "Test Doc");
        assertEquals(1, result.images().size(), "exactly one embedded picture");
        String imgName = result.images().keySet().iterator().next();
        assertTrue(imgName.startsWith("img1"), "image filenames are numbered from 1");
        assertTrue(result.markdown().contains("![](images/" + imgName + ")"),
                "Markdown must reference the extracted image by its images/ path");
    }

    @Test
    void convert_frontmatterHasTitleAndSourceDocx() throws Exception {
        WordImportService.Result result = WordImportService.convert(
                docxWithHeadingParagraphAndImage(), "report.docx", "My Report");
        assertTrue(result.markdown().startsWith("---\n"));
        assertTrue(result.markdown().contains("title: \"My Report\""));
        assertTrue(result.markdown().contains("source_docx: \"report.docx\""));
    }
}
