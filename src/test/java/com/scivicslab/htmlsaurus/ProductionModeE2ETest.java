package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for production mode using Playwright Java.
 *
 * <p>Uses {@code nigsc_homepage2} as real test data.  The docs tree is read from the
 * on-disk project; HTML output and the Lucene search index are written to a temporary
 * directory so the source project is never modified.
 *
 * <p>Specs verified:
 * <ul>
 *   <li>DocPageUrl_260401_oo01       – clean URL rules, numeric-prefix stripping, Pattern 1/2</li>
 *   <li>HtmlSaurusCliOptions_260401_oo01 – production mode hides Rebuild button, Theme
 *       selector, and copy bar</li>
 *   <li>DocPageSidebar_260401_oo01   – Pattern 2 category header is a clickable {@code <a>};
 *       same-name .md does NOT appear as child</li>
 *   <li>HtmlSaurusSearchIndex_260401_oo01 – search box present; search API returns results</li>
 * </ul>
 *
 * <p><b>Project under test</b>
 * <pre>
 * nigsc_homepage2/docs/
 *   guides/
 *     010_top_page/010_top_page.md                 → /guides/top_page/          (Pattern 1)
 *     020_security_policy/020_security_policy.md   → /guides/security_policy/   (Pattern 2)
 *       010_ISMS_Certificate/ (subdir)
 *     030_overview/030_overview.md                  → /guides/overview/          (Pattern 1)
 *     050_software/050_software.md                  → /guides/software/          (Pattern 2)
 *       010_JobScheduler/ ... 050_DevelopmentEnvironment/ (subdirs)
 *       software_update_info.md                     → /guides/software/software_update_info/
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Production mode E2E tests (nigsc_homepage2)")
class ProductionModeE2ETest {

    /** Real docs source – never written to. */
    private static final Path NIGSC_DOCS = Path.of("/home/devteam/works/nigsc_homepage2/docs");

    private Path outDir;
    private HttpServer server;
    private int port;
    private Playwright playwright;
    private Browser browser;
    private Page page;

    // ---- Setup / teardown ------------------------------------------------

