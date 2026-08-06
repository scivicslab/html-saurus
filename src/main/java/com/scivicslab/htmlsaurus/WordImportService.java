package com.scivicslab.htmlsaurus;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * referenced with a plain Markdown {@code ![](images/<name>)} tag.
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
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    body.append(headingPrefix(para)).append(text.strip()).append("\n\n");
                }
                for (XWPFRun run : para.getRuns()) {
                    for (XWPFPicture pic : run.getEmbeddedPictures()) {
                        XWPFPictureData data = pic.getPictureData();
                        if (data == null) continue;
                        imageCounter++;
                        String ext = data.suggestFileExtension();
                        String imgName = "img" + imageCounter + (ext == null || ext.isBlank() ? "" : "." + ext);
                        images.put(imgName, data.getData());
                        body.append("![](images/").append(imgName).append(")\n\n");
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

    /** Returns {@code "## "}-style Markdown heading prefix for a {@code HeadingN} style, or {@code ""}. */
    private static String headingPrefix(XWPFParagraph para) {
        String styleId = para.getStyleID();
        if (styleId == null) return "";
        Matcher m = HEADING_LEVEL.matcher(styleId);
        if (!m.matches()) return "";
        int level = Math.min(6, Integer.parseInt(m.group(1)));
        return "#".repeat(level) + " ";
    }

    private static String yamlQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
