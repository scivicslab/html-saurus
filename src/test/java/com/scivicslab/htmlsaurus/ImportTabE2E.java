package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * E2E test for the portal sidebar's Import tab (top-level tab, alongside Projects — Search lives
 * inside the Projects tab). Import has a type dropdown (#import-type: PDF / Word, more types
 * expected later) rather than sub-tabs, and a server-side file path input, not a browser upload.
 *
 * <p>Requires an already-running dev-mode portal with at least one project named {@code proj1}
 * that has a {@code docs/} directory:
 * <pre>
 *   java -jar html-saurus.jar &lt;worksDir&gt; --portal-mode --serve --port 8600
 * </pre>
 *
 * <p>Run:
 * <pre>
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.htmlsaurus.ImportTabE2E \
 *     -Dexec.classpathScope=test
 *
 *   # Override URL:
 *   PORTAL_URL=http://localhost:28008 mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.htmlsaurus.ImportTabE2E \
 *     -Dexec.classpathScope=test
 * </pre>
 */
public class ImportTabE2E {

    private static final String BASE_URL = System.getenv().getOrDefault("PORTAL_URL", "http://localhost:8600");

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            runSubTabSwitching(browser);
            runWordImport(browser);
            runPdfImportWithImage(browser);
        }
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void runSubTabSwitching(Browser browser) {
        withPage("T-0: Projects tab (with Search) visible by default, Import tab hidden", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            check(page.isVisible("#tab-projects"), "#tab-projects must be visible on load");
            check(page.isVisible("#search-input"), "Search must be inside the Projects tab");
            check(!page.isVisible("#tab-import"), "#tab-import must be hidden on load");
        });

        withPage("T-1: clicking Import tab shows it (PDF sub-panel visible, Word hidden)", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.click("#tab-btn-import");
            check(page.isVisible("#tab-import"), "#tab-import must be visible after click");
            check(!page.isVisible("#tab-projects"), "#tab-projects must be hidden after click");
            check(page.isVisible("#import-panel-pdf"), "#import-panel-pdf must be visible by default within Import");
            check(!page.isVisible("#import-panel-word"), "#import-panel-word must be hidden by default within Import");
        });

        withPage("T-2: selecting Word in the type dropdown shows it and hides PDF", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.click("#tab-btn-import");
            page.selectOption("#import-type", "word");
            check(page.isVisible("#import-panel-word"), "#import-panel-word must be visible after selecting Word");
            check(!page.isVisible("#import-panel-pdf"), "#import-panel-pdf must be hidden after selecting Word");
        });

        withPage("T-3: project dropdowns are populated from the project list", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.click("#tab-btn-import");
            int pdfCount = ((Number) page.evalOnSelector("#import-pdf-project", "el => el.options.length")).intValue();
            check(pdfCount > 0, "#import-pdf-project must have at least one option");
            page.selectOption("#import-type", "word");
            int wordCount = ((Number) page.evalOnSelector("#import-word-project", "el => el.options.length")).intValue();
            check(wordCount > 0, "#import-word-project must have at least one option");
        });
    }

    private static void runWordImport(Browser browser) {
        withPage("W-1: importing a real .docx by server path writes a Markdown file and reports success", browser, page -> {
            try {
                Path docx = Files.createTempFile("e2e-fixture-", ".docx");
                Files.write(docx, buildFixtureDocx());
                page.navigate(BASE_URL + "/");
                page.waitForLoadState();
                page.click("#tab-btn-import");
                page.selectOption("#import-type", "word");
                page.selectOption("#import-word-project", "proj1");
                page.fill("#import-word-dest", "e2e-import-test");
                page.fill("#import-word-title", "E2E Import Test");
                page.fill("#import-word-path", docx.toAbsolutePath().toString());
                page.click("#import-word-start");
                // CSP has no 'unsafe-eval', so page.waitForFunction() (which evals a predicate in the
                // page) is unusable here — poll from the Java side instead.
                String progressText = "";
                for (int i = 0; i < 100; i++) {
                    progressText = page.textContent("#import-progress");
                    if (progressText.contains("Done:") || progressText.contains("Error")) break;
                    page.waitForTimeout(100);
                }
                check(progressText.contains("Done:"), "progress must report success, got: " + progressText);
            } catch (Exception e) {
                throw new AssertionError("setup/build failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Real regression test for the bug reported against html-saurus: a Marker-imported PDF's
     * figures were referenced in the Markdown ({@code ![](...)}) but the image files were never
     * written, because nothing extracted Marker's {@code images} response field. Drives the actual
     * browser UI (not a raw HTTP call) end to end against the real Marker server, and asserts the
     * completion message reports at least one extracted image — the same message a human reads.
     * Requires the real Marker OCR server (see {@code MarkerOcrClient.DEFAULT_BASE_URL}, or
     * {@code MARKER_SERVER_URL}) to be reachable from wherever this test runs.
     */
    private static void runPdfImportWithImage(Browser browser) {
        withPage("P-1: importing a PDF with a figure via Marker extracts and reports the image", browser, page -> {
            try {
                Path pdf = Files.createTempFile("e2e-fixture-", ".pdf");
                Files.write(pdf, buildFixturePdfWithImage());
                page.navigate(BASE_URL + "/");
                page.waitForLoadState();
                page.click("#tab-btn-import");
                // PDF is the type dropdown's default option — no need to select it explicitly.
                page.selectOption("#import-pdf-project", "proj1");
                page.fill("#import-pdf-dest", "e2e-import-test-pdf");
                page.fill("#import-pdf-title", "E2E PDF Import Test");
                page.selectOption("#import-pdf-backend", "marker");
                page.fill("#import-pdf-pages-per-file", "1");
                page.fill("#import-pdf-path", pdf.toAbsolutePath().toString());
                page.click("#import-pdf-start");
                String progressText = "";
                for (int i = 0; i < 300; i++) {
                    progressText = page.textContent("#import-progress");
                    if (progressText.contains("Done:") || progressText.contains("Error")) break;
                    page.waitForTimeout(500);
                }
                check(progressText.contains("Done:"), "progress must report success, got: " + progressText);
                check(!progressText.contains("0 image(s) extracted"),
                        "the fixture PDF has one figure — 0 images means Marker's images field is not "
                        + "being extracted again, got: " + progressText);
            } catch (Exception e) {
                throw new AssertionError("setup/build failed: " + e.getMessage(), e);
            }
        });
    }

    /** A single-page PDF with a heading, an embedded raster figure, and a caption paragraph —
     *  enough visual structure for Marker to detect and return a "Picture"/"Figure" block. */
    private static byte[] buildFixturePdfWithImage() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.LETTER);
            doc.addPage(page);

            var img = new java.awt.image.BufferedImage(300, 200, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(java.awt.Color.BLUE);
            g.fillRect(0, 0, 300, 200);
            g.setColor(java.awt.Color.YELLOW);
            g.fillOval(50, 50, 200, 100);
            g.dispose();
            var pdImage = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, img);

            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                cs.newLineAtOffset(72, 700);
                cs.showText("Figure 1: Test chart caption");
                cs.endText();
                cs.drawImage(pdImage, 72, 450, 300, 200);
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(72, 420);
                cs.showText("Body text describing the figure above in a normal paragraph.");
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildFixtureDocx() throws Exception {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            var p = doc.createParagraph();
            p.createRun().setText("E2E fixture paragraph.");
            doc.write(out);
            return out.toByteArray();
        }
    }

    // ---- Infrastructure (mirrors PortalSearchE2E.java) -----------------------------------------

    private static void withPage(String testName, Browser browser, java.util.function.Consumer<Page> test) {
        BrowserContext ctx = browser.newContext();
        Page page = ctx.newPage();
        try {
            test.accept(page);
            System.out.println("PASS: " + testName);
            passed++;
        } catch (AssertionError e) {
            System.err.println("FAIL: " + testName + " — " + e.getMessage());
            failed++;
        } finally {
            page.close();
            ctx.close();
        }
    }

    private static void check(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }
}
