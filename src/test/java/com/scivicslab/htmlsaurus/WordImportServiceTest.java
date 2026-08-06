package com.scivicslab.htmlsaurus;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

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

    /**
     * A paragraph applying a style whose {@code styleId} does not literally match
     * {@code "Heading1".."Heading9"} (e.g. a Japanese-locale Word install's built-in headings have
     * IDs like {@code "a3"} with display name {@code "見出し 1"}, not the literal string
     * {@code "Heading1"}) but whose STYLE DEFINITION carries {@code w:outlineLvl} 0 — the signal
     * Word's own Navigation Pane relies on regardless of style naming. This is the exact shape of
     * the real-world bug reported against html-saurus: a heading rendered as plain text because
     * its raw styleId string did not match the old literal-string regex.
     */
    @Test
    void convert_nonLiteralStyleIdWithOutlineLvlZeroBecomesH1() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFStyles styles = doc.createStyles();
            CTStyle ctStyle = CTStyle.Factory.newInstance();
            ctStyle.setStyleId("a3"); // arbitrary non-"HeadingN" styleId, as real localized/converted docs have
            ctStyle.addNewName().setVal("見出し 1");
            ctStyle.addNewPPr().addNewOutlineLvl().setVal(BigInteger.ZERO);
            styles.addStyle(new XWPFStyle(ctStyle));

            XWPFParagraph heading = doc.createParagraph();
            heading.setStyle("a3");
            heading.createRun().setText("Localized Heading");

            XWPFParagraph body = doc.createParagraph();
            body.createRun().setText("Body text under the localized heading.");

            doc.write(out);
            WordImportService.Result result = WordImportService.convert(out.toByteArray(), "t.docx", "T");
            assertTrue(result.markdown().contains("# Localized Heading"),
                    "a style whose definition carries outlineLvl 0 must become a single '#' heading, "
                    + "even with a non-'HeadingN' styleId");
            assertFalse(result.markdown().contains("# Body text"),
                    "a plain paragraph with no outlineLvl must stay unprefixed");
        }
    }

    /** {@code w:outlineLvl} 2 (0-based) must become a 3-level Markdown heading ({@code ###}). */
    @Test
    void convert_outlineLvlTwoBecomesH3() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph heading = doc.createParagraph();
            heading.getCTP().addNewPPr().addNewOutlineLvl().setVal(BigInteger.TWO);
            heading.createRun().setText("Third Level Section");

            doc.write(out);
            WordImportService.Result result = WordImportService.convert(out.toByteArray(), "t.docx", "T");
            assertTrue(result.markdown().contains("### Third Level Section"),
                    "outlineLvl 2 (0-based) must become '###' (3-level heading)");
        }
    }
}
