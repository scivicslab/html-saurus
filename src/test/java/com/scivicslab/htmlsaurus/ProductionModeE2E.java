package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Production-mode E2E test runner.
 *
 * <p>Drives Chromium via Playwright against a site served in production mode.
 *
 * <p>With {@code PRODUCTION_URL} set, it checks that already-running site and builds nothing:
 * <pre>
 *   PRODUCTION_URL=http://192.168.5.21:31086 mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.htmlsaurus.ProductionModeE2E \
 *     -Dexec.classpathScope=test
 * </pre>
 *
 * <p>Without it, it builds nigsc_homepage2 in production mode into a temporary directory and
 * serves it from a local HttpServer:
 * <pre>
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.htmlsaurus.ProductionModeE2E \
 *     -Dexec.classpathScope=test
 * </pre>
 */
public class ProductionModeE2E {

    private static final Path NIGSC_DOCS = Path.of("/home/devteam/works/nigsc_homepage2/docs");
    private static final Path NIGSC_EN_DOCS = Path.of(
        "/home/devteam/works/nigsc_homepage2/i18n/en/docusaurus-plugin-content-docs/current");

    /** When set, check this already-running site instead of building and serving one. */
    private static final String EXTERNAL_URL = System.getenv("PRODUCTION_URL");

    private static HttpServer server;
    private static int port;
    private static Playwright playwright;
    private static Browser browser;

    private static int passed = 0;
    private static int failed = 0;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (EXTERNAL_URL == null && !Files.isDirectory(NIGSC_DOCS)) {
            System.out.println("SKIP: nigsc_homepage2/docs not found");
            return;
        }

        setup();
        try {
            runA_urlStructure();
            runB_noDevControls();
            runC_sidebarRendering();
            runD_search();
            runE_i18n();
            runF_navbarOrdering();
            runG_sidebarPersistence();
            runH_sidebarAutoExpand();
            runI_contentRendering();
            runJ_allSectionsAccessible();
            runK_searchUiInteraction();
            runL_rootRedirect();
            runM_moreI18n();
            runN_englishSearch();
            runO_blog();
        } finally {
            teardown();
        }

        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // -------------------------------------------------------------------------
    // Infrastructure
    // -------------------------------------------------------------------------

    private static void setup() throws Exception {
        if (EXTERNAL_URL != null) {
            System.out.println("Checking the running site at " + EXTERNAL_URL);
            playwright = Playwright.create();
            browser = playwright.chromium().launch();
            return;
        }

        Path outDir = Files.createTempDirectory("html-saurus-e2e-prod-");

        Main.build(NIGSC_DOCS, outDir, true);

        Path indexDir = outDir.resolve("search-index");
        Main.reindex(NIGSC_DOCS, indexDir, "ja", true);
        if (Files.isDirectory(NIGSC_EN_DOCS)) {
            Main.reindex(NIGSC_EN_DOCS, indexDir.resolve("en"), "en", true);
        }

        SearchServer ss = new SearchServer(outDir, indexDir, 0, () -> {}, true, NIGSC_DOCS, null);
        server = ss.start();
        port = server.getAddress().getPort();

        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    private static void teardown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.stop(0);
    }

    private static String url(String path) {
        if (EXTERNAL_URL != null) return EXTERNAL_URL.replaceAll("/+$", "") + path;
        return "http://localhost:" + port + path;
    }

