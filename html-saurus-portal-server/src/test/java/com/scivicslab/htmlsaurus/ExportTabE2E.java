package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;

/**
 * E2E test for the portal's Export tab (ExportToPdf_261119_oo01): picks a directory of a doc project, makes the PDF,
 * downloads it through the browser and checks the PDF that arrived.
 *
 * <p>Requires an already-running dev-mode portal over a <b>copy</b> of the doc project (the export rebuilds the
 * project's HTML, so a real project must not be pointed at; see {@code RunningE2ETests_260902_oo01}), and a Chrome on
 * the portal's machine for printing. The test machine needs a Chromium that Playwright can drive.
 * <pre>
 *   java -Dhtml-saurus.chrome=/path/to/chrome -jar html-saurus.jar serve -d &lt;copy of works&gt; -p 18400
 * </pre>
 *
 * <p>Run (see {@code ExportTabE2E_261119_oo01} for what each setting does):
 * <pre>
 *   PORTAL_URL=http://localhost:18400 EXPORT_PROJECT=doc_AASG1 EXPORT_DIR=AAS-G1 EXPORT_OUT=/tmp/export-out \
 *   BROWSER_PATH=/path/to/chromium \
 *   mvn test-compile exec:java -Dexec.mainClass=com.scivicslab.htmlsaurus.ExportTabE2E -Dexec.classpathScope=test
 * </pre>
 *
 * <p>{@code EXPORT_DOCS}, when given, is the project's {@code docs/} directory; the cover's title is then checked against the
 * name the sidebar shows for {@code EXPORT_DIR}, which {@code _category_.json} can change.
 *
 * <p>The PDF is saved as {@code EXPORT_OUT/export.pdf}; the first pages and the pages named by {@code EXPORT_PAGES}
 * (comma-separated page numbers) are rendered to {@code EXPORT_OUT/page-N.png} to be looked at.
 */
public class ExportTabE2E {

    private static final String BASE_URL = System.getenv().getOrDefault("PORTAL_URL", "http://localhost:18400");
    private static final String PROJECT = System.getenv().getOrDefault("EXPORT_PROJECT", "doc_AASG1");
    private static final String DIRECTORY = System.getenv().getOrDefault("EXPORT_DIR", "AAS-G1");
    private static final Path OUT = Path.of(System.getenv().getOrDefault("EXPORT_OUT", "/tmp/export-out"));
    /** The project's {@code docs/} on the machine the test runs on, to ask the sidebar what the chosen directory is called. */
    private static final String DOCS_DIRECTORY = System.getenv().getOrDefault("EXPORT_DOCS", "");
    private static final String PAGES_TO_RENDER = System.getenv().getOrDefault("EXPORT_PAGES", "");
    private static final String BROWSER_PATH = System.getenv("BROWSER_PATH");
    /** How long the export may take; the project's HTML is rebuilt and printed twice. */
    private static final int EXPORT_TIMEOUT_MS = 15 * 60 * 1000;

    private static int passed = 0;
    private static int failed = 0;

