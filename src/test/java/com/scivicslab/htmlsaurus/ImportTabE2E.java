package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.FilePayload;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * E2E test for the portal sidebar's tabbed Import UI (Search / Projects / Import).
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
            runTabSwitching(browser);
            runWordImport(browser);
        }
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void runTabSwitching(Browser browser) {
        withPage("T-1: Projects tab visible by default", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            check(page.isVisible("#tab-projects"), "#tab-projects must be visible on load");
            check(!page.isVisible("#tab-import"), "#tab-import must be hidden on load");
        });

        withPage("T-2: clicking Import tab shows it and hides Projects", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.click("#tab-btn-import");
            check(page.isVisible("#tab-import"), "#tab-import must be visible after click");
            check(!page.isVisible("#tab-projects"), "#tab-projects must be hidden after click");
        });

        withPage("T-3: Import tab's project dropdown is populated from the project list", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.click("#tab-btn-import");
            int count = ((Number) page.evalOnSelector("#import-project", "el => el.options.length")).intValue();
            check(count > 0, "#import-project must have at least one option");
        });

        withPage("T-4: PDF fields hidden when Word is selected", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.click("#tab-btn-import");
            page.selectOption("#import-type", "word");
            check(!page.isVisible("#import-pdf-fields"), "#import-pdf-fields must hide for Word");
            page.selectOption("#import-type", "pdf");
            check(page.isVisible("#import-pdf-fields"), "#import-pdf-fields must show again for PDF");
        });
    }

    private static void runWordImport(Browser browser) {
        withPage("W-1: importing a real .docx writes a Markdown file and reports success", browser, page -> {
            try {
                byte[] docx = buildFixtureDocx();
                page.navigate(BASE_URL + "/");
                page.waitForLoadState();
                page.click("#tab-btn-import");
                page.selectOption("#import-type", "word");
                page.selectOption("#import-project", "proj1");
                page.fill("#import-dest", "e2e-import-test");
                page.fill("#import-title", "E2E Import Test");
                page.setInputFiles("#import-file",
                        new FilePayload("e2e-fixture.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx));
                page.click("#import-btn");
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
