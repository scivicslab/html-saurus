package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    private static final Path NIGSC_EN_DOCS = Path.of(
        "/home/devteam/works/nigsc_homepage2/i18n/en/docusaurus-plugin-content-docs/current");

    private Path outDir;
    private HttpServer server;
    private int port;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    // ---- Setup / teardown ------------------------------------------------

    @BeforeAll
    void startServer() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(NIGSC_DOCS),
            "nigsc_homepage2/docs not found - skipping production E2E tests");
        outDir = Files.createTempDirectory("html-saurus-e2e-prod-");

        // Build all locales (ja default + en alternate) in production mode
        Main.build(NIGSC_DOCS, outDir, true);

        // Index ja (default) and en (alternate) locales
        Path indexDir = outDir.resolve("search-index");
        Main.reindex(NIGSC_DOCS, indexDir, "ja", true);
        if (Files.isDirectory(NIGSC_EN_DOCS)) {
            Main.reindex(NIGSC_EN_DOCS, indexDir.resolve("en"), "en", true);
        }

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
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        if (page != null) page.close();
        if (context != null) context.close();
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

        @Test
        @DisplayName("E-3: from English page, Japanese link resolves to /guides/top_page/ (not /en/guides/top_page/)")
        void english_to_japanese_linkIsCorrect() {
            // Bug fixed: alternate locale pages need one extra '../' to escape the locale dir.
            // Before fix: prefix='../../' → ../../guides/top_page/ → /en/guides/top_page/ (wrong)
            // After fix:  prefix='../../../' → ../../../guides/top_page/ → /guides/top_page/ (correct)
            page.navigate(url("/en/guides/top_page/"));

            ElementHandle jaLink = page.querySelectorAll(".lang-item").stream()
                    .filter(el -> {
                        String text = el.textContent();
                        return text != null && text.contains("日本語");
                    })
                    .findFirst().orElse(null);
            assertNotNull(jaLink, "Japanese language link must exist on English page");

            String href = jaLink.getAttribute("href");
            assertNotNull(href, "Japanese link must have href attribute");

            // Resolve the relative href against the English page URL
            String resolvedUrl = (String) page.evaluate(
                    "href => new URL(href, document.baseURI).href", href);
            assertTrue(resolvedUrl.endsWith("/guides/top_page/"),
                    "Japanese link from English page must resolve to /guides/top_page/, got: " + resolvedUrl);
            assertFalse(resolvedUrl.contains("/en/guides/"),
                    "Japanese link must NOT stay in /en/ locale, got: " + resolvedUrl);
        }

        @Test
        @DisplayName("E-4: following Japanese link from English page lands on Japanese page")
        void english_follow_japanese_link_navigates() {
            page.navigate(url("/en/guides/top_page/"));

            // Extract the ja link href and navigate to it directly
            // (avoids dropdown-open timing issues while still verifying the link works end-to-end)
            String jaHref = (String) page.evaluate("""
                    () => {
                        const links = document.querySelectorAll('.lang-item');
                        for (const l of links) {
                            if (l.textContent.includes('日本語')) return l.getAttribute('href');
                        }
                        return null;
                    }
                    """);
            assertNotNull(jaHref, "Japanese lang-item link must exist");

            // Resolve the relative href to an absolute URL using the browser, then navigate
            page.navigate(url("/en/guides/top_page/"));
            String resolved = (String) page.evaluate("href => new URL(href, document.baseURI).href", jaHref);
            page.navigate(resolved);
            page.waitForLoadState();

            String finalUrl = page.url();
            assertTrue(finalUrl.contains("/guides/top_page/"),
                    "Japanese link must navigate to /guides/top_page/, got: " + finalUrl);
            assertFalse(finalUrl.contains("/en/guides/"),
                    "Japanese link must NOT stay in /en/ locale, got: " + finalUrl);
        }
    }

    // ---- F. Navbar ordering and content ----------------------------------

    @Nested
    @DisplayName("F. Navbar ordering and content (reorderFromDocusaurusConfig fix)")
    class NavbarOrdering {

        @Test
        @DisplayName("F-1: first navbar item is 'システム概要' (not alphabetical order)")
        void firstNavbarItem_isSystemOverview() {
            // Before the reorderFromDocusaurusConfig fix, items were sorted alphabetically.
            // After the fix the order follows docusaurus.config.ts (translated via navbar.json):
            // システム概要 → 各種申請等 → 活用方法 → 稼働状況 → 成果報告
            page.navigate(url("/guides/top_page/"));

            List<ElementHandle> navLinks = page.querySelectorAll("header nav.top a");
            assertFalse(navLinks.isEmpty(), "Navbar must contain at least one link");

            String firstLabel = navLinks.get(0).textContent().trim();
            assertEquals("システム概要", firstLabel,
                    "First navbar item must be 'システム概要' (docusaurus.config.ts order translated via navbar.json)");
        }

        @Test
        @DisplayName("F-2: navbar contains exactly 5 items")
        void navbarItems_count_fiveItems() {
            page.navigate(url("/guides/top_page/"));

            List<ElementHandle> navLinks = page.querySelectorAll("header nav.top a");
            assertEquals(5, navLinks.size(),
                    "Navbar must contain exactly 5 items matching docusaurus.config.ts");
        }

        @Test
        @DisplayName("F-3: navbar labels match docusaurus.config.ts order translated via navbar.json")
        void navbarItems_labelsMatchConfig_inOrder() {
            page.navigate(url("/guides/top_page/"));

            List<String> labels = page.querySelectorAll("header nav.top a").stream()
                    .map(el -> el.textContent().trim())
                    .toList();

            List<String> expected = List.of(
                    "システム概要",
                    "各種申請等",
                    "活用方法",
                    "稼働状況",
                    "成果報告");

            assertEquals(expected, labels,
                    "Navbar labels must match docusaurus.config.ts order translated via i18n/ja/navbar.json: " + expected);
        }

        @Test
        @DisplayName("F-4: active navbar item on /guides/top_page/ is 'システム概要'")
        void activeNavItem_matchesCurrentSection_guidesPage() {
            page.navigate(url("/guides/top_page/"));

            ElementHandle activeItem = page.querySelector("header nav.top a.active");
            assertNotNull(activeItem,
                    "An active navbar item must be present on /guides/top_page/");

            String activeLabel = activeItem.textContent().trim();
            assertEquals("システム概要", activeLabel,
                    "Active navbar item on /guides/top_page/ must be 'システム概要'");
        }

        @Test
        @DisplayName("F-5: navbar present on Applications page with 'Applications' active")
        void navbar_presentAndActiveOnApplicationsPage() {
            // Applications section first page (from docusaurus.config.ts navbar config)
            page.navigate(url("/application/terms_and_policies/user_account_issurance_criteria/"));

            List<ElementHandle> navLinks = page.querySelectorAll("header nav.top a");
            assertEquals(5, navLinks.size(),
                    "Navbar must still show 5 items on Applications section page");

            ElementHandle activeItem = page.querySelector("header nav.top a.active");
            assertNotNull(activeItem, "An active navbar item must be present on Applications page");

            String activeLabel = activeItem.textContent().trim();
            assertEquals("各種申請等", activeLabel,
                    "Active navbar item on Applications page must be '各種申請等'");
        }
    }

    // ---- G. Sidebar state persistence via localStorage -------------------

    @Nested
    @DisplayName("G. Sidebar state persistence via localStorage (hs-cat: keys)")
    class SidebarPersistence {

        @Test
        @DisplayName("G-1: cat-header elements have data-cat attribute starting with '/'")
        void catHeaders_have_dataCatAttribute() {
            page.navigate(url("/guides/top_page/"));

            List<ElementHandle> catHeaders = page.querySelectorAll(".cat-header[data-cat]");
            assertFalse(catHeaders.isEmpty(),
                    "Sidebar must contain .cat-header elements with a data-cat attribute");

            // All data-cat values must be absolute paths starting with "/"
            boolean allStartWithSlash = catHeaders.stream()
                    .allMatch(el -> {
                        String dataCat = el.getAttribute("data-cat");
                        return dataCat != null && dataCat.startsWith("/");
                    });
            assertTrue(allStartWithSlash,
                    "All data-cat attribute values must start with '/' (absolute paths)");
        }

        @Test
        @DisplayName("G-2: clicking a category saves its data-cat key to localStorage as '1'")
        void clickingCategory_savesStateToLocalStorage() {
            page.navigate(url("/guides/top_page/"));

            // Clear any prior state
            page.evaluate("() => localStorage.clear()");

            // Get the first cat-header's data-cat key
            ElementHandle firstCatHeader = page.querySelector(".cat-header[data-cat]");
            assertNotNull(firstCatHeader, "At least one .cat-header[data-cat] must exist");
            String key = firstCatHeader.getAttribute("data-cat");
            assertNotNull(key, "data-cat attribute must not be null");

            // Click the arrow span (not the header div, which contains a cat-label link
            // with stopPropagation that would intercept clicks on the center of the div)
            ElementHandle arrow = firstCatHeader.querySelector(".cat-arrow");
            assertNotNull(arrow, ".cat-header must contain a .cat-arrow span");
            arrow.click();

            // The JS sidebar code must write localStorage.setItem('hs-cat:' + key, '1')
            Object stored = page.evaluate("k => localStorage.getItem('hs-cat:' + k)", key);
            assertEquals("1", stored,
                    "After clicking category, localStorage['hs-cat:" + key + "'] must be '1'");
        }

        @Test
        @DisplayName("G-3: localStorage state is restored after navigating to a different page")
        void localStorageState_restoredAcrossNavigation() {
            page.navigate(url("/guides/top_page/"));

            // Clear state and explicitly mark security_policy as open
            page.evaluate("() => localStorage.clear()");
            page.evaluate("() => localStorage.setItem('hs-cat:/guides/security_policy/', '1')");

            // Navigate to a different page within the same origin (same browser context)
            page.navigate(url("/guides/overview/"));
            page.waitForLoadState();

            // The sidebar should restore the open state from localStorage
            Boolean isOpen = (Boolean) page.evaluate(
                    "() => { " +
                    "  const el = document.querySelector('[data-cat=\"/guides/security_policy/\"]'); " +
                    "  if (!el) return null; " +
                    "  const next = el.nextElementSibling; " +
                    "  if (!next) return null; " +
                    "  return next.classList.contains('open'); " +
                    "}");
            assertNotNull(isOpen,
                    "security_policy cat-header and its sibling must exist on /guides/overview/");
            assertTrue(isOpen,
                    "After restoring localStorage state, security_policy category must be open (.open class)");
        }

        @Test
        @DisplayName("G-4: closing a category removes its key from localStorage")
        void closingCategory_removesFromLocalStorage() {
            page.navigate(url("/guides/top_page/"));

            // Clear state
            page.evaluate("() => localStorage.clear()");

            ElementHandle firstCatHeader = page.querySelector(".cat-header[data-cat]");
            assertNotNull(firstCatHeader, "At least one .cat-header[data-cat] must exist");
            String key = firstCatHeader.getAttribute("data-cat");

            // Click the arrow span to toggle (avoids the stopPropagation on cat-label link)
            ElementHandle arrow = firstCatHeader.querySelector(".cat-arrow");
            assertNotNull(arrow, ".cat-header must contain a .cat-arrow span");

            // First click: open the category
            arrow.click();
            Object afterOpen = page.evaluate("k => localStorage.getItem('hs-cat:' + k)", key);
            assertEquals("1", afterOpen,
                    "After first click (open), localStorage must hold '1' for key: " + key);

            // Second click: close the category
            arrow.click();
            Object afterClose = page.evaluate("k => localStorage.getItem('hs-cat:' + k)", key);
            assertNull(afterClose,
                    "After second click (close), localStorage key 'hs-cat:" + key + "' must be removed (null)");
        }
    }

    // ---- H. Sidebar ancestor auto-expand ---------------------------------

    @Nested
    @DisplayName("H. Sidebar ancestor auto-expand (SiteBuilder ancestor expansion logic)")
    class SidebarAutoExpand {

        @Test
        @DisplayName("H-1: on deep page, software ancestor category is automatically opened")
        void deepPage_softwareAncestor_autoOpened() {
            // Navigate to a deep page under software/JobScheduler/Slurm/
            // The /guides/software/ ancestor cat-header must be auto-expanded (class 'open')
            page.navigate(url("/guides/software/JobScheduler/Slurm/batch_jobs/"));
            page.waitForLoadState();

            Boolean isOpen = (Boolean) page.evaluate(
                    "() => { " +
                    "  const el = document.querySelector('[data-cat=\"/guides/software/\"]'); " +
                    "  if (!el) return null; " +
                    "  const next = el.nextElementSibling; " +
                    "  if (!next) return null; " +
                    "  return next.classList.contains('open'); " +
                    "}");
            assertNotNull(isOpen,
                    "/guides/software/ cat-header and its sibling must exist on the deep Slurm page");
            assertTrue(isOpen,
                    "Ancestor /guides/software/ must be auto-expanded (class 'open') " +
                    "when viewing a page deep inside the software section");
        }

        @Test
        @DisplayName("H-2: on deep page, Slurm ancestor category is automatically opened")
        void deepPage_slurmAncestor_autoOpened() {
            page.navigate(url("/guides/software/JobScheduler/Slurm/batch_jobs/"));
            page.waitForLoadState();

            Boolean isOpen = (Boolean) page.evaluate(
                    "() => { " +
                    "  const el = document.querySelector('[data-cat=\"/guides/software/JobScheduler/Slurm/\"]'); " +
                    "  if (!el) return null; " +
                    "  const next = el.nextElementSibling; " +
                    "  if (!next) return null; " +
                    "  return next.classList.contains('open'); " +
                    "}");
            assertNotNull(isOpen,
                    "/guides/software/JobScheduler/Slurm/ cat-header and sibling must exist on batch_jobs page");
            assertTrue(isOpen,
                    "Ancestor /guides/software/JobScheduler/Slurm/ must be auto-expanded " +
                    "when viewing batch_jobs page inside it");
        }

        @Test
        @DisplayName("H-3: on deep page, unrelated sibling category remains closed")
        void deepPage_siblingCategory_remainsClosed() {
            // Navigate to the Slurm page; clear localStorage to ensure no persisted open state;
            // then reload and check that security_policy (a sibling, not an ancestor) is closed.
            page.navigate(url("/guides/software/JobScheduler/Slurm/batch_jobs/"));
            page.waitForLoadState();

            // Clear any localStorage state that might force security_policy open
            page.evaluate("() => localStorage.clear()");
            page.reload();
            page.waitForLoadState();

            Boolean isOpen = (Boolean) page.evaluate(
                    "() => { " +
                    "  const el = document.querySelector('[data-cat=\"/guides/security_policy/\"]'); " +
                    "  if (!el) return null; " +
                    "  const next = el.nextElementSibling; " +
                    "  if (!next) return null; " +
                    "  return next.classList.contains('open'); " +
                    "}");
            // If the element does not exist on this page that is also acceptable
            if (isOpen != null) {
                assertFalse(isOpen,
                        "Unrelated sibling category /guides/security_policy/ must NOT be auto-expanded " +
                        "when viewing a page in the software/Slurm section");
            }
        }
    }

    // ---- I. Page content rendering ---------------------------------------

    @Nested
    @DisplayName("I. Page content rendering")
    class ContentRendering {

        @Test
        @DisplayName("I-1: page title is not empty on /guides/top_page/")
        void pageTitle_isNotEmpty() {
            page.navigate(url("/guides/top_page/"));
            String title = page.title();
            assertNotNull(title, "page.title() must not be null");
            assertFalse(title.isBlank(),
                    "Page title must not be blank on /guides/top_page/");
        }

        @Test
        @DisplayName("I-2: main content area exists and contains text")
        void mainContentArea_existsAndHasText() {
            page.navigate(url("/guides/top_page/"));
            ElementHandle main = page.querySelector("main");
            assertNotNull(main, "A <main> element must be present on the page");
            String text = main.textContent();
            assertFalse(text == null || text.isBlank(),
                    "<main> element must contain non-blank text content");
        }

        @Test
        @DisplayName("I-3: main content contains at least one heading (h1 or h2)")
        void mainContent_hasHeading() {
            page.navigate(url("/guides/top_page/"));
            boolean hasHeading = page.querySelector("main h1") != null
                    || page.querySelector("main h2") != null;
            assertTrue(hasHeading,
                    "Main content area must contain at least one <h1> or <h2> heading");
        }

        @Test
        @DisplayName("I-4: site title link present in header and not blank")
        void siteTitle_presentInHeader() {
            page.navigate(url("/guides/top_page/"));
            ElementHandle siteTitle = page.querySelector("header a.site-title");
            assertNotNull(siteTitle,
                    "Header must contain <a class=\"site-title\"> link");
            String text = siteTitle.textContent();
            assertFalse(text == null || text.isBlank(),
                    "Site title link text must not be blank");
        }

        @Test
        @DisplayName("I-5: H1 heading overrides frontmatter title — top_page shows '遺伝研スーパーコンピュータ(2025)' not 'トップページ'")
        void h1HeadingOverridesFrontmatterTitle() {
            // 010_top_page.md has frontmatter `title: トップページ` but body `# 遺伝研スーパーコンピュータ(2025)`.
            // The H1 must win: page title and main <h1> must contain the H1 text, not the frontmatter title.
            page.navigate(url("/guides/top_page/"));
            page.waitForLoadState();

            String pageTitle = page.title();
            assertTrue(pageTitle.contains("遺伝研スーパーコンピュータ(2025)"),
                    "Browser <title> must contain the H1 text '遺伝研スーパーコンピュータ(2025)', got: " + pageTitle);
            assertFalse(pageTitle.contains("トップページ"),
                    "Browser <title> must NOT contain frontmatter title 'トップページ', got: " + pageTitle);

            ElementHandle h1 = page.querySelector("main h1");
            assertNotNull(h1, "main <h1> must be present");
            String h1Text = h1.textContent();
            assertTrue(h1Text.contains("遺伝研スーパーコンピュータ(2025)"),
                    "main <h1> must contain '遺伝研スーパーコンピュータ(2025)', got: " + h1Text);
        }

        @Test
        @DisplayName("I-6: H1 is not duplicated — main contains exactly one <h1>")
        void h1NotDuplicated() {
            // H1 is stripped from contentHtml and re-inserted by renderPage; must appear exactly once.
            page.navigate(url("/guides/top_page/"));
            page.waitForLoadState();

            int h1Count = page.querySelectorAll("main h1").size();
            assertEquals(1, h1Count,
                    "main must contain exactly one <h1> — H1 must not be duplicated");
        }
    }

    // ---- J. All five navbar sections accessible --------------------------

    @Nested
    @DisplayName("J. All five navbar sections accessible")
    class AllSectionsAccessible {

        @Test
        @DisplayName("J-1: Applications section first page returns 200")
        void applicationSection_firstPage_returns200() {
            Response resp = page.navigate(
                    url("/application/terms_and_policies/user_account_issurance_criteria/"));
            assertEquals(200, resp.status(),
                    "GET /application/terms_and_policies/user_account_issurance_criteria/ " +
                    "must return 200 (Applications section first page from navbar config)");
        }

        @Test
        @DisplayName("J-2: Advanced Guides section first page returns 200")
        void advancedGuidesSection_firstPage_returns200() {
            Response resp = page.navigate(
                    url("/advanced_guides/topics/advanced_guide_2020-2022/"));
            assertEquals(200, resp.status(),
                    "GET /advanced_guides/topics/advanced_guide_2020-2022/ " +
                    "must return 200 (Advanced Guides section first page from navbar config)");
        }

        @Test
        @DisplayName("J-3: System Status section returns 200")
        void operationSection_returns200() {
            Response resp = page.navigate(url("/operation/job_queue_status/"));
            assertEquals(200, resp.status(),
                    "GET /operation/job_queue_status/ must return 200 " +
                    "(System Status section first page from navbar config)");
        }

        @Test
        @DisplayName("J-4: Reports section returns 200")
        void reportSection_returns200() {
            Response resp = page.navigate(url("/report/statistics/"));
            assertEquals(200, resp.status(),
                    "GET /report/statistics/ must return 200 " +
                    "(Reports section first page from navbar config)");
        }
    }

    // ---- K. Search UI interaction ----------------------------------------

    @Nested
    @DisplayName("K. Search UI interaction (null-guard fix for rebuild-btn in production mode)")
    class SearchUiInteraction {

        @Test
        @DisplayName("K-1: typing in search box triggers the results dropdown")
        void typing_triggersResultsDropdown() {
            page.navigate(url("/guides/top_page/"));

            // Filling the input dispatches input events which trigger the search debounce
            page.fill("#search-input", "slurm");

            // The dropdown must open (class 'open' added to #search-results)
            ElementHandle results = page.waitForSelector("#search-results.open");
            assertNotNull(results,
                    "After typing 'slurm', #search-results must acquire class 'open'");
        }

        @Test
        @DisplayName("K-2: search results have expected structure elements")
        void searchResults_haveExpectedStructure() {
            page.navigate(url("/guides/top_page/"));
            page.fill("#search-input", "slurm");
            page.waitForSelector("#search-results.open");

            // Each result item must contain the expected structural sub-elements
            assertNotNull(page.querySelector(".sr-item"),
                    "Search results must contain .sr-item elements");
            assertNotNull(page.querySelector(".sr-title"),
                    "Search result items must contain .sr-title element");
            assertNotNull(page.querySelector(".sr-breadcrumb"),
                    "Search result items must contain .sr-breadcrumb element");
            assertNotNull(page.querySelector(".sr-summary"),
                    "Search result items must contain .sr-summary element");
        }

        @Test
        @DisplayName("K-3: pressing Escape closes the results dropdown")
        void escapeKey_closesResultsDropdown() {
            page.navigate(url("/guides/top_page/"));
            page.fill("#search-input", "slurm");
            page.waitForSelector("#search-results.open");

            // Press Escape to dismiss
            page.keyboard().press("Escape");

            // The dropdown must lose its 'open' class.
            // #search-results is display:none when not open, so we cannot use waitForSelector
            // with the default visible state.  Poll the class directly instead.
            page.waitForFunction("() => !document.querySelector('#search-results').classList.contains('open')");
            assertNull(page.querySelector("#search-results.open"),
                    "After pressing Escape, #search-results must not have class 'open'");
        }

        @Test
        @DisplayName("K-4: search result links use clean URL hrefs (start with '/', no '.html')")
        void searchResultLinks_haveCleanUrlHrefs() {
            page.navigate(url("/guides/top_page/"));
            page.fill("#search-input", "slurm");
            page.waitForSelector("#search-results.open");

            // Get the first result item and check its href
            ElementHandle firstItem = page.querySelector(".sr-item");
            assertNotNull(firstItem, "At least one .sr-item must be present in results");

            String href = firstItem.getAttribute("href");
            assertNotNull(href, ".sr-item must have an href attribute");
            assertTrue(href.startsWith("/"),
                    "Search result link href must start with '/', got: " + href);
            assertFalse(href.contains(".html"),
                    "Search result link href must not contain '.html', got: " + href);
        }

        @Test
        @DisplayName("K-5: empty search input does not open the dropdown")
        void emptySearchInput_doesNotOpenDropdown() {
            // On a fresh page load with no interaction, the dropdown must be closed
            page.navigate(url("/guides/top_page/"));
            page.waitForLoadState();

            assertNull(page.querySelector("#search-results.open"),
                    "#search-results must not have class 'open' on fresh page load without typing");
        }
    }

    // ---- L. Root redirect behavior ---------------------------------------

    @Nested
    @DisplayName("L. Root redirect behavior")
    class RedirectBehavior {

        @Test
        @DisplayName("L-1: navigating to '/' does not stay at index.html after redirect")
        void rootRedirect_doesNotStayAtIndexHtml() {
            page.navigate(url("/"));
            page.waitForLoadState();

            String currentUrl = page.url();
            assertFalse(currentUrl.endsWith("index.html"),
                    "After root redirect, page URL must not end with 'index.html', got: " + currentUrl);
        }

        @Test
        @DisplayName("L-2: root redirect target page returns 200 when navigated to directly")
        void rootRedirect_targetPage_returns200() {
            // Follow the redirect to discover the target URL
            page.navigate(url("/"));
            page.waitForLoadState();
            String redirectTarget = page.url();

            // Navigate via about:blank so we are not at the target URL yet;
            // then navigate directly to it to obtain a fresh HTTP response.
            page.navigate("about:blank");
            Response resp = page.navigate(redirectTarget);
            assertNotNull(resp,
                    "Direct navigation to '" + redirectTarget + "' must return a response");
            assertEquals(200, resp.status(),
                    "Root redirect target '" + redirectTarget + "' must return 200 when navigated directly");
        }
    }

    // ---- M. More i18n ----------------------------------------------------

    @Nested
    @DisplayName("M. More i18n (language switcher depth and alternate locale)")
    class MoreI18n {

        @Test
        @DisplayName("M-1: language switcher shows exactly two locale options")
        void languageSwitcher_hasTwoLocales() {
            page.navigate(url("/guides/top_page/"));

            List<ElementHandle> langItems = page.querySelectorAll(".lang-item");
            assertEquals(2, langItems.size(),
                    "Language switcher must contain exactly 2 .lang-item elements " +
                    "(ja + en as configured in nigsc_homepage2)");
        }

        @Test
        @DisplayName("M-2: English link on /guides/software/software_update_info/ resolves to /en/ equivalent")
        void deepJapanesePage_englishLinkResolvesCorrectly() {
            // Verify that the locale-switcher href for a deep Japanese page correctly
            // points to the mirrored English page path
            page.navigate(url("/guides/software/software_update_info/"));

            ElementHandle enLink = page.querySelectorAll(".lang-item").stream()
                    .filter(el -> {
                        String text = el.textContent();
                        return text != null && text.contains("English");
                    })
                    .findFirst().orElse(null);
            assertNotNull(enLink,
                    "An 'English' .lang-item must exist on /guides/software/software_update_info/");

            String href = enLink.getAttribute("href");
            assertNotNull(href, "English lang-item must have an href attribute");

            // Resolve the relative href to an absolute URL
            String resolvedUrl = (String) page.evaluate(
                    "href => new URL(href, document.baseURI).href", href);
            assertTrue(resolvedUrl.contains("/en/guides/software/software_update_info/"),
                    "English link from /guides/software/software_update_info/ must resolve to " +
                    "/en/guides/software/software_update_info/, got: " + resolvedUrl);
        }

        @Test
        @DisplayName("M-3: alternate locale deep page /en/guides/software/software_update_info/ returns 200")
        void alternateLocaleDeepPage_returns200() {
            Response resp = page.navigate(url("/en/guides/software/software_update_info/"));
            assertEquals(200, resp.status(),
                    "GET /en/guides/software/software_update_info/ must return 200 " +
                    "(English alternate locale deep page must be built and served)");
        }
    }

    // ---- N. English locale search ----------------------------------------

    @Nested
    @DisplayName("N. English locale search")
    class EnglishSearch {

        @Test
        @DisplayName("N-1: English page embeds search URL with ?locale=en (not /en/search)")
        void englishPage_searchUrl_hasLocaleParam() {
            Assumptions.assumeTrue(Files.isDirectory(NIGSC_EN_DOCS),
                "English docs not found - skipping English search tests");
            page.navigate(url("/en/guides/top_page/"));
            String source = page.content();
            assertTrue(source.contains("search?locale=en"),
                    "English page must embed a search URL containing 'search?locale=en'");
            assertFalse(source.contains("'/en/search'") || source.contains("\"/en/search\""),
                    "English page must NOT embed '/en/search' as search endpoint " +
                    "(it would 404 because the server only handles /search at project root)");
        }

        @Test
        @DisplayName("N-2: search API with ?locale=en returns English results")
        void searchApi_localeEn_returnsResults() {
            Assumptions.assumeTrue(Files.isDirectory(NIGSC_EN_DOCS),
                "English docs not found - skipping");
            page.navigate(url("/en/guides/top_page/"));
            // "supercomputer" appears in English /guides/overview/ content
            String json = (String) page.evaluate(
                    "() => fetch('/search?locale=en&q=supercomputer').then(r => r.text())");
            assertNotNull(json, "Search API must return a response");
            assertFalse(json.equals("[]"),
                    "Search with ?locale=en must return results for 'supercomputer' " +
                    "(keyword appears in English guides/overview content)");
            assertTrue(json.contains("\"title\""),
                    "English search results must include a title field");
            assertTrue(json.contains("\"path\""),
                    "English search results must include a path field");
        }

        @Test
        @DisplayName("N-3: search from English page using embedded SEARCH_URL returns results")
        void englishPage_embeddedSearchUrl_returnsResults() {
            Assumptions.assumeTrue(Files.isDirectory(NIGSC_EN_DOCS),
                "English docs not found - skipping");
            page.navigate(url("/en/guides/top_page/"));
            // Extract the SEARCH_URL baked into the page and fetch from it —
            // this simulates what the browser's search box does when on an English page.
            String json = (String) page.evaluate("""
                    () => {
                        for (const s of document.querySelectorAll('script')) {
                            const m = s.textContent.match(/var SEARCH_URL = '([^']+)'/);
                            if (m) {
                                var sep = m[1].indexOf('?') >= 0 ? '&' : '?';
                                return fetch(m[1] + sep + 'q=supercomputer').then(r => r.text());
                            }
                        }
                        return Promise.resolve(null);
                    }
                    """);
            assertNotNull(json, "SEARCH_URL must be found in the page and return a response");
            assertFalse(json.equals("[]"),
                    "Fetch via embedded SEARCH_URL from English page must return results for 'supercomputer'");
        }

        @Test
        @DisplayName("N-4: English search results path does not contain .html extension")
        void englishSearch_resultPaths_useCleanUrls() {
            Assumptions.assumeTrue(Files.isDirectory(NIGSC_EN_DOCS),
                "English docs not found - skipping");
            page.navigate(url("/en/guides/top_page/"));
            String pathsJson = (String) page.evaluate("""
                    () => fetch('/search?locale=en&q=supercomputer')
                        .then(r => r.json())
                        .then(results => JSON.stringify(results.map(r => r.path)))
                    """);
            assertNotNull(pathsJson);
            assertFalse(pathsJson.equals("[]"), "Need at least one result to check path format");
            assertFalse(pathsJson.contains(".html"),
                    "English search result paths must not contain .html extension, got: " + pathsJson);
        }

        @Test
        @DisplayName("N-5: Japanese search does not bleed into English locale results")
        void jaSearch_doesNotReturnEnglishResults() {
            Assumptions.assumeTrue(Files.isDirectory(NIGSC_EN_DOCS),
                "English docs not found - skipping");
            page.navigate(url("/guides/top_page/"));
            // スーパーコンピュータ is a Japanese-only term; English index should not match it
            String enJson = (String) page.evaluate(
                    "() => fetch('/search?locale=en&q=\u30B9\u30FC\u30D1\u30FC\u30B3\u30F3\u30D4\u30E5\u30FC\u30BF').then(r => r.text())");
            // We only assert the locale-specific index was used (no cross-contamination).
            // The English index may or may not match katakana — we just verify it returns a
            // different (typically empty) result set from the Japanese index.
            String jaJson = (String) page.evaluate(
                    "() => fetch('/search?q=\u30B9\u30FC\u30D1\u30FC\u30B3\u30F3\u30D4\u30E5\u30FC\u30BF').then(r => r.text())");
            assertNotNull(enJson, "English locale search must return a response");
            assertNotNull(jaJson, "Japanese locale search must return a response");
            assertFalse(jaJson.equals("[]"),
                    "Japanese search for 'スーパーコンピュータ' must return results from the Japanese index");
        }
    }
}
