package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.*;

import java.util.List;

/**
 * E2E test for the portal search forms.
 *
 * <p>Requires an already-running portal:
 * <pre>
 *   java -jar html-saurus.jar &lt;worksDir&gt; --portal-mode --serve --port 8500
 * </pre>
 *
 * <p>Run:
 * <pre>
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.htmlsaurus.PortalSearchE2E \
 *     -Dexec.classpathScope=test
 *
 *   # Override URL:
 *   PORTAL_URL=http://localhost:28008 mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.htmlsaurus.PortalSearchE2E \
 *     -Dexec.classpathScope=test
 * </pre>
 */
public class PortalSearchE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("PORTAL_URL", "http://localhost:8500");

    private static final String KNOWN_KEYWORD = "html-saurus";

    private static int passed = 0;
    private static int failed = 0;

    // ---- Entry point -------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("=== Portal Search E2E: " + BASE_URL + " ===");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));

            runForm1(browser);
            runForm2(browser);
        }

        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ---- Form 1: form.portal-search → GET /search -------------------------

    private static void runForm1(Browser browser) {
        // P-1: portal page has form.portal-search with input[name=q]
        withPage("P-1: portal page has form.portal-search and input[name=q]", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            check(page.querySelector("form.portal-search") != null,
                    "form.portal-search not found");
            check(page.querySelector("form.portal-search input[name=q]") != null,
                    "input[name=q] not found inside form.portal-search");
            check(page.querySelector("form.portal-search button[type=submit]") != null,
                    "submit button not found inside form.portal-search");
        });

        // P-2: form has method=get and action=/search
        withPage("P-2: form has method=get, action ends with /search", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            ElementHandle form = page.querySelector("form.portal-search");
            check(form != null, "form.portal-search not found");
            String method = form.getAttribute("method");
            check(method != null && method.equalsIgnoreCase("get"),
                    "form method must be 'get', got: " + method);
            String action = form.getAttribute("action");
            check(action != null && action.endsWith("/search"),
                    "form action must end with /search, got: " + action);
        });

        // P-3: submitting form via Enter navigates to /search?q=<keyword>
        withPage("P-3: submitting form navigates to /search?q=<keyword>", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.fill("form.portal-search input[name=q]", KNOWN_KEYWORD);
            page.press("form.portal-search input[name=q]", "Enter");
            page.waitForLoadState();
            String url = page.url();
            check(url.contains("/search"), "After submit, URL must contain /search, got: " + url);
            check(url.contains("q="), "After submit, URL must contain q=, got: " + url);
        });

        // P-4: /search?q=<keyword> renders a <body>
        withPage("P-4: search result page renders <body>", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            check(page.querySelector("body") != null, "<body> not found on search result page");
        });

        // P-5: known keyword returns at least one .result
        withPage("P-5: known keyword returns at least one .result", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            check(page.querySelector(".result") != null,
                    "No .result elements found for keyword: " + KNOWN_KEYWORD);
        });

        // P-6: unknown keyword shows "No results found", no .result
        // Single token (no underscores) that is guaranteed absent from any indexed document.
        withPage("P-6: unknown keyword shows 'No results found'", browser, page -> {
            page.navigate(BASE_URL + "/search?q=zzznomatch9876543xyzabc");
            page.waitForLoadState();
            check(page.querySelector(".result") == null,
                    "Unknown keyword must produce no .result elements");
            check(page.content().contains("No results found"),
                    "Unknown keyword must show 'No results found'");
        });

        // P-7: empty ?q= shows "Please enter a search query"
        withPage("P-7: empty query shows 'Please enter a search query'", browser, page -> {
            page.navigate(BASE_URL + "/search?q=");
            page.waitForLoadState();
            check(page.content().contains("Please enter a search query"),
                    "Empty query page must show 'Please enter a search query'");
        });

        // P-8: results page header input[name=q] retains keyword
        withPage("P-8: results page header input retains submitted keyword", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            check(page.querySelector("header form input[name=q]") != null,
                    "header form input[name=q] not found on results page");
            String value = (String) page.evaluate(
                    "() => document.querySelector('header form input[name=q]').value");
            check(KNOWN_KEYWORD.equals(value),
                    "header input[name=q] must retain '" + KNOWN_KEYWORD + "', got: " + value);
        });

        // P-9: result links have target=_blank
        withPage("P-9: result links have target=_blank", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            ElementHandle link = page.querySelector("a.result");
            check(link != null, "No a.result link found");
            check("_blank".equals(link.getAttribute("target")),
                    "a.result must have target=\"_blank\", got: " + link.getAttribute("target"));
        });

        // P-10: result href starts with /<projectName>/
        withPage("P-10: result href starts with /<project>/", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            ElementHandle link = page.querySelector("a.result");
            check(link != null, "No a.result link found");
            String href = link.getAttribute("href");
            check(href != null && href.startsWith("/") && href.indexOf('/', 1) > 0,
                    "a.result href must be /<project>/..., got: " + href);
        });

        // P-11: .result-count shows non-blank text
        withPage("P-11: .result-count shows non-blank text", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            ElementHandle el = page.querySelector(".result-count");
            check(el != null, ".result-count element not found");
            String text = el.textContent();
            check(text != null && !text.isBlank(), ".result-count must contain non-blank text");
        });

        // P-12: result structure has .result-title, .result-project, .result-summary
        withPage("P-12: result has .result-title, .result-project, .result-summary", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            check(page.querySelector(".result .result-title") != null,
                    ".result-title not found inside .result");
            check(page.querySelector(".result .result-project") != null,
                    ".result-project not found inside .result");
            check(page.querySelector(".result .result-summary") != null,
                    ".result-summary not found inside .result");
        });

        // P-13 through P-16: special chars must not crash the server
        for (String[] tc : new String[][]{
                {"P-13", "100%25",     "percent"},
                {"P-14", "html+saurus","plus"},
                {"P-15", "%28unclosed","open-paren"},
                {"P-16", "field%3Avalue", "colon"},
        }) {
            String id = tc[0], q = tc[1], label = tc[2];
            withPage(id + ": special char " + label + " does not crash server", browser, page -> {
                Response resp = page.navigate(BASE_URL + "/search?q=" + q);
                check(resp != null && resp.status() < 500,
                        "/search?q=" + q + " must not return 5xx, got: "
                        + (resp != null ? resp.status() : "null"));
                check(page.querySelector("body") != null, "<body> must be present");
            });
        }
    }

    // ---- Form 2: #find-related-input → POST /api/find-related -------------

    private static void runForm2(Browser browser) {
        // FR-1: find-related input and button exist
        withPage("FR-1: #find-related-input and #find-related-btn exist", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            check(page.querySelector("#find-related-input") != null,
                    "#find-related-input not found on portal page");
            check(page.querySelector("#find-related-btn") != null,
                    "#find-related-btn not found on portal page");
        });

        // FR-2: clicking button with empty input shows validation message
        withPage("FR-2: empty input shows 'Please enter some text.'", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.click("#find-related-btn");
            page.waitForLoadState();
            check(page.content().contains("Please enter some text."),
                    "Empty find-related must show 'Please enter some text.'");
        });

        // FR-3: valid text input returns results in #find-related-list
        withPage("FR-3: valid text returns results in #find-related-list", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.fill("#find-related-input", KNOWN_KEYWORD);
            page.click("#find-related-btn");
            page.waitForSelector("#find-related-list a, #find-related-list .find-related-item",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
            List<ElementHandle> items = page.querySelectorAll(
                    "#find-related-list a, #find-related-list .find-related-item");
            check(!items.isEmpty(), "#find-related-list must contain at least one result");
        });

        // FR-4: results have title and project name
        withPage("FR-4: results have title and project name", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.fill("#find-related-input", KNOWN_KEYWORD);
            page.click("#find-related-btn");
            page.waitForSelector("#find-related-list a, #find-related-list .find-related-item",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
            ElementHandle first = page.querySelector(
                    "#find-related-list a, #find-related-list .find-related-item");
            check(first != null, "#find-related-list must have at least one item");
            String text = first.textContent();
            check(text != null && !text.isBlank(),
                    "First find-related result must contain non-blank text");
        });

        // FR-5: result links have target=_blank
        withPage("FR-5: find-related result links have target=_blank", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.fill("#find-related-input", KNOWN_KEYWORD);
            page.click("#find-related-btn");
            page.waitForSelector("#find-related-list a",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
            ElementHandle link = page.querySelector("#find-related-list a");
            check(link != null, "No <a> link found in #find-related-list");
            check("_blank".equals(link.getAttribute("target")),
                    "#find-related-list a must have target=\"_blank\", got: "
                    + link.getAttribute("target"));
        });
    }

    // ---- Helpers -----------------------------------------------------------

    @FunctionalInterface
    interface PageTest {
        void run(Page page);
    }

    private static void withPage(String name, Browser browser, PageTest test) {
        try (Page page = browser.newPage()) {
            test.run(page);
            System.out.println("PASS: " + name);
            passed++;
        } catch (AssertionError e) {
            System.err.println("FAIL: " + name + " — " + e.getMessage());
            failed++;
        } catch (Exception e) {
            System.err.println("FAIL: " + name + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
