package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitForSelectorState;

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
import org.apache.pdfbox.text.PDFTextStripper;

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
 * <p>The PDF is saved as {@code EXPORT_OUT/export.pdf}; the first pages and the pages named by {@code EXPORT_PAGES}
 * (comma-separated page numbers) are rendered to {@code EXPORT_OUT/page-N.png} to be looked at.
 */
public class ExportTabE2E {

    private static final String BASE_URL = System.getenv().getOrDefault("PORTAL_URL", "http://localhost:18400");
    private static final String PROJECT = System.getenv().getOrDefault("EXPORT_PROJECT", "doc_AASG1");
    private static final String DIRECTORY = System.getenv().getOrDefault("EXPORT_DIR", "AAS-G1");
    private static final Path OUT = Path.of(System.getenv().getOrDefault("EXPORT_OUT", "/tmp/export-out"));
    private static final String PAGES_TO_RENDER = System.getenv().getOrDefault("EXPORT_PAGES", "");
    private static final String BROWSER_PATH = System.getenv("BROWSER_PATH");
    /** How long the export may take; the project's HTML is rebuilt and printed twice. */
    private static final int EXPORT_TIMEOUT_MS = 15 * 60 * 1000;

    private static int passed = 0;
    private static int failed = 0;

    /** One line of the table of contents: the heading and the page it says. */
    private record ContentsLine(String title, int page) {}

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
        check("the left pane has the fourth tab Export", page.locator("#tab-btn-export").count() == 1,
                "tabs: " + page.locator(".tab-btn").allInnerTexts());
        page.click("#tab-btn-export");
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
        page.click("#export-start");
        String line = waitUntilTheExportEnds(page);
        System.out.println("  job line: " + line);
        check("the export finished", line.startsWith("Done"), line);
        if (!line.startsWith("Done")) return null;

        Download download = page.waitForDownload(() -> page.locator("#export-jobs a.btn").first().click());
        Path pdf = OUT.resolve("export.pdf");
        download.saveAs(pdf);
        check("the browser downloaded a PDF file",
                download.suggestedFilename().endsWith(".pdf") && fileStartsWithPdfMagic(pdf), download.suggestedFilename());
        System.out.println("  downloaded " + download.suggestedFilename() + " (" + pdf.toFile().length() + " bytes)");
        check("the Batch Job tab lists the same job with a Download link", batchTabHasDownload(page), "");
        context.close();
        return pdf;
    }

    /**
     * The page's content security policy forbids the string evaluation that {@code waitForFunction} needs, so the line is
     * read again every two seconds.
     *
     * @return the job's line once it says Done, Error or Stopped; the last line seen when the time is up
     */
    private static String waitUntilTheExportEnds(Page page) {
        long deadline = System.currentTimeMillis() + EXPORT_TIMEOUT_MS;
        String line = "";
        while (System.currentTimeMillis() < deadline) {
            Locator lines = page.locator("#export-jobs .import-job-line");
            if (lines.count() > 0) {
                line = lines.first().innerText();
                if (line.startsWith("Done") || line.startsWith("Error") || line.startsWith("Stopped")) return line;
            }
            page.waitForTimeout(2000);
        }
        return line;
    }

    private static boolean batchTabHasDownload(Page page) {
        page.click("#tab-btn-batch");
        return page.locator("#import-jobs a.btn").count() >= 1;
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
            String lastSegment = DIRECTORY.substring(DIRECTORY.lastIndexOf('/') + 1);
            check("the cover is titled with the chosen directory", squash(pageTexts.get(0)).contains(squash(lastSegment)),
                    pageTexts.get(0).strip());
            List<Integer> nearlyEmpty = new ArrayList<>();
            for (int i = 1; i < pageTexts.size(); i++) {
                if (squash(pageTexts.get(i)).length() < 30) nearlyEmpty.add(i + 1);
            }
            check("no page after the cover is empty apart from its footer", nearlyEmpty.isEmpty(), "pages " + nearlyEmpty);

            List<PDOutlineItem> bookmarks = new ArrayList<>();
            collectBookmarks(document.getDocumentCatalog().getDocumentOutline(), bookmarks);
            check("the PDF has bookmarks", bookmarks.size() > 1, "bookmarks " + bookmarks.size());
            if (bookmarks.size() <= 1) return;

            List<ContentsLine> contents = readContentsLines(pageTexts);
            // The first bookmark is Contents itself; the rest are the parts in order, as the contents lines are.
            List<PDOutlineItem> parts = bookmarks.subList(1, bookmarks.size());
            check("the contents have one line per bookmarked part", contents.size() == parts.size(),
                    contents.size() + " lines, " + parts.size() + " bookmarks");
            int wrongNumber = 0;
            int missingHeading = 0;
            for (int i = 0; i < Math.min(contents.size(), parts.size()); i++) {
                int bookmarkPage = document.getPages().indexOf(parts.get(i).findDestinationPage(document)) + 1;
                String heading = squash(parts.get(i).getTitle());
                if (contents.get(i).page() != bookmarkPage) {
                    wrongNumber++;
                    System.out.println("    contents says " + contents.get(i).page() + ", bookmark is on " + bookmarkPage
                            + ": " + parts.get(i).getTitle());
                }
                if (bookmarkPage < 1 || !squash(pageTexts.get(bookmarkPage - 1)).contains(heading)) {
                    missingHeading++;
                    System.out.println("    page " + bookmarkPage + " does not carry its heading: " + parts.get(i).getTitle());
                }
            }
            check("every number in the contents is the page its bookmark points at", wrongNumber == 0, wrongNumber + " differ");
            check("the page every bookmark points at carries that heading", missingHeading == 0, missingHeading + " do not");
            check("the contents are in the order of the pages", pagesAscend(contents), "");
            check("Japanese text is on the pages", pageTexts.stream().anyMatch(t -> t.matches("(?s).*[぀-ヿ一-鿿].*")), "");

            renderPages(document, total);
        }
    }

    private static boolean everyPageHasFooter(List<String> pageTexts) {
        for (int i = 0; i < pageTexts.size(); i++) {
            if (!squash(pageTexts.get(i)).contains((i + 1) + "/" + pageTexts.size())) return false;
        }
        return true;
    }

    private static boolean pagesAscend(List<ContentsLine> contents) {
        for (int i = 1; i < contents.size(); i++) {
            if (contents.get(i).page() < contents.get(i - 1).page()) return false;
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

    /** The lines of the contents page(s): text that ends in a page number. The contents start after the cover. */
    private static List<ContentsLine> readContentsLines(List<String> pageTexts) {
        List<ContentsLine> lines = new ArrayList<>();
        Pattern line = Pattern.compile("^(.*\\S)\\s+(\\d{1,4})\\s*$");
        boolean inContents = false;
        for (String text : pageTexts) {
            if (!inContents && text.contains("Contents")) inContents = true;
            if (!inContents) continue;
            int before = lines.size();
            for (String row : text.split("\\R")) {
                Matcher m = line.matcher(row);
                if (m.matches() && !row.contains(" / ")) lines.add(new ContentsLine(m.group(1), Integer.parseInt(m.group(2))));
            }
            if (lines.size() == before) break;
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
