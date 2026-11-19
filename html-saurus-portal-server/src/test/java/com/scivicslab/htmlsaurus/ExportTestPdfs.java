package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;

/** Writes small PDFs that look, to {@link PdfExportFinish}, like what Chrome prints: pages of text, and links by name. */
final class ExportTestPdfs {

    private ExportTestPdfs() {}

    /**
     * Writes a PDF.
     *
     * @param pdf          where to write it
     * @param pageTexts    the text of each page, in order
     * @param linkPage     the page (from 1) that holds the links of the table of contents, or 0 for none
     * @param startOfPart  the page (from 1) each part starts on, by part number; each becomes a link on {@code linkPage}
     *                     to the named destination {@code hs-part-<number>}
     */
    static void write(Path pdf, List<String> pageTexts, int linkPage, Map<Integer, Integer> startOfPart) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            List<PDPage> pages = new ArrayList<>();
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                pages.add(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            if (linkPage > 0) {
                PDDocumentCatalog catalog = doc.getDocumentCatalog();
                Map<String, PDPageDestination> destinations = new LinkedHashMap<>();
                List<PDAnnotation> annotations = new ArrayList<>();
                float y = 650;
                for (Map.Entry<Integer, Integer> part : startOfPart.entrySet()) {
                    String name = PdfExportPlan.ANCHOR_PREFIX + part.getKey();
                    PDPageXYZDestination destination = new PDPageXYZDestination();
                    destination.setPage(pages.get(part.getValue() - 1));
                    destinations.put(name, destination);
                    PDAnnotationLink link = new PDAnnotationLink();
                    link.setRectangle(new PDRectangle(72, y, 200, 12));
                    link.setDestination(new PDNamedDestination(name));
                    annotations.add(link);
                    y -= 14;
                }
                PDDocumentNameDictionary names = new PDDocumentNameDictionary(catalog);
                PDDestinationNameTreeNode tree = new PDDestinationNameTreeNode();
                tree.setNames(destinations);
                names.setDests(tree);
                catalog.setNames(names);
                pages.get(linkPage - 1).setAnnotations(annotations);
            }
            doc.save(pdf.toFile());
        }
    }
}
