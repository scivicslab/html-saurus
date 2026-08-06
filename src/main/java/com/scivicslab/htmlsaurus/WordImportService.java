package com.scivicslab.htmlsaurus;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a Microsoft Word (.docx) file into Markdown, using Apache POI. Word documents carry
 * their own text layer (unlike a scanned PDF), so no OCR is involved — this is a direct structural
 * conversion, not an image-recognition step.
 *
 * <p>Embedded pictures are written out as separate image files rather than dropped: dropping them
 * silently loses content the source document had. Each picture is placed immediately after the
 * paragraph its run belongs to (not necessarily at the exact mid-sentence position POI reports),
 * referenced with a plain Markdown {@code ![](<name>)} tag. Per {@code HtmlSaurus_260806_oo01}'s
 * file/directory naming convention, an image sits directly alongside the {@code .md} file it
 * belongs to (no {@code images/} subdirectory) — matching every hand-authored image in this
 * project's own docs (e.g. {@code POJO-actor/200_tutorial/010_introduction/Turing87.jpg}).
 */
final class WordImportService {

    private WordImportService() {}

    private static final Pattern HEADING_LEVEL = Pattern.compile("^Heading(\\d)$");

    /** The Markdown body text plus every embedded image extracted along the way. */
    record Result(String markdown, Map<String, byte[]> images) {}

    /**
     * Converts {@code docxBytes} to Markdown with YAML frontmatter.
     *
     * @param docxBytes      the raw {@code .docx} file content
     * @param sourceFilename the original filename, recorded in frontmatter
     * @param title          the document title, recorded in frontmatter
     */
    static Result convert(byte[] docxBytes, String sourceFilename, String title) throws IOException {
        Map<String, byte[]> images = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        int imageCounter = 0;

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            XWPFStyles styles = doc.getStyles();
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    body.append(headingPrefix(para, styles)).append(text.strip()).append("\n\n");
                }
                for (XWPFRun run : para.getRuns()) {
                    for (XWPFPicture pic : run.getEmbeddedPictures()) {
                        XWPFPictureData data = pic.getPictureData();
                        if (data == null) continue;
                        imageCounter++;
                        String ext = data.suggestFileExtension();
                        String imgName = "img" + imageCounter + (ext == null || ext.isBlank() ? "" : "." + ext);
                        images.put(imgName, data.getData());
                        body.append("![](").append(imgName).append(")\n\n");
                    }
                }
            }
        }

        String frontmatter = "---\n"
            + "title: " + yamlQuote(title) + "\n"
            + "source_docx: " + yamlQuote(sourceFilename) + "\n"
            + "---\n\n";
        return new Result(frontmatter + body, images);
    }

    /**
     * Returns a {@code "## "}-style Markdown heading prefix, or {@code ""} for a body paragraph.
     *
     * <p>Determines the heading level from Word's own {@code w:outlineLvl} (0 = Heading 1, ...,
     * 8 = Heading 9) rather than the paragraph's raw {@code w:styleId}. A document's built-in
     * heading styles keep this outline level regardless of the document's UI language or how the
     * style got renamed/recreated (e.g. a Japanese Word installation's built-in headings carry
     * style IDs like {@code "a3"} with the display name {@code "見出し 1"}, not the literal string
     * {@code "Heading1"} the old {@link #HEADING_LEVEL} regex required) — outline level is what
     * Word itself uses to build the Navigation Pane / table of contents, so it is the one signal
     * that survives localization, style renaming, and custom styles based on a built-in heading.
     */
    private static String headingPrefix(XWPFParagraph para, XWPFStyles styles) {
        Integer outlineLvl = directOutlineLvl(para.getCTP().getPPr());
        if (outlineLvl == null && styles != null) {
            outlineLvl = styleOutlineLvl(para.getStyleID(), styles, new HashSet<>());
        }
        if (outlineLvl != null) {
            int level = Math.min(6, outlineLvl + 1);
            return "#".repeat(level) + " ";
        }
        // Fallback for the (rare) case outline level cannot be resolved at all: the original
        // literal "Heading1".."Heading9" styleId check, in case some writer produces that
        // exact styleId without ever setting outlineLvl on the style.
        String styleId = para.getStyleID();
        if (styleId == null) return "";
        Matcher m = HEADING_LEVEL.matcher(styleId);
        if (!m.matches()) return "";
        int level = Math.min(6, Integer.parseInt(m.group(1)));
        return "#".repeat(level) + " ";
    }

    /**
     * Reads {@code w:outlineLvl} directly off a {@code w:pPr}, or {@code null} if unset.
     * {@code CTPPrBase} is the common ancestor of the paragraph's own {@code CTPPr} and a style
     * definition's {@code CTPPrGeneral} — neither is a subtype of the other, so this accepts both.
     */
    private static Integer directOutlineLvl(org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrBase pPr) {
        if (pPr == null || !pPr.isSetOutlineLvl()) return null;
        var val = pPr.getOutlineLvl().getVal();
        return val == null ? null : val.intValue();
    }

    /**
     * Resolves the outline level a style carries, walking the {@code w:basedOn} chain when the
     * style itself does not set one directly (a custom style based on a built-in heading style
     * inherits that heading's outline level). {@code seen} guards against a malformed circular
     * {@code basedOn} chain.
     */
    private static Integer styleOutlineLvl(String styleId, XWPFStyles styles, Set<String> seen) {
        if (styleId == null || !seen.add(styleId)) return null;
        XWPFStyle style = styles.getStyle(styleId);
        if (style == null) return null;
        var ctStyle = style.getCTStyle();
        if (ctStyle != null) {
            Integer direct = directOutlineLvl(ctStyle.getPPr());
            if (direct != null) return direct;
        }
        return styleOutlineLvl(style.getBasisStyleID(), styles, seen);
    }

    private static String yamlQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