    /**
     * One line of the table of contents.
     *
     * @param title  the heading the line shows
     * @param shown  the page number the line shows at its end
     * @param linked the page the line's link points at
     */
    private record ContentsLine(String title, int shown, int linked) {}

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        Playwright.CreateOptions options = new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
        try (Playwright playwright = Playwright.create(options)) {
            BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true);
            if (BROWSER_PATH != null && !BROWSER_PATH.isBlank()) launch.setExecutablePath(Path.of(BROWSER_PATH));
            Browser browser = playwright.chromium().launch(launch);
            Path pdf = makeAndDownloadPdf(browser);
            if (pdf != null) checkPdf(pdf);
        }
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + (detail.isEmpty() ? "" : ": " + detail));
        }
    }

    /** Drives the tab: lists directories, makes the PDF, waits for the job and downloads the file. */
    private static Path makeAndDownloadPdf(Browser browser) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(true));
        Page page = context.newPage();
        page.setDefaultTimeout(60_000);
        page.navigate(BASE_URL + "/");
        check("the left pane's menu has Export", page.locator("#pane-option-export").count() == 1,
                "menu: " + page.locator("#pane-menu option").allInnerTexts());
        page.selectOption("#pane-menu", "export");
        check("the Export panel opens and the others close",
                page.locator("#tab-export").isVisible() && !page.locator("#tab-projects").isVisible(), "");

        page.waitForSelector("#export-project option", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));
        page.selectOption("#export-project", PROJECT);
        page.waitForSelector("#export-dir option:nth-child(2)",
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));
        List<String> shown = page.locator("#export-dir option").allInnerTexts();
        List<String> values = new ArrayList<>();
        for (Locator option : page.locator("#export-dir option").all()) values.add(option.getAttribute("value"));
        check("the first directory is the whole project", "".equals(values.get(0)), values.toString());
        check("the directory to export is offered", values.contains(DIRECTORY), values.toString());
        check("a directory deeper than the first level is indented", shown.stream().anyMatch(s -> s.startsWith(" ")),
                shown.toString());
        System.out.println("  directories offered: " + values.size());

        page.selectOption("#export-dir", DIRECTORY);
        String jobId = startTheExport(page);
        String row = "#export-jobs .import-job[data-job-id=\"" + jobId + "\"]";
        String line = waitUntilTheExportEnds(page, row);
        System.out.println("  job line: " + line);
        check("the export finished", line.startsWith("Done"), line);
        if (!line.startsWith("Done")) return null;

        Download download = page.waitForDownload(() -> page.locator(row + " a.btn").first().click());
        Path pdf = OUT.resolve("export.pdf");
        download.saveAs(pdf);
        check("the browser downloaded a PDF file",
                download.suggestedFilename().endsWith(".pdf") && fileStartsWithPdfMagic(pdf), download.suggestedFilename());
        System.out.println("  downloaded " + download.suggestedFilename() + " (" + pdf.toFile().length() + " bytes)");
        check("the Batch Job tab lists the same job with a Download link", batchTabHasDownload(page, jobId), "");
        context.close();
        return pdf;
    }

    /** Presses {@code Make PDF} and reads the id of the job from the answer to that press. */
    private static String startTheExport(Page page) {
        Response answer = page.waitForResponse(r -> r.url().contains("/api/export/pdf/start"), () -> page.click("#export-start"));
        Matcher matcher = Pattern.compile("\"jobId\"\\s*:\\s*\"([^\"]+)\"").matcher(answer.text());
        if (!matcher.find()) throw new IllegalStateException("No jobId in the answer: " + answer.text());
        return matcher.group(1);
    }

    /**
     * The page's content security policy forbids the string evaluation that {@code waitForFunction} needs, so the line is
     * read again every two seconds.
     *
     * @param row the selector of the row of the job this test started; other rows are not looked at
     * @return the job's line once it says Done, Error or Stopped; the last line seen when the time is up
     */
    private static String waitUntilTheExportEnds(Page page, String row) {
        long deadline = System.currentTimeMillis() + EXPORT_TIMEOUT_MS;
        String line = "";
        while (System.currentTimeMillis() < deadline) {
            Locator lines = page.locator(row + " .import-job-line");
            if (lines.count() > 0) {
                line = lines.first().innerText();
                if (line.startsWith("Done") || line.startsWith("Error") || line.startsWith("Stopped")) return line;
            }
            page.waitForTimeout(2000);
        }
        return line;
    }

    private static boolean batchTabHasDownload(Page page, String jobId) {
        page.selectOption("#pane-menu", "batch");
        return page.locator("#import-jobs .import-job[data-job-id=\"" + jobId + "\"] a.btn").count() >= 1;
    }

    private static boolean fileStartsWithPdfMagic(Path file) {
        try {
            return new String(Files.readAllBytes(file), 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1).equals("%PDF-");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Opens the PDF that was downloaded and checks it: a cover, a contents page whose numbers are the pages the
     * bookmarks point at, each of those pages carrying its heading, and the n / N footer. Renders pages to look at.
     */
    private static void checkPdf(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            int total = document.getNumberOfPages();
            System.out.println("  pages: " + total);
            check("the PDF has more pages than a cover and a contents page", total > 3, "pages " + total);

            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pageTexts = new ArrayList<>();
            for (int i = 1; i <= total; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pageTexts.add(stripper.getText(document));
            }
            check("every page carries its n / N footer", everyPageHasFooter(pageTexts), "");
            String expectedTitle = nameInTheSidebar();
            check("the cover is titled with the name the sidebar shows for the chosen directory (" + expectedTitle + ")",
                    squash(pageTexts.get(0)).contains(squash(expectedTitle)), pageTexts.get(0).strip());
            List<Integer> nearlyEmpty = new ArrayList<>();
            for (int i = 1; i < pageTexts.size(); i++) {
                if (squash(pageTexts.get(i)).length() < 30) nearlyEmpty.add(i + 1);
            }
            check("no page after the cover is empty apart from its footer", nearlyEmpty.isEmpty(), "pages " + nearlyEmpty);

            List<PDOutlineItem> bookmarks = new ArrayList<>();
            collectBookmarks(document.getDocumentCatalog().getDocumentOutline(), bookmarks);
            check("the PDF has bookmarks", bookmarks.size() > 1, "bookmarks " + bookmarks.size());
            if (bookmarks.size() <= 1) return;

            List<ContentsLine> contents = readContentsLines(document);
            // The first bookmark is Contents itself; the rest are the parts in order, as the contents lines are.
            List<PDOutlineItem> parts = bookmarks.subList(1, bookmarks.size());
            check("the contents have one line per bookmarked part", contents.size() == parts.size(),
                    contents.size() + " lines, " + parts.size() + " bookmarks");
            int wrongNumber = 0;
            int differentFromBookmark = 0;
            int missingHeading = 0;
            for (int i = 0; i < contents.size(); i++) {
                ContentsLine line = contents.get(i);
                if (line.shown() != line.linked()) {
                    wrongNumber++;
                    System.out.println("    the line shows " + line.shown() + ", its link points at " + line.linked() + ": " + line.title());
                }
                if (i < parts.size() && document.getPages().indexOf(parts.get(i).findDestinationPage(document)) + 1 != line.linked()) {
                    differentFromBookmark++;
                    System.out.println("    the bookmark is not where the link of " + line.title() + " points (page " + line.linked() + ")");
                }
                if (line.linked() < 1 || !squash(pageTexts.get(line.linked() - 1)).contains(squash(line.title()))) {
                    missingHeading++;
                    System.out.println("    page " + line.linked() + " does not carry its heading: " + line.title());
                }
            }
            check("every number a contents line shows is the page its link points at", wrongNumber == 0, wrongNumber + " differ");
            check("every bookmark is where the link of the same contents line points", differentFromBookmark == 0, differentFromBookmark + " differ");
            check("the page every contents line points at carries that heading", missingHeading == 0, missingHeading + " do not");
            check("the contents are in the order of the pages", pagesAscend(contents), "");
            check("Japanese text is on the pages", pageTexts.stream().anyMatch(t -> t.matches("(?s).*[぀-ヿ一-鿿].*")), "");

            renderPages(document, total);
        }
    }

    /**
     * What the sidebar calls the chosen directory: its name with the number in front of it dropped, or the label that
     * {@code _category_.json} gives it. {@code NavTreeBuilder} makes the sidebar, so it is asked; when {@code EXPORT_DOCS}
     * does not say where the project's {@code docs/} is, the last name of the path is used.
     */
    private static String nameInTheSidebar() throws IOException {
        String lastSegment = DIRECTORY.substring(DIRECTORY.lastIndexOf('/') + 1);
        if (DOCS_DIRECTORY.isBlank()) return lastSegment;
        SiteNode tree = new NavTreeBuilder(Path.of(DOCS_DIRECTORY), new MarkdownConverter(), null, null, null).build();
        return PdfExportPlan.planOfDirectory(tree, DIRECTORY, PROJECT).title();
    }

    private static boolean everyPageHasFooter(List<String> pageTexts) {
        for (int i = 0; i < pageTexts.size(); i++) {
            if (!squash(pageTexts.get(i)).contains((i + 1) + "/" + pageTexts.size())) return false;
        }
        return true;
    }

    private static boolean pagesAscend(List<ContentsLine> contents) {
        for (int i = 1; i < contents.size(); i++) {
            if (contents.get(i).linked() < contents.get(i - 1).linked()) return false;
        }
        return true;
    }

    /** Every bookmark, parents before their children, in the order a reader meets them. */
    private static void collectBookmarks(PDOutlineNode node, List<PDOutlineItem> into) {
        if (node == null) return;
        for (PDOutlineItem item : node.children()) {
            into.add(item);
            collectBookmarks(item, into);
        }
    }

    /**
     * Reads the lines of the table of contents. A line is a link to the anchor of a part, on a page before the first
     * part; the number it shows is the number at the end of the text row the link sits in. Reading the lines through
     * their links, not by their look, keeps a heading that wraps or ends in a number from being taken for a line.
     */
    private static List<ContentsLine> readContentsLines(PDDocument document) throws IOException {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        List<PDAnnotationLink> links = new ArrayList<>();
        List<Integer> pagesOfLinks = new ArrayList<>();
        List<Integer> targets = new ArrayList<>();
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            for (PDAnnotation annotation : document.getPage(i).getAnnotations()) {
                if (!(annotation instanceof PDAnnotationLink link) || !(link.getDestination() instanceof PDNamedDestination named)) continue;
                if (named.getNamedDestination() == null || !named.getNamedDestination().startsWith("hs-part-")) continue;
                PDPageDestination target = catalog.findNamedDestinationPage(named);
                links.add(link);
                pagesOfLinks.add(i);
                targets.add(target == null || target.getPage() == null ? -1 : document.getPages().indexOf(target.getPage()) + 1);
            }
        }
        int firstPartPage = targets.stream().filter(t -> t > 0).min(Integer::compare).orElse(Integer.MAX_VALUE);
        List<ContentsLine> lines = new ArrayList<>();
        PDFTextStripperByArea rows = new PDFTextStripperByArea();
        for (int n = 0; n < links.size(); n++) {
            if (pagesOfLinks.get(n) + 1 >= firstPartPage) continue;
            PDPage page = document.getPage(pagesOfLinks.get(n));
            PDRectangle box = page.getMediaBox();
            PDRectangle at = links.get(n).getRectangle();
            float top = box.getHeight() - at.getUpperRightY();
            rows.addRegion("row", new Rectangle2D.Float(0, top - 1, box.getWidth(), at.getHeight() + 2));
            rows.extractRegions(page);
            String text = rows.getTextForRegion("row").strip().replaceAll("\\s+", " ");
            rows.removeRegion("row");
            Matcher number = Pattern.compile("^(.*?)\\s*(\\d{1,4})$").matcher(text);
            lines.add(number.matches() ? new ContentsLine(number.group(1), Integer.parseInt(number.group(2)), targets.get(n))
                                       : new ContentsLine(text, -1, targets.get(n)));
        }
        return lines;
    }

    private static String squash(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static void renderPages(PDDocument document, int total) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        List<Integer> wanted = new ArrayList<>(List.of(1, 2, 3, 4));
        for (String n : PAGES_TO_RENDER.split(",")) {
            if (!n.isBlank()) wanted.add(Integer.parseInt(n.strip()));
        }
        for (int p : wanted) {
            if (p < 1 || p > total) continue;
            BufferedImage image = renderer.renderImageWithDPI(p - 1, 80);
            ImageIO.write(image, "png", OUT.resolve("page-" + p + ".png").toFile());
        }
        System.out.println("  rendered pages " + wanted + " to " + OUT);
    }
}
