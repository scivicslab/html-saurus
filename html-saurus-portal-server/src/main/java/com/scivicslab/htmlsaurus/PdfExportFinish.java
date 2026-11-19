package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * What PDFBox does to a printed export: reads the page each part starts on, then numbers the pages and adds the
 * bookmarks (ExportToPdf_261119_oo01).
 */
final class PdfExportFinish {

    private PdfExportFinish() {}

    /**
     * Where the parts of a printed export start, read from the table of contents Chrome printed: each line links to
     * the anchor of its part, and the PDF keeps that anchor as a named destination on the page the part is on.
     *
     * @param parts        the page (from 1) each part starts on, by part number
     * @param contentsPage the page (from 1) the table of contents starts on
     */
    record PrintedPages(Map<Integer, Integer> parts, int contentsPage) {}

    /**
     * Reads where every part starts from the links of the table of contents.
     *
     * @param pdf  the printed export
     * @param plan what the PDF holds
     * @return the pages of every part of {@code plan}
     * @throws IOException           when the PDF cannot be read
     * @throws IllegalStateException when a part has no link, or a link points nowhere, so its page is not known
     */
    static PrintedPages readPrintedPages(Path pdf, PdfExportPlan plan) throws IOException {
        Map<Integer, Integer> pages = new HashMap<>();
        int contentsPage = 0;
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                for (PDAnnotation annotation : doc.getPage(i).getAnnotations()) {
                    if (!(annotation instanceof PDAnnotationLink link)) continue;
                    PDDestination destination = link.getDestination();
                    if (destination == null && link.getAction() instanceof PDActionGoTo goTo) destination = goTo.getDestination();
                    if (!(destination instanceof PDNamedDestination named)) continue;
                    Integer number = PdfExportPlan.partNumberOfAnchor(named.getNamedDestination());
                    PDPageDestination target = number == null ? null : catalog.findNamedDestinationPage(named);
                    if (target == null) continue;
                    int targetPage = target.getPage() == null ? target.getPageNumber() + 1 : doc.getPages().indexOf(target.getPage()) + 1;
                    if (targetPage < 1) continue;
                    pages.putIfAbsent(number, targetPage);
                    if (contentsPage == 0) contentsPage = i + 1;
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (PdfExportPlan.Part part : plan.parts()) {
            if (!pages.containsKey(part.number())) missing.add(part.title());
        }
        if (!missing.isEmpty() || contentsPage == 0) {
            throw new IllegalStateException("The printed PDF does not say which page these parts start on: "
                    + String.join(", ", missing.size() > 5 ? missing.subList(0, 5) : missing) + (missing.size() > 5 ? ", ..." : ""));
        }
        return new PrintedPages(pages, contentsPage);
    }

    /** @return true when the text of the PDF, with its white space left out, holds one of the texts, with theirs left out */
    static boolean textContainsAny(Path pdf, List<String> texts) throws IOException {
        if (texts.isEmpty()) return false;
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            String all = new PDFTextStripper().getText(doc).replaceAll("\\s+", "");
            for (String text : texts) {
                if (all.contains(text.replaceAll("\\s+", ""))) return true;
            }
            return false;
        }
    }

    /**
     * Writes {@code n / N} at the foot of every page and a bookmark for the contents and every part, nested as the
     * parts are, and saves the result to {@code out}.
     *
     * @return the page count
     */
    static int stampPageNumbersAndBookmarks(Path pdf, Path out, PdfExportPlan plan, Map<Integer, Integer> pages, int contentsPage) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int total = doc.getNumberOfPages();
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < total; i++) {
                PDPage page = doc.getPage(i);
                PDRectangle box = page.getMediaBox();
                String label = (i + 1) + " / " + total;
                float size = 9f;
                float width = font.getStringWidth(label) / 1000 * size;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.beginText();
                    cs.setFont(font, size);
                    cs.setNonStrokingColor(0.35f, 0.38f, 0.42f);
                    cs.newLineAtOffset((box.getWidth() - width) / 2, 22);
                    cs.showText(label);
                    cs.endText();
                }
            }

            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            if (contentsPage >= 1 && contentsPage <= total) outline.addLast(bookmarkOf("Contents", doc.getPage(contentsPage - 1)));
            Deque<Map.Entry<Integer, PDOutlineNode>> stack = new ArrayDeque<>();
            for (PdfExportPlan.Part p : plan.parts()) {
                Integer at = pages.get(p.number());
                if (at == null || at < 1 || at > total) continue;
                PDOutlineItem it = bookmarkOf(p.title(), doc.getPage(at - 1));
                while (!stack.isEmpty() && stack.peek().getKey() >= p.level()) stack.pop();
                PDOutlineNode parent = stack.isEmpty() ? outline : stack.peek().getValue();
                parent.addLast(it);
                stack.push(Map.entry(p.level(), it));
            }
            doc.save(out.toFile());
            return total;
        }
    }

    private static PDOutlineItem bookmarkOf(String title, PDPage page) {
        PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
        dest.setPage(page);
        PDOutlineItem it = new PDOutlineItem();
        it.setTitle(title);
        it.setDestination(dest);
        return it;
    }

    /** @return how many pages a PDF has */
    static int pageCount(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }

}
