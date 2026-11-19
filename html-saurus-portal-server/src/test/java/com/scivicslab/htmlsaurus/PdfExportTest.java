package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every document under one directory of a doc project as one PDF (ExportToPdf_261119_oo01): which documents in what
 * order and at what heading level, what of each built page goes in, and the page numbers and bookmarks.
 */
@Tag("ExportToPdf_261119_oo01")
class PdfExportTest {

    @TempDir
    Path dir;

    private static SiteNode leaf(String title, String href) {
        return new SiteNode(title, href, false, List.of(), null);
    }

    private static SiteNode folder(String label, String catLink, SiteNode... children) {
        return new SiteNode(label, catLink, true, List.of(children), catLink);
    }

    /** docs/AAS-G1/{CHM13-expr1/{a,b}, GRCh38-expr1/{c}, manuals/{m}} and a sibling docs/other/x. */
    private static SiteNode tree() {
        return folder("docs", null,
                folder("AAS-G1", null,
                        folder("CHM13-expr1", null, leaf("Plan", "/AAS-G1/CHM13-expr1/Plan/"), leaf("Index", "/AAS-G1/CHM13-expr1/Index/")),
                        folder("GRCh38-expr1", null, leaf("Log in", "/AAS-G1/GRCh38-expr1/LogIn/")),
                        folder("Manuals", "/AAS-G1/manuals/", leaf("Guide", "/AAS-G1/manuals/Guide/"))),
                folder("other", null, leaf("X", "/other/X/")));
    }

    private static List<String> titles(PdfExportPlan plan) {
        return plan.parts().stream().map(p -> p.level() + ":" + p.title()).toList();
    }

