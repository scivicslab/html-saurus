package com.scivicslab.htmlsaurus;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Calendar;

/**
 * Extracts text and metadata from a PDF file and produces a Markdown document
 * with YAML frontmatter.
 *
 * <p>Metadata (title, authors, year, journal) is placed in the frontmatter and
 * indexed as a {@code meta} field in Lucene — distinct from the body text so
 * that metadata can be boosted separately in search queries.
 *
 * <p>PDFBox embedded metadata is best-effort; fields left blank in the PDF info
 * dictionary are omitted from the frontmatter rather than filled with placeholders.
 */
public class PdfExtractor {

    /**
     * Extracts text and metadata from {@code pdfFile} and returns a Markdown string.
     *
     * @param pdfFile path to the PDF file
     * @return Markdown content with YAML frontmatter
     * @throws IOException if the file cannot be read or parsed
     */
    public static String extract(Path pdfFile) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
            PDDocumentInformation info = doc.getDocumentInformation();

            String rawTitle   = info.getTitle();
            String rawAuthors = info.getAuthor();
            String rawJournal = info.getSubject();
            String year       = extractYear(info);

            String title = (rawTitle != null && !rawTitle.isBlank())
                ? rawTitle.trim()
                : pdfFile.getFileName().toString().replaceAll("\\.pdf$", "");

            PDFTextStripper stripper = new PDFTextStripper();
            String body = stripper.getText(doc).trim();

            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("title: \"").append(escapeFm(title)).append("\"\n");
            if (rawAuthors != null && !rawAuthors.isBlank())
                sb.append("authors: \"").append(escapeFm(rawAuthors.trim())).append("\"\n");
            if (!year.isBlank())
                sb.append("year: \"").append(year).append("\"\n");
            if (rawJournal != null && !rawJournal.isBlank())
                sb.append("journal: \"").append(escapeFm(rawJournal.trim())).append("\"\n");
            sb.append("source_pdf: \"").append(pdfFile.getFileName().toString()).append("\"\n");
            sb.append("---\n\n");
            sb.append(body);

            return sb.toString();
        }
    }

    private static String extractYear(PDDocumentInformation info) {
        try {
            Calendar c = info.getCreationDate();
            if (c != null) return String.valueOf(c.get(Calendar.YEAR));
        } catch (Exception ignored) {}
        return "";
    }

    private static String escapeFm(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