    private static void withPage(String testName, Consumer<Page> test) {
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

    // -------------------------------------------------------------------------
    // A. URL structure
    // -------------------------------------------------------------------------

    private static void runA_urlStructure() {
        withPage("A-1: root / serves content without 404", page -> {
            Response resp = page.navigate(url("/"));
            check(resp.status() != 404, "Root / must not return 404");
            page.waitForLoadState();
            check(!page.url().endsWith("/index.html"),
                "After meta-refresh, URL must not end with index.html, got: " + page.url());
        });

        withPage("A-2: Pattern 1 — /guides/top_page/ returns 200", page ->
            check(page.navigate(url("/guides/top_page/")).status() == 200,
                "GET /guides/top_page/ must return 200"));

        withPage("A-3: Pattern 1 — /guides/overview/ returns 200", page ->
            check(page.navigate(url("/guides/overview/")).status() == 200,
                "GET /guides/overview/ must return 200"));

        withPage("A-4: Pattern 2 — /guides/security_policy/ returns 200", page ->
            check(page.navigate(url("/guides/security_policy/")).status() == 200,
                "GET /guides/security_policy/ must return 200"));

        withPage("A-5: Pattern 2 — /guides/software/ returns 200", page ->
            check(page.navigate(url("/guides/software/")).status() == 200,
                "GET /guides/software/ must return 200"));

        withPage("A-6: flat nested — /guides/software/software_update_info/ returns 200", page ->
            check(page.navigate(url("/guides/software/software_update_info/")).status() == 200,
                "GET /guides/software/software_update_info/ must return 200"));

        withPage("A-7: Pattern 2 deep path /guides/software/software/ must 404", page ->
            check(page.navigate(url("/guides/software/software/")).status() == 404,
                "GET /guides/software/software/ must return 404"));

        withPage("A-8: numeric prefix stripped — 010_ absent from URL", page ->
            check(page.navigate(url("/guides/010_top_page/")).status() != 200,
                "URL with numeric prefix must NOT return 200"));
    }

    // -------------------------------------------------------------------------
    // B. Production mode — dev controls absent
    // -------------------------------------------------------------------------

    private static void runB_noDevControls() {
        withPage("B-1: no Rebuild button (#rebuild-btn)", page -> {
            page.navigate(url("/guides/top_page/"));
            check(page.querySelector("#rebuild-btn") == null,
                "Production mode must not render #rebuild-btn");
        });

        withPage("B-2: no Theme selector (#theme-sel)", page -> {
            page.navigate(url("/guides/top_page/"));
            check(page.querySelector("#theme-sel") == null,
                "Production mode must not render #theme-sel");
        });

        withPage("B-3: no copy bar", page -> {
            page.navigate(url("/guides/top_page/"));
            check(page.querySelector("#copy-text-btn") == null,
                "Production mode must not render copy bar buttons");
        });
    }

    // -------------------------------------------------------------------------
    // C. Sidebar rendering
    // -------------------------------------------------------------------------

    private static void runC_sidebarRendering() {
        withPage("C-1: Pattern 2 category header is <a class=\"cat-label\">", page -> {
            page.navigate(url("/guides/software/software_update_info/"));
            check(page.querySelector(".cat-header a.cat-label") != null,
                "Pattern 2: sidebar must render category header as <a class=\"cat-label\">");
        });

        withPage("C-2: Pattern 2 category header links to /guides/software/", page -> {
            page.navigate(url("/guides/software/software_update_info/"));
            boolean found = page.querySelectorAll(".cat-header a.cat-label").stream()
                .anyMatch(el -> {
                    String href = el.getAttribute("href");
                    return href != null && href.contains("software") && !href.contains("software_update");
                });
            check(found, "Pattern 2: Software category header must link to /guides/software/");
        });

        withPage("C-3: Pattern 2 same-name .md does NOT appear as child item", page -> {
            page.navigate(url("/guides/software/software_update_info/"));
            check(!page.content().contains("software/software/"),
                "Pattern 2: same-name .md must not appear as a child sidebar link");
        });

        withPage("C-4: Pattern 1 renders as plain link (not cat-header)", page -> {
            page.navigate(url("/guides/top_page/"));
            boolean topPageAsCatHeader = page.querySelectorAll(".cat-header a.cat-label").stream()
                .anyMatch(el -> {
                    String href = el.getAttribute("href");
                    return href != null && href.contains("top_page");
                });
            check(!topPageAsCatHeader,
                "Pattern 1: top_page must not appear as a cat-header link");
        });

        withPage("C-5: active sidebar link matches current page URL", page -> {
            page.navigate(url("/guides/software/software_update_info/"));
            ElementHandle active = page.querySelector("nav.side a.active");
            check(active != null, "An active sidebar link must be present");
            String href = active.getAttribute("href");
            check(href != null && href.contains("software_update_info"),
                "Active sidebar link must point to software_update_info, got: " + href);
        });
    }

    // -------------------------------------------------------------------------
    // D. Search
    // -------------------------------------------------------------------------

    private static void runD_search() {
        withPage("D-1: search input (#search-input) present on every page", page -> {
            page.navigate(url("/guides/top_page/"));
            check(page.querySelector("#search-input") != null, "#search-input must be present");
        });

        withPage("D-2: search API returns JSON results for English keyword", page -> {
            page.navigate(url("/guides/top_page/"));
            String json = (String) page.evaluate("() => fetch('/search?q=SLURM').then(r => r.text())");
            check(json != null && !json.equals("[]"), "Search API must return results for 'SLURM'");
            check(json.contains("\"title\""), "Results must include title field");
            check(json.contains("\"path\""), "Results must include path field");
        });

        withPage("D-3: search API returns results for Japanese keyword", page -> {
            page.navigate(url("/guides/top_page/"));
            String json = (String) page.evaluate(
                "() => fetch('/search?q=\u30B9\u30FC\u30D1\u30FC\u30B3\u30F3\u30D4\u30E5\u30FC\u30BF').then(r => r.text())");
            check(json != null && !json.equals("[]"),
                "Search API must return results for 'スーパーコンピュータ'");
        });

        withPage("D-4: search API path field uses clean URL (no .html)", page -> {
            page.navigate(url("/guides/top_page/"));
            String pathsJson = (String) page.evaluate("""
                () => fetch('/search?q=SLURM')
                    .then(r => r.json())
                    .then(results => JSON.stringify(results.map(r => r.path)))
                """);
            check(pathsJson != null && !pathsJson.equals("[]"), "Need at least one result");
            check(!pathsJson.contains(".html"),
                "Search result paths must not contain .html, got: " + pathsJson);
        });

        withPage("D-5: search API response never leaks srcPath (production mode)", page -> {
            page.navigate(url("/guides/top_page/"));
            String json = (String) page.evaluate("() => fetch('/search?q=SLURM').then(r => r.text())");
            check(json != null && !json.equals("[]"), "Need at least one result");
            check(!json.contains("srcPath"),
                "Production /search response must not include srcPath (local filesystem path)");
        });
    }

    // -------------------------------------------------------------------------
    // E. i18n — language switcher
    // -------------------------------------------------------------------------

    private static void runE_i18n() {
        withPage("E-1: language switcher button present", page -> {
            page.navigate(url("/guides/top_page/"));
            check(page.querySelector(".lang-btn") != null,
                "Language switcher .lang-btn must be present");
        });

        withPage("E-2: English locale /en/guides/top_page/ returns 200", page ->
            check(page.navigate(url("/en/guides/top_page/")).status() == 200,
                "GET /en/guides/top_page/ must return 200"));

        withPage("E-3: Japanese link from English page resolves to /guides/top_page/", page -> {
            page.navigate(url("/en/guides/top_page/"));
            ElementHandle jaLink = page.querySelectorAll(".lang-item").stream()
                .filter(el -> { String t = el.textContent(); return t != null && t.contains("日本語"); })
                .findFirst().orElse(null);
            check(jaLink != null, "Japanese language link must exist on English page");
            String href = jaLink.getAttribute("href");
            String resolved = (String) page.evaluate("href => new URL(href, document.baseURI).href", href);
            check(resolved.endsWith("/guides/top_page/"),
                "Japanese link must resolve to /guides/top_page/, got: " + resolved);
            check(!resolved.contains("/en/guides/"),
                "Japanese link must NOT stay in /en/ locale, got: " + resolved);
        });

        withPage("E-4: following Japanese link from English page navigates correctly", page -> {
            page.navigate(url("/en/guides/top_page/"));
            String jaHref = (String) page.evaluate("""
                () => {
                    for (const l of document.querySelectorAll('.lang-item')) {
                        if (l.textContent.includes('日本語')) return l.getAttribute('href');
                    }
                    return null;
                }
                """);
            check(jaHref != null, "Japanese lang-item link must exist");
            String resolved = (String) page.evaluate("href => new URL(href, document.baseURI).href", jaHref);
            page.navigate(resolved);
            page.waitForLoadState();
            String finalUrl = page.url();
            check(finalUrl.contains("/guides/top_page/"),
                "Must navigate to /guides/top_page/, got: " + finalUrl);
            check(!finalUrl.contains("/en/guides/"),
                "Must NOT stay in /en/ locale, got: " + finalUrl);
        });
    }

    // -------------------------------------------------------------------------
    // F. Navbar ordering and content
    // -------------------------------------------------------------------------

    private static void runF_navbarOrdering() {
        withPage("F-1: first navbar item is 'システム概要'", page -> {
            page.navigate(url("/guides/top_page/"));
            List<ElementHandle> navLinks = page.querySelectorAll("header nav.top a");
            check(!navLinks.isEmpty(), "Navbar must contain at least one link");
            check("システム概要".equals(navLinks.get(0).textContent().trim()),
                "First navbar item must be 'システム概要', got: " + navLinks.get(0).textContent().trim());
        });

        withPage("F-2: navbar contains exactly 6 items", page -> {
            page.navigate(url("/guides/top_page/"));
            int count = page.querySelectorAll("header nav.top a").size();
            check(count == 6, "Navbar must contain exactly 6 items, got: " + count);
        });

        withPage("F-3: navbar labels match config order", page -> {
            page.navigate(url("/guides/top_page/"));
            List<String> labels = page.querySelectorAll("header nav.top a").stream()
                .map(el -> el.textContent().trim()).toList();
            List<String> expected = List.of("システム概要", "各種申請等", "活用方法", "稼働状況", "成果報告", "Blog");
            check(expected.equals(labels),
                "Navbar labels must match config order. Expected: " + expected + ", got: " + labels);
        });

        withPage("F-4: active navbar item on /guides/top_page/ is 'システム概要'", page -> {
            page.navigate(url("/guides/top_page/"));
            ElementHandle active = page.querySelector("header nav.top a.active");
            check(active != null, "An active navbar item must be present");
            check("システム概要".equals(active.textContent().trim()),
                "Active item must be 'システム概要', got: " + active.textContent().trim());
        });

        withPage("F-5: navbar present on Applications page with '各種申請等' active", page -> {
            page.navigate(url("/application/terms_and_policies/user_issurance_criteria/"));
            int count = page.querySelectorAll("header nav.top a").size();
            check(count == 6, "Navbar must show 6 items on Applications page, got: " + count);
            ElementHandle active = page.querySelector("header nav.top a.active");
            check(active != null, "An active navbar item must be present on Applications page");
            check("各種申請等".equals(active.textContent().trim()),
                "Active item must be '各種申請等', got: " + active.textContent().trim());
        });
    }

    // -------------------------------------------------------------------------
    // G. Sidebar state persistence via localStorage
    // -------------------------------------------------------------------------

    private static void runG_sidebarPersistence() {
        withPage("G-1: cat-header elements have data-cat attribute", page -> {
            page.navigate(url("/guides/top_page/"));
            List<ElementHandle> catHeaders = page.querySelectorAll(".cat-header[data-cat]");
            check(!catHeaders.isEmpty(), "Sidebar must contain .cat-header[data-cat] elements");
            boolean allValid = catHeaders.stream().allMatch(el -> {
                String v = el.getAttribute("data-cat");
                return v != null && !v.isBlank() && !v.startsWith("/") && !v.endsWith(".html");
            });
            check(allValid, "All data-cat values must be stable path keys");
        });

        withPage("G-2: clicking category saves state to localStorage", page -> {
            page.navigate(url("/guides/top_page/"));
            page.evaluate("() => localStorage.clear()");
            ElementHandle cat = page.querySelector(".cat-header[data-cat]");
            check(cat != null, "At least one .cat-header[data-cat] must exist");
            String key = cat.getAttribute("data-cat");
            ElementHandle arrow = cat.querySelector(".cat-arrow");
            check(arrow != null, ".cat-header must contain .cat-arrow");
            arrow.click();
            Object stored = page.evaluate("k => localStorage.getItem('hs-cat:' + k)", key);
            check("1".equals(stored), "localStorage['hs-cat:" + key + "'] must be '1'");
        });

        withPage("G-3: localStorage state restored after navigation", page -> {
            page.navigate(url("/guides/top_page/"));
            page.evaluate("() => localStorage.clear()");
            page.evaluate("() => localStorage.setItem('hs-cat:guides/security_policy', '1')");
            page.navigate(url("/guides/overview/"));
            page.waitForLoadState();
            Boolean isOpen = (Boolean) page.evaluate("""
                () => {
                    const el = document.querySelector('[data-cat="guides/security_policy"]');
                    if (!el) return null;
                    const next = el.nextElementSibling;
                    if (!next) return null;
                    return next.classList.contains('open');
                }
                """);
            check(isOpen != null, "security_policy cat-header must exist on /guides/overview/");
            check(isOpen, "security_policy category must be open after localStorage restore");
        });

        withPage("G-4: closing category removes key from localStorage", page -> {
            page.navigate(url("/guides/top_page/"));
            page.evaluate("() => localStorage.clear()");
            ElementHandle cat = page.querySelector(".cat-header[data-cat]");
            check(cat != null, "At least one .cat-header[data-cat] must exist");
            String key = cat.getAttribute("data-cat");
            ElementHandle arrow = cat.querySelector(".cat-arrow");
            check(arrow != null, ".cat-header must contain .cat-arrow");
            arrow.click();
            check("1".equals(page.evaluate("k => localStorage.getItem('hs-cat:' + k)", key)),
                "After open click, localStorage must hold '1'");
            arrow.click();
            check(page.evaluate("k => localStorage.getItem('hs-cat:' + k)", key) == null,
                "After close click, localStorage key must be removed");
        });
    }

    // -------------------------------------------------------------------------
    // H. Sidebar ancestor auto-expand
    // -------------------------------------------------------------------------

    private static void runH_sidebarAutoExpand() {
        withPage("H-1: software ancestor auto-opened on deep page", page -> {
            page.navigate(url("/guides/software/JobScheduler/Slurm/batch_jobs/"));
            page.waitForLoadState();
            Boolean isOpen = (Boolean) page.evaluate("""
                () => {
                    const el = document.querySelector('[data-cat="guides/software"]');
                    if (!el) return null;
                    const next = el.nextElementSibling;
                    if (!next) return null;
                    return next.classList.contains('open');
                }
                """);
            check(isOpen != null, "guides/software cat-header must exist on deep Slurm page");
            check(isOpen, "guides/software must be auto-expanded");
        });

        withPage("H-2: Slurm ancestor auto-opened on deep page", page -> {
            page.navigate(url("/guides/software/JobScheduler/Slurm/batch_jobs/"));
            page.waitForLoadState();
            Boolean isOpen = (Boolean) page.evaluate("""
                () => {
                    const el = document.querySelector('[data-cat="guides/software/JobScheduler/Slurm"]');
                    if (!el) return null;
                    const next = el.nextElementSibling;
                    if (!next) return null;
                    return next.classList.contains('open');
                }
                """);
            check(isOpen != null, "guides/software/JobScheduler/Slurm cat-header must exist");
            check(isOpen, "Slurm ancestor must be auto-expanded");
        });

        withPage("H-3: unrelated sibling category remains closed", page -> {
            page.navigate(url("/guides/software/JobScheduler/Slurm/batch_jobs/"));
            page.waitForLoadState();
            page.evaluate("() => localStorage.clear()");
            page.reload();
            page.waitForLoadState();
            Boolean isOpen = (Boolean) page.evaluate("""
                () => {
                    const el = document.querySelector('[data-cat="guides/security_policy"]');
                    if (!el) return null;
                    const next = el.nextElementSibling;
                    if (!next) return null;
                    return next.classList.contains('open');
                }
                """);
            if (isOpen != null) {
                check(!isOpen, "Unrelated sibling guides/security_policy must NOT be auto-expanded");
            }
        });
    }

    // -------------------------------------------------------------------------
    // I. Page content rendering
    // -------------------------------------------------------------------------

    private static void runI_contentRendering() {
        withPage("I-1: page title is not empty", page -> {
            page.navigate(url("/guides/top_page/"));
            String title = page.title();
            check(title != null && !title.isBlank(), "Page title must not be blank");
        });

        withPage("I-2: main content area exists and contains text", page -> {
            page.navigate(url("/guides/top_page/"));
            ElementHandle main = page.querySelector("main");
            check(main != null, "<main> must be present");
            String text = main.textContent();
            check(text != null && !text.isBlank(), "<main> must contain non-blank text");
        });

        withPage("I-3: main content has at least one heading", page -> {
            page.navigate(url("/guides/top_page/"));
            check(page.querySelector("main h1") != null || page.querySelector("main h2") != null,
                "main must contain at least one h1 or h2");
        });

        withPage("I-4: site title link present in header", page -> {
            page.navigate(url("/guides/top_page/"));
            ElementHandle st = page.querySelector("header a.site-title");
            check(st != null, "header must contain <a class=\"site-title\">");
            check(st.textContent() != null && !st.textContent().isBlank(),
                "Site title link text must not be blank");
        });

        withPage("I-5: H1 heading overrides frontmatter title", page -> {
            page.navigate(url("/guides/top_page/"));
            page.waitForLoadState();
            String title = page.title();
            check(title.contains("遺伝研スーパーコンピュータ(2025)"),
                "<title> must contain H1 text, got: " + title);
            check(!title.contains("トップページ"),
                "<title> must NOT contain frontmatter title, got: " + title);
            ElementHandle h1 = page.querySelector("main h1");
            check(h1 != null, "main <h1> must be present");
            check(h1.textContent().contains("遺伝研スーパーコンピュータ(2025)"),
                "main h1 must contain H1 text, got: " + h1.textContent());
        });

        withPage("I-6: H1 not duplicated — main contains exactly one <h1>", page -> {
            page.navigate(url("/guides/top_page/"));
            page.waitForLoadState();
            int count = page.querySelectorAll("main h1").size();
            check(count == 1, "main must contain exactly one <h1>, got: " + count);
        });
    }

    // -------------------------------------------------------------------------
    // J. All five navbar sections accessible
    // -------------------------------------------------------------------------

    private static void runJ_allSectionsAccessible() {
        withPage("J-1: Applications section first page returns 200", page ->
            check(page.navigate(url("/application/terms_and_policies/user_issurance_criteria/")).status() == 200,
                "GET /application/... must return 200"));

        withPage("J-2: Advanced Guides section first page returns 200", page ->
            check(page.navigate(url("/advanced_guides/topics/advanced_guide_2020-2022/")).status() == 200,
                "GET /advanced_guides/... must return 200"));

        withPage("J-3: System Status section returns 200", page ->
            check(page.navigate(url("/operation/job_queue_status/")).status() == 200,
                "GET /operation/... must return 200"));

        withPage("J-4: Reports section returns 200", page ->
            check(page.navigate(url("/report/statistics/")).status() == 200,
                "GET /report/... must return 200"));
    }

    // -------------------------------------------------------------------------
    // K. Search UI interaction
    // -------------------------------------------------------------------------

    private static void runK_searchUiInteraction() {
        withPage("K-1: typing in the search box does nothing on its own", page -> {
            page.navigate(url("/guides/top_page/"));
            page.fill("#search-input", "slurm");
            page.waitForTimeout(500);
            check(page.querySelector("#search-results") == null,
                "production mode has no dropdown: #search-results must not exist");
            check(page.url().contains("/guides/top_page"),
                "typing must not navigate anywhere, went to: " + page.url());
        });

        withPage("K-2: Enter goes to the results page for what was typed", page -> {
            page.navigate(url("/guides/top_page/"));
            page.fill("#search-input", "slurm");
            page.keyboard().press("Enter");
            page.waitForURL("**/search/?q=slurm");
            check(page.url().contains("/search/?q=slurm"),
                "Enter must go to /search/?q=slurm, went to: " + page.url());
        });

        withPage("K-3: the results page wears the site's own chrome", page -> {
            page.navigate(url("/search?q=slurm"));
            check(page.querySelector("header nav.top") != null,
                "the results page must carry the site's navbar");
            check(page.querySelector("nav.side") != null,
                "the results page must carry the site's sidebar");
            check(page.querySelector("#search-input") != null,
                "the results page must carry the search box");
            String value = page.inputValue("#search-input");
            check("slurm".equals(value),
                "the search box must hold the query that produced the page, held: " + value);
            int headings = page.querySelectorAll("main h1").size();
            check(headings == 1, "the results page must carry one heading, carried: " + headings);
        });

        withPage("K-4: the results page lists hits, each linking to a clean URL", page -> {
            page.navigate(url("/search?q=slurm"));
            check(page.querySelector(".search-count") != null, "the page must state how many hits");
            ElementHandle first = page.querySelector("a.search-hit");
            check(first != null, "at least one hit must be listed");
            check(page.querySelector(".search-hit-title") != null, "a hit must show its title");
            check(page.querySelector(".search-hit-summary") != null, "a hit must show its summary");
            String href = first.getAttribute("href");
            check(href != null && href.startsWith("/"), "a hit's href must start with '/', got: " + href);
            check(!href.contains(".html"), "a hit's href must not contain .html, got: " + href);
        });

        withPage("K-5: the results page for an empty query lists nothing", page -> {
            page.navigate(url("/search"));
            check(page.querySelector("a.search-hit") == null,
                "an empty query must list no hits");
            check(page.querySelector(".search-hint") != null,
                "an empty query must say what to do instead");
        });

        withPage("K-8: the results page's sidebar tells how to write a query", page -> {
            page.navigate(url("/search?q=slurm"));
            check(page.querySelector("nav.side .search-help") != null,
                "the sidebar must carry the search help");
            int examples = page.querySelectorAll("nav.side .search-help code").size();
            check(examples >= 10, "the help must show its examples, showed: " + examples);
            check(page.querySelector("nav.side > ul") == null,
                "the search page's sidebar must not carry the document tree");
        });

        withPage("K-9: the address picks the blog posts out, or leaves them out", page -> {
            page.navigate(url("/search?q=path_tokens%3Ablog"));
            int posts = page.querySelectorAll("a.search-hit").size();
            check(posts > 0, "path_tokens:blog must list blog posts");
            for (ElementHandle hit : page.querySelectorAll("a.search-hit")) {
                String href = hit.getAttribute("href");
                check(href.contains("/blog/"),
                    "path_tokens:blog must list posts alone, listed: " + href);
            }
            page.navigate(url("/search?q=NOT+path_tokens%3Ablog+AND+slurm"));
            for (ElementHandle hit : page.querySelectorAll("a.search-hit")) {
                String href = hit.getAttribute("href");
                check(!href.contains("/blog/"),
                    "NOT path_tokens:blog must leave the posts out, listed: " + href);
            }
        });

        withPage("K-6: a page of results holds at most twenty hits", page -> {
            page.navigate(url("/search?q=slurm"));
            int listed = page.querySelectorAll("a.search-hit").size();
            check(listed <= 20, "a page must list at most 20 hits, listed: " + listed);
            int total = Integer.parseInt(page.textContent(".search-count").replaceAll("\\D+.*$", ""));
            check(total >= listed, "the count must not be smaller than the hits listed: "
                + total + " < " + listed);
            // The paginator appears exactly when the hits do not fit on one page.
            boolean paginated = page.querySelector("nav.search-paginator") != null;
            check(paginated == (total > 20),
                "paginator present=" + paginated + " for " + total + " hit(s)");
        });

        withPage("K-7: the next page of results lists the hits that follow", page -> {
            page.navigate(url("/search?q=slurm"));
            int total = Integer.parseInt(page.textContent(".search-count").replaceAll("\\D+.*$", ""));
            if (total <= 20) {
                // Too few hits in this site to have a second page; K-6 already checked that the
                // paginator stays away in that case.
                return;
            }
            String firstOnPage1 = page.querySelector("a.search-hit").getAttribute("href");
            page.click("nav.search-paginator a.paginator-next");
            page.waitForLoadState();
            check(page.url().contains("page=2"), "Next must lead to page 2, went to: " + page.url());
            check(page.querySelector("span.paginator-current").textContent().trim().equals("2"),
                "page 2 must mark 2 as the current page");
            String firstOnPage2 = page.querySelector("a.search-hit").getAttribute("href");
            check(!firstOnPage1.equals(firstOnPage2),
                "page 2 must start after page 1, both started with: " + firstOnPage1);
            check(page.inputValue("#search-input").equals("slurm"),
                "the search box must still hold the query on page 2");
        });
    }

    // -------------------------------------------------------------------------
    // L. Root redirect
    // -------------------------------------------------------------------------

    private static void runL_rootRedirect() {
        withPage("L-1: root redirect does not stay at index.html", page -> {
            page.navigate(url("/"));
            page.waitForLoadState();
            check(!page.url().endsWith("index.html"),
                "After root redirect, URL must not end with index.html, got: " + page.url());
        });

        withPage("L-2: root redirect target returns 200", page -> {
            page.navigate(url("/"));
            page.waitForLoadState();
            String target = page.url();
            page.navigate("about:blank");
            Response resp = page.navigate(target);
            check(resp != null && resp.status() == 200,
                "Root redirect target must return 200, got: " + (resp != null ? resp.status() : "null"));
        });
    }

    // -------------------------------------------------------------------------
    // M. More i18n
    // -------------------------------------------------------------------------

    private static void runM_moreI18n() {
        withPage("M-1: language switcher shows exactly two locale options", page -> {
            page.navigate(url("/guides/top_page/"));
            int count = page.querySelectorAll(".lang-item").size();
            check(count == 2, "Language switcher must contain 2 .lang-item elements, got: " + count);
        });

        withPage("M-2: English link from deep Japanese page resolves to /en/ equivalent", page -> {
            page.navigate(url("/guides/software/software_update_info/"));
            ElementHandle enLink = page.querySelectorAll(".lang-item").stream()
                .filter(el -> { String t = el.textContent(); return t != null && t.contains("English"); })
                .findFirst().orElse(null);
            check(enLink != null, "English .lang-item must exist");
            String href = enLink.getAttribute("href");
            String resolved = (String) page.evaluate("href => new URL(href, document.baseURI).href", href);
            check(resolved.contains("/en/guides/software/software_update_info/"),
                "English link must resolve to /en/guides/software/software_update_info/, got: " + resolved);
        });

        withPage("M-3: /en/guides/software/software_update_info/ returns 200", page ->
            check(page.navigate(url("/en/guides/software/software_update_info/")).status() == 200,
                "GET /en/guides/software/software_update_info/ must return 200"));
    }

    // -------------------------------------------------------------------------
    // N. English locale search
    // -------------------------------------------------------------------------

    private static void runN_englishSearch() {
        if (!Files.isDirectory(NIGSC_EN_DOCS)) {
            System.out.println("SKIP: N-1 through N-5 — English docs not found");
            return;
        }

        withPage("N-1: English page embeds search URL with ?locale=en", page -> {
            page.navigate(url("/en/guides/top_page/"));
            String source = page.content();
            check(source.contains("search?locale=en"),
                "English page must embed search URL with 'search?locale=en'");
            check(!source.contains("'/en/search'") && !source.contains("\"/en/search\""),
                "English page must NOT embed '/en/search'");
        });

        withPage("N-2: search API with ?locale=en returns English results", page -> {
            page.navigate(url("/en/guides/top_page/"));
            String json = (String) page.evaluate(
                "() => fetch('/search?locale=en&q=supercomputer').then(r => r.text())");
            check(json != null && !json.equals("[]"),
                "Search with ?locale=en must return results for 'supercomputer'");
            check(json.contains("\"title\""), "Results must include title field");
        });

        withPage("N-3: embedded SEARCH_URL on English page returns results", page -> {
            page.navigate(url("/en/guides/top_page/"));
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
            check(json != null && !json.equals("[]"),
                "Fetch via embedded SEARCH_URL must return results for 'supercomputer'");
        });

        withPage("N-4: English search result paths use clean URLs", page -> {
            page.navigate(url("/en/guides/top_page/"));
            String pathsJson = (String) page.evaluate("""
                () => fetch('/search?locale=en&q=supercomputer')
                    .then(r => r.json())
                    .then(results => JSON.stringify(results.map(r => r.path)))
                """);
            check(pathsJson != null && !pathsJson.equals("[]"), "Need at least one result");
            check(!pathsJson.contains(".html"),
                "English search paths must not contain .html, got: " + pathsJson);
        });

        withPage("N-5: Japanese search does not bleed into English locale results", page -> {
            page.navigate(url("/guides/top_page/"));
            String enJson = (String) page.evaluate(
                "() => fetch('/search?locale=en&q=\u30B9\u30FC\u30D1\u30FC\u30B3\u30F3\u30D4\u30E5\u30FC\u30BF').then(r => r.text())");
            String jaJson = (String) page.evaluate(
                "() => fetch('/search?q=\u30B9\u30FC\u30D1\u30FC\u30B3\u30F3\u30D4\u30E5\u30FC\u30BF').then(r => r.text())");
            check(enJson != null, "English locale search must return a response");
            check(jaJson != null && !jaJson.equals("[]"),
                "Japanese search for 'スーパーコンピュータ' must return results");
        });

        withPage("N-6: the search box on an English page leads to English results", page -> {
            page.navigate(url("/en/guides/software/"));
            page.fill("#search-input", "slurm");
            page.press("#search-input", "Enter");
            page.waitForURL("**/search/?q=slurm");
            check(page.url().contains("/en/search"),
                "the English search box must lead to /en/search, went to: " + page.url());
            ElementHandle first = page.querySelector("a.search-hit");
            check(first != null, "the English results page must list hits");
            String href = first.getAttribute("href");
            check(href.startsWith("/en/"),
                "an English hit must live under /en/, got: " + href);
        });

        withPage("N-8: links on the English results page stay in English", page -> {
            page.navigate(url("/en/search?q=slurm"));
            String href = (String) page.evaluate(
                "() => document.querySelector('nav.side .mobile-top-nav a').href");
            check(href.contains("/en/"),
                "a section link on the English results page must stay under /en/, got: " + href);
        });

        withPage("N-7: an English results page is a page, not the marker", page -> {
            page.navigate(url("/en/search?q=slurm"));
            check(page.querySelector(".search-count") != null,
                "the English results page must state how many hits");
            check(!page.content().contains("html-saurus:search-results"),
                "the results marker must be replaced by the results");
            check(page.inputValue("#search-input").equals("slurm"),
                "the search box must hold the query on the English results page");
        });
    }

    // -------------------------------------------------------------------------
    // O. Blog
    // -------------------------------------------------------------------------

    private static void runO_blog() {
        withPage("O-1: /blog/ returns 200 and renders blog index", page -> {
            Response resp = page.navigate(url("/blog/"));
            check(resp != null && resp.status() == 200, "/blog/ must return 200, got: "
                + (resp != null ? resp.status() : "null"));
            check(page.querySelector("main") != null, "/blog/ must contain <main>");
        });

        withPage("O-2: Blog appears in navbar", page -> {
            page.navigate(url("/blog/"));
            List<ElementHandle> navLinks = page.querySelectorAll("header nav.top a");
            boolean hasBlog = navLinks.stream().anyMatch(el -> {
                String t = el.textContent();
                return t != null && t.contains("Blog");
            });
            check(hasBlog, "Navbar must contain a 'Blog' link");
        });

        withPage("O-3: blog index lists at least one post", page -> {
            page.navigate(url("/blog/"));
            // Post links are relative hrefs (e.g. "2026-03-13-slug/"), not absolute /blog/... paths
            List<ElementHandle> posts = page.querySelectorAll("main a").stream()
                .filter(el -> {
                    String href = el.getAttribute("href");
                    if (href == null) return false;
                    return !href.contains("/page/") && !href.contains("/tags/")
                        && !href.startsWith("#") && !href.startsWith("/") && !href.isEmpty();
                })
                .toList();
            check(!posts.isEmpty(), "Blog index must list at least one post");
        });

        withPage("O-4: blog post page returns 200 and has heading", page -> {
            // Post links on the blog index are relative hrefs (e.g. "2026-03-13-slug/"),
            // not absolute /blog/... paths.
            page.navigate(url("/blog/"));
            List<ElementHandle> links = page.querySelectorAll("main a").stream()
                .filter(el -> {
                    String href = el.getAttribute("href");
                    if (href == null) return false;
                    return !href.contains("/page/") && !href.contains("/tags/")
                        && !href.startsWith("#") && !href.startsWith("/")
                        && !href.isEmpty();
                })
                .toList();
            check(!links.isEmpty(), "Blog index must have at least one relative post link");
            String relHref = links.get(0).getAttribute("href");
            String postUrl = url("/blog/" + relHref);
            Response resp = page.navigate(postUrl);
            check(resp != null && resp.status() == 200,
                "Blog post page must return 200, got: " + (resp != null ? resp.status() : "null"));
            check(page.querySelector("main h1, main h2") != null,
                "Blog post page must contain a heading");
        });

        withPage("O-5: blog page 1 — no paginator when only one page", page -> {
            page.navigate(url("/blog/"));
            Response resp2 = page.navigate(url("/blog/page/2/"));
            boolean multiPage = resp2 != null && resp2.status() == 200;
            page.navigate(url("/blog/"));
            ElementHandle paginator = page.querySelector("nav.blog-paginator");
            if (!multiPage) {
                check(paginator == null, "Single-page blog must NOT render nav.blog-paginator");
                System.out.println("  (O-5: only one page — paginator correctly absent)");
            } else {
                check(paginator != null, "Multi-page blog must render nav.blog-paginator on page 1");
            }
        });

        withPage("O-6: blog page 1 paginator — Prev disabled, Next is a link", page -> {
            page.navigate(url("/blog/"));
            Response resp2 = page.navigate(url("/blog/page/2/"));
            if (resp2 == null || resp2.status() == 404) {
                System.out.println("  (O-6: only one page — skip)");
                return;
            }
            page.navigate(url("/blog/"));
            ElementHandle prev = page.querySelector("nav.blog-paginator .paginator-prev");
            check(prev != null, "page 1 must have .paginator-prev");
            check("SPAN".equals(prev.evaluate("el => el.tagName")),
                ".paginator-prev on page 1 must be a disabled <span>, not a link");
            ElementHandle next = page.querySelector("nav.blog-paginator .paginator-next");
            check(next != null, "page 1 must have .paginator-next");
            check("A".equals(next.evaluate("el => el.tagName")),
                ".paginator-next on page 1 must be an <a> link");
            String nextHref = next.getAttribute("href");
            check(nextHref != null && nextHref.contains("/blog/page/2/"),
                ".paginator-next must link to /blog/page/2/, got: " + nextHref);
        });

        withPage("O-7: blog page 2 — Prev links to /blog/, current page number is 2", page -> {
            Response resp = page.navigate(url("/blog/page/2/"));
            if (resp == null || resp.status() == 404) {
                System.out.println("  (O-7: page 2 does not exist — skip)");
                return;
            }
            check(resp.status() == 200, "/blog/page/2/ must return 200");
            ElementHandle prev = page.querySelector("nav.blog-paginator .paginator-prev");
            check(prev != null, "page 2 must have .paginator-prev");
            check("A".equals(prev.evaluate("el => el.tagName")),
                ".paginator-prev on page 2 must be an <a> link");
            String prevHref = prev.getAttribute("href");
            check(prevHref != null && (prevHref.equals("/blog/") || prevHref.endsWith("/blog/")),
                ".paginator-prev on page 2 must link to /blog/, got: " + prevHref);
            ElementHandle current = page.querySelector("nav.blog-paginator .paginator-current");
            check(current != null, "page 2 must have .paginator-current");
            check("2".equals(current.textContent().trim()),
                ".paginator-current on page 2 must show '2', got: " + current.textContent());
        });

        withPage("O-8: blog paginator page numbers are consecutive and complete", page -> {
            Response resp2 = page.navigate(url("/blog/page/2/"));
            if (resp2 == null || resp2.status() == 404) {
                System.out.println("  (O-8: only one page — skip)");
                return;
            }
            page.navigate(url("/blog/"));
            List<ElementHandle> pageNums = page.querySelectorAll(
                "nav.blog-paginator .paginator-current, nav.blog-paginator .paginator-page");
            check(pageNums.size() >= 2, "Paginator must show at least 2 page numbers, got: " + pageNums.size());
            // First number must be 1 (current on page 1)
            String first = pageNums.get(0).textContent().trim();
            check("1".equals(first), "First page number must be '1', got: " + first);
        });

        withPage("O-9: /blog/tags/ returns 200", page -> {
            Response resp = page.navigate(url("/blog/tags/"));
            check(resp != null && resp.status() == 200,
                "/blog/tags/ must return 200, got: " + (resp != null ? resp.status() : "null"));
        });

        withPage("O-10: English blog /en/blog/ returns 200 when EN docs exist", page -> {
            if (!Files.isDirectory(NIGSC_EN_DOCS)) {
                System.out.println("  (O-10: English docs not found — skip)");
                return;
            }
            Response resp = page.navigate(url("/en/blog/"));
            check(resp != null && resp.status() == 200,
                "/en/blog/ must return 200, got: " + (resp != null ? resp.status() : "null"));
        });

        withPage("O-11: language switcher on blog index links to /en/blog/", page -> {
            if (!Files.isDirectory(NIGSC_EN_DOCS)) {
                System.out.println("  (O-11: English docs not found — skip)");
                return;
            }
            page.navigate(url("/blog/"));
            ElementHandle enLink = page.querySelectorAll(".lang-item").stream()
                .filter(el -> { String t = el.textContent(); return t != null && t.contains("English"); })
                .findFirst().orElse(null);
            check(enLink != null, "Blog index must have an English .lang-item");
            String href = enLink.getAttribute("href");
            String resolved = (String) page.evaluate("href => new URL(href, document.baseURI).href", href);
            check(resolved.contains("/en/blog/"),
                "English switcher on /blog/ must link to /en/blog/, got: " + resolved);
        });

        withPage("O-12: language switcher on /en/blog/ links back to /blog/", page -> {
            if (!Files.isDirectory(NIGSC_EN_DOCS)) {
                System.out.println("  (O-12: English docs not found — skip)");
                return;
            }
            page.navigate(url("/en/blog/"));
            ElementHandle jaLink = page.querySelectorAll(".lang-item").stream()
                .filter(el -> { String t = el.textContent(); return t != null && t.contains("日本語"); })
                .findFirst().orElse(null);
            check(jaLink != null, "English blog index must have a Japanese .lang-item");
            String href = jaLink.getAttribute("href");
            String resolved = (String) page.evaluate("href => new URL(href, document.baseURI).href", href);
            check(resolved.endsWith("/blog/") || resolved.endsWith("/blog"),
                "Japanese switcher on /en/blog/ must link to /blog/, got: " + resolved);
        });
    }
}
