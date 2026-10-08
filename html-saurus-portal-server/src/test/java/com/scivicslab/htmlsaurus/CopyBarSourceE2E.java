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
 *   <li><b>the bar is wired</b> — choosing "Copy path" in the format list puts the document's
 *       source path on the clipboard. Every handler in the copy bar is registered by one block of
 *       script that returns early when the bar is absent, so a silent clipboard means no handler
 *       in the bar runs, whatever the controls look like;</li>
 *   <li><b>each full-text entry opens its representation</b> — Text, Markdown (OpenMath),
 *       Markdown (LaTeX), HTML and, when the list offers it, Lisp each open a tab on
 *       {@code /api/source} with their own {@code format}, and what arrives has the property that
 *       distinguishes that format: the Markdown forms carry the frontmatter the rendered page does
 *       not have, HTML carries tags, text carries neither, and Lisp begins with the rule file's
 *       package line.</li>
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

            copyPath_chosen_putsTheSourcePathOnTheClipboard(page);

            entry_chosen_opensTheTextWithoutTagsOrFrontmatter(page);
            entry_chosen_opensTheMarkdownSourceWithItsFrontmatter(page, "md-om");
            entry_chosen_opensTheMarkdownSourceWithItsFrontmatter(page, "md-latex");
            entry_chosen_opensTheConvertedHtmlWithTags(page);
            lispEntry_whenOffered_opensTheRuleFile(page);

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
    private static void copyPath_chosen_putsTheSourcePathOnTheClipboard(Page page) {
        run("Copy path puts the source path on the clipboard", () -> {
            String dataPath = page.getAttribute("#view-format", "data-path");
            check(dataPath != null && dataPath.endsWith(".md"),
                    "The format list must carry the document's source path, got: " + dataPath);
            page.selectOption("#view-format", "copy-path");
            String clip = (String) page.evaluate("() => navigator.clipboard.readText()");
            check(dataPath.equals(clip),
                    "Clipboard must hold " + dataPath + ", got: " + clip
                    + " (empty means no handler in the copy bar is registered)");
        });
    }

    private static void entry_chosen_opensTheTextWithoutTagsOrFrontmatter(Page page) {
        run("Text opens the text, without tags or frontmatter", () -> {
            String body = openAndRead(page, "text");
            check(!body.startsWith("---"), "text must not carry the frontmatter");
            check(!body.contains("<p>") && !body.contains("<h2"), "text must not carry tags");
            check(!body.isBlank(), "text must carry the document's words");
        });
    }

    private static void entry_chosen_opensTheMarkdownSourceWithItsFrontmatter(Page page, String format) {
        run(format + " opens the Markdown source, frontmatter included", () -> {
            String body = openAndRead(page, format);
            check(body.startsWith("---"),
                    format + " must begin with the frontmatter, which the rendered page does not have");
            check(body.contains("\n## "), format + " must keep headings as Markdown");
        });
    }

    private static void entry_chosen_opensTheConvertedHtmlWithTags(Page page) {
        run("HTML opens the converted body, with tags", () -> {
            String body = openAndRead(page, "html");
            check(body.contains("<h2"), "html must carry tags");
            check(!body.startsWith("---"), "html must not carry the frontmatter");
        });
    }

    /** Only pages whose Markdown has a rule file beside it offer the entry; elsewhere the check is skipped. */
    private static void lispEntry_whenOffered_opensTheRuleFile(Page page) {
        if (page.querySelector("#view-format option[value=lisp]") == null) {
            System.out.println("SKIP: Lisp entry (this page has no rule file beside its Markdown)");
            return;
        }
        run("Lisp opens the rule file", () -> {
            String body = openAndRead(page, "lisp");
            check(body.startsWith("(in-package :rst)"), "lisp must begin with the rule file's package line");
        });
    }

    /** Chooses the entry, and returns the text of the tab it opens after checking the format it asked for. */
    private static String openAndRead(Page page, String format) {
        Page popup = page.waitForPopup(() -> page.selectOption("#view-format", format));
        try {
            popup.waitForLoadState();
            String url = popup.url();
            check(url.contains("/api/source?"), format + " must open /api/source, got: " + url);
            check(url.contains("format=" + format), "the list must ask for format=" + format + ", got: " + url);
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
