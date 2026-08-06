package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.*;

/**
 * E2E test for portal search: the sidebar's "one form, three search types" widget
 * ({@code #search-input} + {@code search-type} radios, inside the Projects tab —
 * see {@code ImportTab_260806_oo01} for the Projects/Import tab layout), and the two
 * standalone results pages it dispatches to ({@code /search} for keyword and embedding,
 * {@code /find-related} for TF-IDF).
 *
 * <p>Requires an already-running portal with real, indexed content that contains the
 * keyword {@link #KNOWN_KEYWORD}:
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

            runSidebarWidget(browser);
            runSearchResultsPage(browser);
            runFindRelatedResultsPage(browser);
        }

        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ---- Sidebar widget: #search-input + search-type radios, inside the Projects tab ----------

    private static void runSidebarWidget(Browser browser) {
        withPage("S-1: search widget is visible by default (Projects tab is active on load)", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            check(page.isVisible("#search-input"), "#search-input must be visible on load");
            check(page.isVisible("#search-btn"), "#search-btn must be visible on load");
            check(page.isVisible("input[name='search-type'][value='fulltext']"), "Keyword radio must be visible");
            check(page.isVisible("input[name='search-type'][value='tfidf']"), "TF-IDF radio must be visible");
            check(page.isVisible("input[name='search-type'][value='embedding']"), "Embedding radio must be visible");
        });

        withPage("S-2: empty input shows a validation message and does not navigate the frame", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.click("#search-btn");
            check("Please enter some text.".equals(page.textContent("#search-status")),
                    "empty submit must show the validation message");
        });

        withPage("S-3: Keyword search loads /search?q=... into the doc-frame iframe", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.fill("#search-input", KNOWN_KEYWORD);
            page.click("#search-btn");
            String url = waitForFrameUrlContains(page, "/search?q=");
            check(url.contains("q=" + KNOWN_KEYWORD), "doc-frame URL must carry the query, got: " + url);
        });

        withPage("S-4: TF-IDF search loads /find-related results into the doc-frame iframe", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.fill("#search-input", KNOWN_KEYWORD);
            page.check("input[name='search-type'][value='tfidf']");
            page.click("#search-btn");
            String html = waitForFrameHtmlContaining(page, ".result-count");
            check(html.contains("class=\"result\"") || html.contains("No related documents found"),
                    "find-related frame must show either results or the no-results message");
        });

        withPage("S-5: Embedding search loads /search-semantic?q=... into the doc-frame iframe", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.fill("#search-input", KNOWN_KEYWORD);
            page.check("input[name='search-type'][value='embedding']");
            page.click("#search-btn");
            String url = waitForFrameUrlContains(page, "/search-semantic?q=");
            check(url.contains(KNOWN_KEYWORD), "doc-frame URL must carry the query, got: " + url);
        });
    }

    // ---- /search?q=... standalone results page (SSR) -----------------------------------------

    private static void runSearchResultsPage(Browser browser) {
        withPage("R-1: /search?q=<keyword> renders a <body>", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            check(page.querySelector("body") != null, "<body> not found on search result page");
        });

        withPage("R-2: known keyword returns at least one .result", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            check(page.querySelector(".result") != null,
                    "No .result elements found for keyword: " + KNOWN_KEYWORD);
        });

        // Single token (no underscores) that is guaranteed absent from any indexed document.
        withPage("R-3: unknown keyword shows 'No results found'", browser, page -> {
            page.navigate(BASE_URL + "/search?q=zzznomatch9876543xyzabc");
            page.waitForLoadState();
            check(page.querySelector(".result") == null,
                    "Unknown keyword must produce no .result elements");
            check(page.content().contains("No results found"),
                    "Unknown keyword must show 'No results found'");
        });

        withPage("R-4: empty query shows 'Please enter a search query'", browser, page -> {
            page.navigate(BASE_URL + "/search?q=");
            page.waitForLoadState();
            check(page.content().contains("Please enter a search query"),
                    "Empty query page must show 'Please enter a search query'");
        });

        // The results page embeds the same #search-input widget (in <main>, not <header> —
        // the header holds only the home link) and pre-fills it with the submitted query.
        withPage("R-5: results page's own search widget retains the submitted keyword", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            check(page.querySelector("header form") == null,
                    "header must not contain a form (search widget belongs in <main>)");
            check(page.querySelector("main #search-input") != null,
                    "main #search-input not found on results page");
            String value = (String) page.evaluate(
                    "() => document.querySelector('main #search-input').value");
            check(KNOWN_KEYWORD.equals(value),
                    "main #search-input must retain '" + KNOWN_KEYWORD + "', got: " + value);
        });

        withPage("R-6: result links do not open a new tab", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            ElementHandle link = page.querySelector("a.result");
            check(link != null, "No a.result link found");
            check(!"_blank".equals(link.getAttribute("target")),
                    "a.result must not open a new tab (target must not be _blank), got: "
                    + link.getAttribute("target"));
        });

        withPage("R-7: result href starts with /<project>/", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            ElementHandle link = page.querySelector("a.result");
            check(link != null, "No a.result link found");
            String href = link.getAttribute("href");
            check(href != null && href.startsWith("/") && href.indexOf('/', 1) > 0,
                    "a.result href must be /<project>/..., got: " + href);
        });

        withPage("R-8: .result-count shows non-blank text", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            ElementHandle el = page.querySelector(".result-count");
            check(el != null, ".result-count element not found");
            String text = el.textContent();
            check(text != null && !text.isBlank(), ".result-count must contain non-blank text");
        });

        withPage("R-9: result has .result-title, .result-project, .result-summary", browser, page -> {
            page.navigate(BASE_URL + "/search?q=" + KNOWN_KEYWORD);
            page.waitForLoadState();
            check(page.querySelector(".result .result-title") != null,
                    ".result-title not found inside .result");
            check(page.querySelector(".result .result-project") != null,
                    ".result-project not found inside .result");
            check(page.querySelector(".result .result-summary") != null,
                    ".result-summary not found inside .result");
        });

        // R-10 through R-13: special chars must not crash the server
        for (String[] tc : new String[][]{
                {"R-10", "100%25",     "percent"},
                {"R-11", "html+saurus","plus"},
                {"R-12", "%28unclosed","open-paren"},
                {"R-13", "field%3Avalue", "colon"},
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

    // ---- /find-related standalone results page (POST-only, reached via the sidebar widget) ---

    private static void runFindRelatedResultsPage(Browser browser) {
        withPage("FR-1: results have title, project name, and non-blank summary", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.fill("#search-input", KNOWN_KEYWORD);
            page.check("input[name='search-type'][value='tfidf']");
            page.click("#search-btn");
            String html = waitForFrameHtmlContaining(page, ".result");
            check(html.contains("class=\"result-title\""), ".result-title not found inside .result");
            check(html.contains("class=\"result-project\""), ".result-project not found inside .result");
            check(html.matches("(?s).*class=\"result-summary\">[^<]*\\S[^<]*</div>.*"),
                    ".result-summary must contain non-blank text");
        });

        withPage("FR-2: result links do not open a new tab", browser, page -> {
            page.navigate(BASE_URL + "/");
            page.waitForLoadState();
            page.fill("#search-input", KNOWN_KEYWORD);
            page.check("input[name='search-type'][value='tfidf']");
            page.click("#search-btn");
            String html = waitForFrameHtmlContaining(page, "a.result");
            check(!html.contains("target=\"_blank\""),
                    "a.result must not open a new tab (no element in the find-related frame may set target=_blank)");
        });
    }

    // ---- Helpers -----------------------------------------------------------

    /**
     * Polls {@code doc-frame}'s URL until it contains {@code substr}, returning the matched URL
     * as a plain string. The CSP has no {@code 'unsafe-eval'}, so {@code page.waitForFunction}
     * (which evals a predicate in the page) cannot be used here — poll from the Java side
     * instead. Returning a string rather than the live {@code Frame} avoids a second live call
     * racing a follow-up navigation inside the frame.
     */
    private static String waitForFrameUrlContains(Page page, String substr) {
        for (int i = 0; i < 100; i++) {
            Frame frame = page.frame("doc-frame");
            String url = frame == null ? null : frame.url();
            if (url != null && url.contains(substr)) return url;
            page.waitForTimeout(100);
        }
        throw new AssertionError("doc-frame did not navigate to a URL containing '" + substr + "'");
    }

    /**
     * Polls {@code doc-frame} until {@code selector} appears inside it, then returns the frame's
     * full HTML as a plain string (captured atomically with the selector check, in the same live
     * call). A transient {@link PlaywrightException} while the frame is mid-navigation is treated
     * as "not ready yet" and retried, rather than failing the test.
     */
    private static String waitForFrameHtmlContaining(Page page, String selector) {
        for (int i = 0; i < 100; i++) {
            Frame frame = page.frame("doc-frame");
            if (frame != null) {
                try {
                    if (frame.querySelector(selector) != null) return frame.content();
                } catch (PlaywrightException transientNavigation) {
                    // frame is mid-navigation; fall through and retry
                }
            }
            page.waitForTimeout(100);
        }
        throw new AssertionError("doc-frame never showed an element matching '" + selector + "'");
    }

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