    @BeforeAll
    void startServer() throws Exception {
        outDir = Files.createTempDirectory("html-saurus-e2e-prod-");

        // Build all locales (ja default + en alternate) in production mode
        Main.build(NIGSC_DOCS, outDir, true);

        // Index default locale only (ja)
        Path indexDir = outDir.resolve("search-index");
        Main.reindex(NIGSC_DOCS, indexDir, "ja", true);

        SearchServer ss = new SearchServer(outDir, indexDir, 0, () -> {}, true);
        server = ss.start();
        port = server.getAddress().getPort();

        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    void stopServer() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void openPage() {
        page = browser.newPage();
    }

    @AfterEach
    void closePage() {
        if (page != null) page.close();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ---- A. URL structure ------------------------------------------------

    @Nested
    @DisplayName("A. URL structure (DocPageUrl_260401_oo01)")
    class UrlStructure {

        @Test
        @DisplayName("A-1: root / serves content without 404")
        void root_servesContent() {
            Response resp = page.navigate(url("/"));
            // The initial response is the meta-refresh redirect page (status 200).
            // After Playwright follows the redirect, page.url() changes to the first doc page.
            assertNotEquals(404, resp.status(), "Root / must not return 404");
            // Wait for any ongoing navigation (meta-refresh) to settle before reading content
            page.waitForLoadState();
            String currentUrl = page.url();
            assertFalse(currentUrl.endsWith("/index.html"),
                    "After meta-refresh, URL must be a clean content page URL, not index.html");
        }

        @Test
        @DisplayName("A-2: Pattern 1 — /guides/top_page/ returns 200")
        void pattern1_topPage_returns200() {
            Response resp = page.navigate(url("/guides/top_page/"));
            assertEquals(200, resp.status(),
                    "GET /guides/top_page/ must return 200 " +
                    "(Pattern 1: 010_top_page/010_top_page.md collapses to parent dir URL)");
        }

        @Test
        @DisplayName("A-3: Pattern 1 — /guides/overview/ returns 200")
        void pattern1_overview_returns200() {
            Response resp = page.navigate(url("/guides/overview/"));
            assertEquals(200, resp.status(),
                    "GET /guides/overview/ must return 200 " +
                    "(Pattern 1: 030_overview/030_overview.md collapses to parent dir URL)");
        }

        @Test
        @DisplayName("A-4: Pattern 2 — /guides/security_policy/ returns 200")
        void pattern2_securityPolicy_returns200() {
            Response resp = page.navigate(url("/guides/security_policy/"));
            assertEquals(200, resp.status(),
                    "GET /guides/security_policy/ must return 200 " +
                    "(Pattern 2: 020_security_policy.md + subdir 010_ISMS_Certificate)");
        }

        @Test
        @DisplayName("A-5: Pattern 2 — /guides/software/ returns 200")
        void pattern2_software_returns200() {
            Response resp = page.navigate(url("/guides/software/"));
            assertEquals(200, resp.status(),
                    "GET /guides/software/ must return 200 " +
                    "(Pattern 2: 050_software.md + 5 subdirs)");
        }

        @Test
        @DisplayName("A-6: flat nested — /guides/software/software_update_info/ returns 200")
        void flatNested_softwareUpdateInfo_returns200() {
            Response resp = page.navigate(url("/guides/software/software_update_info/"));
            assertEquals(200, resp.status(),
                    "GET /guides/software/software_update_info/ must return 200 " +
                    "(flat nested file: 050_software/software_update_info.md)");
        }

        @Test
        @DisplayName("A-7: Pattern 2 deep path /guides/software/software/ must NOT exist (404)")
        void pattern2_deepPath_returns404() {
            Response resp = page.navigate(url("/guides/software/software/"));
            assertEquals(404, resp.status(),
                    "GET /guides/software/software/ must return 404: " +
                    "Pattern 2 same-name .md collapses to parent dir URL — " +
                    "no extra path segment for the file name itself");
        }

        @Test
        @DisplayName("A-8: numeric prefix stripped — 010_ absent from URL")
        void numericPrefix_strippedFromUrl() {
            // 010_top_page must be accessible as /guides/top_page/, not /guides/010_top_page/
            Response withPrefix = page.navigate(url("/guides/010_top_page/"));
            assertNotEquals(200, withPrefix.status(),
                    "URL with numeric prefix /guides/010_top_page/ must NOT return 200");
        }
    }

    // ---- B. Production mode — dev controls absent ------------------------

    @Nested
    @DisplayName("B. Production mode: dev controls absent (HtmlSaurusCliOptions_260401_oo01)")
    class NoDevControls {

        @Test
        @DisplayName("B-1: no Rebuild button (#rebuild-btn)")
        void no_rebuildButton() {
            page.navigate(url("/guides/top_page/"));
            assertNull(page.querySelector("#rebuild-btn"),
                    "Production mode must not render <button id=\"rebuild-btn\">");
        }

        @Test
        @DisplayName("B-2: no Theme selector (#theme-sel)")
        void no_themeSelector() {
            page.navigate(url("/guides/top_page/"));
            assertNull(page.querySelector("#theme-sel"),
                    "Production mode must not render <select id=\"theme-sel\">");
        }

        @Test
        @DisplayName("B-3: no copy bar (Text/Markdown/Path buttons absent)")
        void no_copyBar() {
            page.navigate(url("/guides/top_page/"));
            assertNull(page.querySelector("#copy-text-btn"),
                    "Production mode must not render copy bar buttons");
        }
    }

    // ---- C. Sidebar rendering (DocPageSidebar_260401_oo01) ---------------

    @Nested
    @DisplayName("C. Sidebar rendering (DocPageSidebar_260401_oo01)")
    class SidebarRendering {

        @Test
        @DisplayName("C-1: Pattern 2 category header is <a class=\"cat-label\"> (not <span>)")
        void pattern2_categoryHeader_isLink() {
            // View a page that belongs to the "guides" top section.
            // The sidebar shows guides' children; software (Pattern 2) must have a clickable header.
            page.navigate(url("/guides/software/software_update_info/"));

            ElementHandle catLink = page.querySelector(".cat-header a.cat-label");
            assertNotNull(catLink,
                    "Pattern 2: sidebar must render category header as <a class=\"cat-label\">");
        }

        @Test
        @DisplayName("C-2: Pattern 2 category header links to /guides/software/")
        void pattern2_categoryHeader_href() {
            page.navigate(url("/guides/software/software_update_info/"));

            // Find all cat-label links, then pick the one for "software"
            boolean found = page.querySelectorAll(".cat-header a.cat-label").stream()
                    .anyMatch(el -> {
                        String href = el.getAttribute("href");
                        return href != null && href.contains("software") && !href.contains("software_update");
                    });
            assertTrue(found,
                    "Pattern 2: Software category header must link to the /guides/software/ page");
        }

        @Test
        @DisplayName("C-3: Pattern 2 same-name .md does NOT appear as child item in sidebar")
        void pattern2_sameNameMd_notChildItem() {
            page.navigate(url("/guides/software/software_update_info/"));

            // The software.md (same-name file) collapses to /guides/software/.
            // The sidebar must NOT contain a separate child link like .../software/software/
            // (note: trailing slash required to avoid false-positive match with software/software_update_info/)
            String content = page.content();
            assertFalse(content.contains("software/software/"),
                    "Pattern 2: same-name .md must not appear as a child sidebar link " +
                    "(a link ending in software/software/ must not exist)");
        }

        @Test
        @DisplayName("C-4: Pattern 1 dir/dir.md renders as plain link (no cat-header link to self)")
        void pattern1_rendersAsLeafLink_notCatHeader() {
            // top_page is Pattern 1 (no subdirs). When viewing top_page, the sidebar
            // shows guides' children. top_page must appear as a plain <a> leaf link, not
            // as a <div class="cat-header"> with a clickable link.
            page.navigate(url("/guides/top_page/"));

            // Check: no cat-label <a> with href pointing to /guides/top_page/
            boolean topPageAsCatHeader = page.querySelectorAll(".cat-header a.cat-label").stream()
                    .anyMatch(el -> {
                        String href = el.getAttribute("href");
                        return href != null && href.contains("top_page");
                    });
            assertFalse(topPageAsCatHeader,
                    "Pattern 1: top_page must not appear as a cat-header link in the sidebar");
        }

        @Test
        @DisplayName("C-5: active sidebar link matches current page URL")
        void activeLink_matchesCurrentPage() {
            page.navigate(url("/guides/software/software_update_info/"));

            ElementHandle activeLink = page.querySelector("nav.side a.active");
            assertNotNull(activeLink, "An active sidebar link must be present on the page");
            String href = activeLink.getAttribute("href");
            assertTrue(href != null && href.contains("software_update_info"),
                    "Active sidebar link must point to the current page (software_update_info)");
        }
    }

    // ---- D. Search (HtmlSaurusSearchIndex_260401_oo01) -------------------

    @Nested
    @DisplayName("D. Search (HtmlSaurusSearchIndex_260401_oo01)")
    class SearchFunction {

        @Test
        @DisplayName("D-1: search input (#search-input) is present on every page")
        void searchInput_present() {
            page.navigate(url("/guides/top_page/"));
            assertNotNull(page.querySelector("#search-input"),
                    "Search input #search-input must be present on every page");
        }

        @Test
        @DisplayName("D-2: search API returns JSON results for an English keyword")
        void searchApi_englishKeyword_returnsResults() {
            page.navigate(url("/guides/top_page/"));
            // Fetch from the same origin to avoid CORS issues
            String json = (String) page.evaluate(
                    "() => fetch('/search?q=SLURM').then(r => r.text())");
            assertNotNull(json, "Search API must return a response");
            assertFalse(json.equals("[]"),
                    "Search API must return results for 'SLURM' (known keyword in guides)");
            assertTrue(json.contains("\"title\""),
                    "Search results must include a title field");
            assertTrue(json.contains("\"path\""),
                    "Search results must include a path field");
        }

        @Test
        @DisplayName("D-3: search API returns results for a Japanese keyword")
        void searchApi_japaneseKeyword_returnsResults() {
            page.navigate(url("/guides/top_page/"));
            String json = (String) page.evaluate(
                    "() => fetch('/search?q=\u30B9\u30FC\u30D1\u30FC\u30B3\u30F3\u30D4\u30E5\u30FC\u30BF').then(r => r.text())");
            // "スーパーコンピュータ" (supercomputer)
            assertNotNull(json, "Search API must return a response for Japanese query");
            assertFalse(json.equals("[]"),
                    "Search API must return results for 'スーパーコンピュータ'");
        }

        @Test
        @DisplayName("D-4: search API path field uses clean URL format (trailing slash, no .html)")
        void searchApi_pathField_isCleanUrl() {
            page.navigate(url("/guides/top_page/"));
            // Extract only the path values from search results to avoid false-positives
            // from .html appearing in the page body/summary text
            String pathsJson = (String) page.evaluate("""
                    () => fetch('/search?q=SLURM')
                        .then(r => r.json())
                        .then(results => JSON.stringify(results.map(r => r.path)))
                    """);
            assertNotNull(pathsJson);
            assertFalse(pathsJson.equals("[]"), "Need at least one result to check path format");
            assertFalse(pathsJson.contains(".html"),
                    "Production mode: search result path fields must not contain .html extension, got: " + pathsJson);
            assertTrue(pathsJson.contains("\"/"),
                    "Search result path fields must start with /");
        }
    }

    // ---- E. i18n (language switcher) -------------------------------------

    @Nested
    @DisplayName("E. i18n: language switcher (nigsc_homepage2 has ja + en)")
    class I18n {

        @Test
        @DisplayName("E-1: language switcher button is present (multiple locales configured)")
        void languageSwitcher_present() {
            page.navigate(url("/guides/top_page/"));
            assertNotNull(page.querySelector(".lang-btn"),
                    "Language switcher button (.lang-btn) must be present " +
                    "because nigsc_homepage2 has ja + en locales");
        }

        @Test
        @DisplayName("E-2: English locale pages are accessible at /en/guides/top_page/")
        void english_topPage_returns200() {
            Response resp = page.navigate(url("/en/guides/top_page/"));
            assertEquals(200, resp.status(),
                    "GET /en/guides/top_page/ must return 200 " +
                    "(English alternate locale must be built and served)");
        }
    }
}
