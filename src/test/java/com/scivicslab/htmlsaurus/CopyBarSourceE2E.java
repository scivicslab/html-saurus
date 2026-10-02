package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.util.List;

/**
 * End-to-end test: does the copy bar hand over each representation of the document it sits on?
 *
 * <p>Against a RUNNING portal, for the page given as the first argument:
 * <ol>
 *   <li><b>the bar is wired</b> — clicking Path puts the document's source path on the clipboard.
 *       Every handler in the copy bar is registered by one block of script that returns early when
 *       the bar is absent, so a silent clipboard means no handler in the bar runs, whatever the
 *       buttons look like;</li>
 *   <li><b>each full-text button opens its representation</b> — Text, Markdown (OpenMath),
 *       Markdown (LaTeX) and HTML each open a tab on {@code /api/source} with their own
 *       {@code format}, and what arrives has the property that distinguishes that format: the
 *       Markdown forms carry the frontmatter the rendered page does not have, HTML carries tags,
 *       and text carries neither.</li>
 * </ol>
 *
 * <p>Requires an already-running portal whose pages were built by the same version, since the
 * handlers live in the generated page:
 * <pre>
 *   PORTAL_URL=http://localhost:28001 \
 *   mvn test-compile exec:java -Dexec.mainClass=com.scivicslab.htmlsaurus.CopyBarSourceE2E \
 *     -Dexec.classpathScope=test \
 *     -Dexec.args="/doc_Base010/ProjectStandard2/AgentBrief_260905_oo01.html"
 * </pre>
 * Exits non-zero if any check fails.
 */
public class CopyBarSourceE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("PORTAL_URL", "http://localhost:28001");
    private static final String DEFAULT_PAGE =
            "/doc_Base010/ProjectStandard2/AgentBrief_260905_oo01.html";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        String pagePath = args.length > 0 ? args[0] : DEFAULT_PAGE;
        String pageUrl = BASE_URL.replaceAll("/+$", "") + pagePath;
        System.out.println("=== Copy bar source E2E: " + pageUrl + " ===");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                    .setPermissions(List.of("clipboard-read", "clipboard-write")));
            Page page = ctx.newPage();
            page.navigate(pageUrl);

            pathButton_clicked_putsTheSourcePathOnTheClipboard(page);

            viewButton_clicked_opensTheTextWithoutTagsOrFrontmatter(page);
            viewButton_clicked_opensTheMarkdownSourceWithItsFrontmatter(page, "view-md-om-btn", "md-om");
            viewButton_clicked_opensTheMarkdownSourceWithItsFrontmatter(page, "view-md-latex-btn", "md-latex");
            viewButton_clicked_opensTheConvertedHtmlWithTags(page);

            ctx.close();
            browser.close();
        }

        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    /**
     * The check that catches a copy bar whose script never attached: the buttons render and look
     * right, yet nothing happens on click.
     */
    private static void pathButton_clicked_putsTheSourcePathOnTheClipboard(Page page) {
        run("Path puts the source path on the clipboard", () -> {
            String dataPath = page.getAttribute("#copy-path-btn", "data-path");
            check(dataPath != null && dataPath.endsWith(".md"),
                    "The Path button must carry the document's source path, got: " + dataPath);
            page.click("#copy-path-btn");
            String clip = (String) page.evaluate("() => navigator.clipboard.readText()");
            check(dataPath.equals(clip),
                    "Clipboard must hold " + dataPath + ", got: " + clip
                    + " (empty means no handler in the copy bar is registered)");
        });
    }

    private static void viewButton_clicked_opensTheTextWithoutTagsOrFrontmatter(Page page) {
        run("Text opens the text, without tags or frontmatter", () -> {
            String body = openAndRead(page, "view-text-btn", "text");
            check(!body.startsWith("---"), "text must not carry the frontmatter");
            check(!body.contains("<p>") && !body.contains("<h2"), "text must not carry tags");
            check(!body.isBlank(), "text must carry the document's words");
        });
    }

    private static void viewButton_clicked_opensTheMarkdownSourceWithItsFrontmatter(
            Page page, String buttonId, String format) {
        run(format + " opens the Markdown source, frontmatter included", () -> {
            String body = openAndRead(page, buttonId, format);
            check(body.startsWith("---"),
                    format + " must begin with the frontmatter, which the rendered page does not have");
            check(body.contains("\n## "), format + " must keep headings as Markdown");
        });
    }

    private static void viewButton_clicked_opensTheConvertedHtmlWithTags(Page page) {
        run("HTML opens the converted body, with tags", () -> {
            String body = openAndRead(page, "view-html-btn", "html");
            check(body.contains("<h2"), "html must carry tags");
            check(!body.startsWith("---"), "html must not carry the frontmatter");
        });
    }

    /** Clicks the button, and returns the text of the tab it opens after checking the format it asked for. */
    private static String openAndRead(Page page, String buttonId, String format) {
        Page popup = page.waitForPopup(() -> page.click("#" + buttonId));
        try {
            popup.waitForLoadState();
            String url = popup.url();
            check(url.contains("/api/source?"), buttonId + " must open /api/source, got: " + url);
            check(url.contains("format=" + format), buttonId + " must ask for format=" + format + ", got: " + url);
            String body = (String) popup.evaluate("() => document.body ? document.body.innerText : ''");
            return body == null ? "" : body;
        } finally {
            popup.close();
        }
    }

    private static void run(String name, Runnable body) {
        try {
            body.run();
            System.out.println("PASS: " + name);
            passed++;
        } catch (AssertionError | RuntimeException e) {
            System.err.println("FAIL: " + name + " — " + e.getMessage());
            failed++;
        }
    }

    private static void check(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }
}