    @Test
    void aDirectoryIsItsDocumentsInSidebarOrder_withItsSubdirectoriesAsHeadings() {
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "AAS-G1", "doc_AASG1");
        assertEquals("AAS-G1", plan.title());
        assertEquals(List.of("1:CHM13-expr1", "2:Plan", "2:Index", "1:GRCh38-expr1", "2:Log in", "1:Manuals", "2:Guide"),
                titles(plan));
        assertTrue(plan.parts().get(0).folder());
        assertEquals("/AAS-G1/manuals/", plan.parts().get(5).href(), "a directory's own page opens its section");
        assertEquals(5, plan.documentCount(), "four documents and one directory page");
    }

    @Test
    void aSubdirectoryWithNumericPrefixIsFoundByItsCleanedPath() {
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "AAS-G1/010_CHM13-expr1".replace("010_", ""), "doc_AASG1");
        assertEquals("CHM13-expr1", plan.title());
        assertEquals(List.of("1:Plan", "1:Index"), titles(plan));
        assertEquals(List.of("1:Plan", "1:Index"),
                titles(PdfExportPlan.planOfDirectory(tree(), "AAS-G1/010_CHM13-expr1", "doc_AASG1")), "the number prefix is dropped as the sidebar does");
    }

    @Test
    void theWholeProjectHasItsTopDirectoriesAtLevelOne() {
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "", "doc_AASG1");
        assertEquals("doc_AASG1", plan.title());
        assertEquals("1:AAS-G1", titles(plan).get(0));
        assertEquals("2:CHM13-expr1", titles(plan).get(1));
        assertTrue(titles(plan).contains("1:other"));
        assertEquals(6, plan.documentCount());
    }

    @Test
    void planOfDirectory_whenTheProjectHoldsOnlyTheChosenDirectory_titlesThePdfWithTheDirectoryNotTheDocsNode() {
        SiteNode onlyOne = folder("docs", null,
                folder("AAS-G1", null,
                        folder("CHM13-expr1", null, leaf("Plan", "/AAS-G1/CHM13-expr1/Plan/")),
                        folder("Manuals", null, leaf("Guide", "/AAS-G1/manuals/Guide/"))));
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(onlyOne, "AAS-G1", "doc_AASG1");
        assertEquals("AAS-G1", plan.title());
        assertEquals(List.of("1:CHM13-expr1", "2:Plan", "1:Manuals", "2:Guide"), titles(plan));
    }

    @Test
    void planOfDirectory_whenADocumentsFolderHoldsOnlyItsPage_plansItAsADocumentNotADirectory() {
        SiteNode scripts = folder("scripts", null);
        SiteNode ownFolder = new SiteNode("Plan", "/A/Plan/", true, List.of(scripts), "/A/Plan/");
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(folder("docs", null, folder("A", null, ownFolder, leaf("Other", "/A/Other/"))), "A", "p");
        assertEquals(List.of("1:Plan", "1:Other"), titles(plan));
        assertFalse(plan.parts().get(0).folder(), "a document's own folder is a document");
    }

    @Test
    void aDirectoryWithNoDocumentPlansNothing() {
        assertTrue(PdfExportPlan.planOfDirectory(tree(), "nowhere", "p").parts().isEmpty());
    }

    @Test
    void theFileNameNamesTheProjectAndTheDirectory() {
        assertEquals("doc_AASG1-AAS-G1-CHM13-expr1.pdf", PdfExportJob.fileName("doc_AASG1", "AAS-G1/CHM13-expr1/"));
        assertEquals("doc_AASG1.pdf", PdfExportJob.fileName("doc_AASG1", ""));
    }

    private Path page(Path staticDir, String href, String main) throws Exception {
        Path file = PdfExportHtml.builtPageFile(staticDir, href);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<html><body><nav class=\"side\">sidebar</nav><main>" + main + "</main><aside class=\"toc\">toc</aside></body></html>");
        return file;
    }

    @Test
    void aPagesBodyKeepsItsArticle_withHeadingsShiftedIdsPrefixedImagesAbsoluteAndLinksInsideThePdf() throws Exception {
        Path staticDir = dir.resolve("static-html");
        page(staticDir, "/AAS-G1/CHM13-expr1/Plan/", """
                <h1>Plan</h1><div class="copy-bar">copy</div>
                <h2 id="problem-definition">Problem Definition</h2><p>本文 <a href="#problem-definition">here</a></p>
                <h3>Detail</h3><img src="fig.png"><img src="/shared/logo.svg">
                <p><a href="../Index/">the index</a> <a href="../../../other/X/">elsewhere</a> <a href="https://example.org/">web</a></p>
                <pre><code>$ ls</code></pre>
                <footer class="source-footer">src</footer><div class="page-last-updated">Last updated</div>
                <nav class="page-nav"><a href="../Index/">Next</a></nav>
                """);
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "AAS-G1", "doc_AASG1");
        PdfExportPlan.Part planPart = plan.parts().get(1);
        String body = PdfExportHtml.bodyOfPage(staticDir, planPart,
                Map.of("/AAS-G1/CHM13-expr1/Index/", plan.parts().get(2).anchor()));

        assertFalse(body.contains("copy-bar") || body.contains("source-footer") || body.contains("Last updated")
                || body.contains("page-nav") || body.contains("sidebar"), body);
        assertFalse(body.contains("<h1>"), "the page's own title is the part's heading");
        assertTrue(body.contains("<h3 id=\"d2-problem-definition\">"), "level-2 document: h2 moves to h3, id prefixed: " + body);
        assertTrue(body.contains("<h4>Detail</h4>"), body);
        assertTrue(body.contains("href=\"#d2-problem-definition\""), body);
        assertTrue(body.contains("src=\"" + staticDir.resolve("AAS-G1/CHM13-expr1/Plan/fig.png").toUri()), body);
        assertTrue(body.contains("src=\"" + staticDir.resolve("shared/logo.svg").toUri()), body);
        assertTrue(body.contains("href=\"#" + plan.parts().get(2).anchor() + "\">the index</a>"), "a document in the PDF: " + body);
        assertTrue(body.contains("elsewhere") && !body.contains("other/X"), "a document outside the PDF: plain text: " + body);
        assertTrue(body.contains("href=\"https://example.org/\""), body);
        assertTrue(body.contains("本文") && body.contains("$ ls"), body);
    }

    @Test
    void theCombinedHtmlHasACoverTheContentsAndEveryPart_andMarksEachPartsPage() throws Exception {
        Path staticDir = dir.resolve("static-html");
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "AAS-G1", "doc_AASG1");
        for (PdfExportPlan.Part p : plan.parts()) if (p.href() != null) page(staticDir, p.href(), "<h1>" + p.title() + "</h1><p>body of " + p.title() + "</p>");
        String first = PdfExportHtml.renderCombinedHtml(plan, "doc_AASG1/docs/AAS-G1", staticDir, Map.of());
        assertTrue(first.contains("data-theme=\"light-clean\""));
        assertTrue(first.contains("katex") && first.contains("mermaid"), "math and diagrams render as on screen");
        assertTrue(first.contains("doc_AASG1/docs/AAS-G1"));
        assertEquals(plan.parts().size(), first.split("hs-toc-page\">000<", -1).length - 1, "first printing: every line shows 000");
        for (PdfExportPlan.Part p : plan.parts()) {
            assertTrue(first.contains("id=\"" + p.anchor() + "\""), p.title());
        }
        assertTrue(first.contains("body of Guide"));
        String second = PdfExportHtml.renderCombinedHtml(plan, "doc_AASG1/docs/AAS-G1", staticDir, Map.of(1, 3, 2, 3, 3, 4));
        assertTrue(second.contains("hs-toc-page\">3<") && second.contains("hs-toc-page\">4<"));
        // The heading of CHM13-expr1 and its first document share a page; the next document starts a new one.
        assertTrue(second.contains("class=\"hs-part\" id=\"" + plan.parts().get(1).anchor() + "\""), "no break after a directory heading");
        assertTrue(second.contains("class=\"hs-part hs-break\" id=\"" + plan.parts().get(2).anchor() + "\""));
    }

    @Test
    void bodyOfPage_keepsTheLineBreaksOfAMermaidDiagram_becauseTheDiagramIsOneStatementPerLine() throws Exception {
        Path staticDir = dir.resolve("static-html");
        page(staticDir, "/AAS-G1/CHM13-expr1/Plan/",
                "<h1>Plan</h1><div class=\"mermaid\">graph LR\n  A[Browser] --&gt; B[Portal]\n  B --&gt; C[Chrome]\n</div><p>text</p>");
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "AAS-G1", "doc_AASG1");
        String body = PdfExportHtml.bodyOfPage(staticDir, plan.parts().get(1), Map.of());
        assertTrue(body.contains("graph LR\n  A[Browser] --&gt; B[Portal]\n  B --&gt; C[Chrome]\n"),
                "Mermaid reads the lines of the diagram as they were written: " + body);
    }

    @Test
    void bodyOfPage_dropsWhatWouldReadFilesOutsideTheSite_andWhatRunsCode() throws Exception {
        Path staticDir = dir.resolve("static-html");
        Path secret = Files.writeString(dir.resolve("secret.png"), "x");
        page(staticDir, "/AAS-G1/CHM13-expr1/Plan/", """
                <h1>Plan</h1>
                <img id="inside" src="fig.png">
                <img id="up" src="../../../../secret.png">
                <img id="absolute" src="file://%s">
                <img id="schemeless" src="//example.org/x.png">
                <img id="web" src="https://example.org/x.png">
                <img id="handler" src="fig.png" onerror="alert(1)" onclick="x()">
                <iframe src="https://example.org/"></iframe><object data="x"></object><embed src="x">
                <p onmouseover="x()">text</p>
                """.formatted(secret));
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "AAS-G1", "doc_AASG1");
        String body = PdfExportHtml.bodyOfPage(staticDir, plan.parts().get(1), Map.of());
        assertTrue(body.contains("fig.png"), "an image of the site stays: " + body);
        assertTrue(body.contains("https://example.org/x.png"), "an image on the web stays: " + body);
        assertFalse(body.contains("secret.png"), "an image outside the site is dropped: " + body);
        assertFalse(body.contains("//example.org/x.png\"") && body.contains("id=\"schemeless\" src"), "a scheme-less URL is dropped: " + body);
        assertFalse(body.contains("<iframe") || body.contains("<object") || body.contains("<embed"), body);
        assertFalse(body.contains("onerror") || body.contains("onclick") || body.contains("onmouseover"), body);
    }

    @Test
    void renderCombinedHtml_whenADirectoryIsFollowedByASubdirectory_putsTheirHeadingsOnOnePage() throws Exception {
        Path staticDir = dir.resolve("static-html");
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "", "doc_AASG1");
        for (PdfExportPlan.Part p : plan.parts()) if (p.href() != null) page(staticDir, p.href(), "<p>body of " + p.title() + "</p>");
        String html = PdfExportHtml.renderCombinedHtml(plan, "doc_AASG1/docs", staticDir, Map.of());
        // Parts 1 and 2 are the headings of AAS-G1 and of CHM13-expr1 under it: no page break between them.
        assertTrue(html.contains("class=\"hs-part\" id=\"" + plan.parts().get(1).anchor() + "\""), "the subdirectory's heading follows on the same page");
        assertTrue(html.contains("class=\"hs-part hs-break\" id=\"" + plan.parts().get(0).anchor() + "\""), "the first directory starts a page");
    }

    @Test
    void deleteDirectory_removesTheDirectoryAndEverythingUnderIt_andDoesNothingForAMissingOne() throws Exception {
        Path work = dir.resolve("work");
        Files.createDirectories(work.resolve("chrome-profile/sub"));
        Files.writeString(work.resolve("chrome-profile/sub/x"), "x");
        Files.writeString(work.resolve("a.pdf"), "pdf");
        PdfExportJob.deleteDirectory(work);
        assertFalse(Files.exists(work));
        PdfExportJob.deleteDirectory(work);
        PdfExportJob.deleteDirectory(null);
    }

    @Test
    void eachPartsPageIsReadFromTheLinksOfTheContents_thenPagesAreNumberedAndBookmarked() throws Exception {
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "AAS-G1", "doc_AASG1");
        Map<Integer, Integer> start = Map.of(1, 3, 2, 3, 3, 4, 4, 5, 5, 5, 6, 6, 7, 7);
        Path pdf = dir.resolve("in.pdf");
        ExportTestPdfs.write(pdf, List.of("cover", "Contents", "CHM13 Plan", "Index", "GRCh38 Log in", "Manuals", "Guide"), 2, start);

        PdfExportFinish.PrintedPages found = PdfExportFinish.readPrintedPages(pdf, plan);
        assertEquals(Map.of(1, 3, 2, 3, 3, 4, 4, 5, 5, 5, 6, 6, 7, 7), found.parts());
        assertEquals(2, found.contentsPage());
        Map<Integer, Integer> pages = found.parts();

        Path out = dir.resolve("out.pdf");
        assertEquals(7, PdfExportFinish.stampPageNumbersAndBookmarks(pdf, out, plan, pages, found.contentsPage()));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(4);
            stripper.setEndPage(4);
            assertTrue(stripper.getText(doc).contains("4 / 7"), "every page carries n / N");
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            List<String> top = new java.util.ArrayList<>();
            for (PDOutlineItem it : outline.children()) top.add(it.getTitle());
            assertEquals(List.of("Contents", "CHM13-expr1", "GRCh38-expr1", "Manuals"), top);
            PDOutlineItem chm = outline.getFirstChild().getNextSibling();
            List<String> under = new java.util.ArrayList<>();
            for (PDOutlineItem it : chm.children()) under.add(it.getTitle());
            assertEquals(List.of("Plan", "Index"), under, "documents nest under their directory");
        }
    }

    @Test
    void readPrintedPages_whenAPartHasNoLink_namesTheParts() throws Exception {
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "AAS-G1", "doc_AASG1");
        Path pdf = dir.resolve("in.pdf");
        ExportTestPdfs.write(pdf, List.of("cover", "Contents", "a"), 2, Map.of(1, 3, 2, 3));
        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> PdfExportFinish.readPrintedPages(pdf, plan));
        assertTrue(failure.getMessage().contains("Index"), failure.getMessage());
    }

    @Test
    void renderCombinedHtml_putsEveryContentsNumberInAColumnOfOneWidth_andNoMarkerInTheText() throws Exception {
        Path staticDir = dir.resolve("static-html");
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "AAS-G1", "doc_AASG1");
        String html = PdfExportHtml.renderCombinedHtml(plan, "doc_AASG1/docs/AAS-G1", staticDir, Map.of());
        assertTrue(html.contains("min-width: 4em; text-align: right"), "a 3-digit placeholder and a 1-digit page take the same width");
        assertFalse(html.contains("HSXA"), "nothing is hidden in the text of the pages");
    }

    @Test
    void mathExpressions_findsMathOutsideCodeOnly() {
        String html = "<body><p>The $E = mc^2$ and $$x_1 + x_2$$ here.</p><pre><code>echo $HOME and $PATH</code></pre><p>It costs 5 dollars.</p></body>";
        assertEquals(List.of("$E = mc^2$", "$$x_1 + x_2$$"), PdfExportHtml.mathExpressions(html));
    }

    @Test
    void textContainsAny_ignoresWhiteSpace() throws Exception {
        Path pdf = dir.resolve("text.pdf");
        ExportTestPdfs.write(pdf, List.of("The formula $E = mc^2$ here."), 0, Map.of());
        assertTrue(PdfExportFinish.textContainsAny(pdf, List.of("$E=mc^2$")));
        assertFalse(PdfExportFinish.textContainsAny(pdf, List.of("$F=ma$")));
        assertFalse(PdfExportFinish.textContainsAny(pdf, List.of()));
    }

    /** The script tags that load from the CDN, with their attributes, as one line each. */
    private static List<String> cdnScriptTags(String html) {
        List<String> tags = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<script[^>]*cdn\\.jsdelivr\\.net[^>]*>", java.util.regex.Pattern.DOTALL).matcher(html);
        while (m.find()) tags.add(m.group().replaceAll("\\s+", " "));
        return tags;
    }

    @Test
    void renderCombinedHtml_loadsKatexAndMermaidThroughTheSameTagsAsAPageOnScreen() throws Exception {
        PdfExportPlan plan = PdfExportPlan.planOfDirectory(tree(), "AAS-G1", "doc_AASG1");
        String printed = PdfExportHtml.renderCombinedHtml(plan, "doc_AASG1/docs/AAS-G1", dir.resolve("static-html"), Map.of());
        List<String> onScreen = cdnScriptTags(PageScripts.render());
        assertEquals(3, onScreen.size(), "a page loads Mermaid, KaTeX and its auto-render script: " + onScreen);
        assertEquals(onScreen, cdnScriptTags(printed), "the versions, integrity hashes and the draw call match the screen's");
        assertTrue(printed.replaceAll("\\s+", " ").contains("mermaid.initialize({startOnLoad: true});"), "the diagrams are drawn as on screen");
    }
}
