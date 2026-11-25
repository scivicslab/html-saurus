package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Browser check of the portal's left pane after the Search tab (search scopes) and the dropdown menu of the Revise panel
 * and the two-level Projects list were merged: the menu offers every panel, Search is the one shown first, each panel
 * opens from the menu, the choice survives a reload, and a ticked search category narrows a search in the right pane.
 *
 * <p>Starts its own portal on a free port over two throw-away projects of a series ({@code guide1}, {@code guide2}) and one
 * project of its own ({@code beta}); every page holds the word {@code needle}. A {@code main()} program outside the build:
 * <pre>
 *   java -cp &lt;test-classes and a classpath with Playwright&gt; com.scivicslab.htmlsaurus.MergedPortalE2E
 * </pre>
 * Prints one line per check and exits 1 when any fails.
 */
public class MergedPortalE2E {

    static int failures = 0;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static void check(boolean ok, String what, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : ": " + detail));
        if (!ok) failures++;
    }

    static Path project(Path works, String name, String... pages) throws Exception {
        Path dir = works.resolve(name);
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docusaurus.config.js"), "module.exports = {};");
        for (String page : pages) {
            Path md = dir.resolve("docs/" + page + ".md");
            Files.createDirectories(md.getParent());
            Files.writeString(md, "---\ntitle: " + page + "\n---\n\nThe needle is in " + page + ".\n");
        }
        BuildStages.build(dir.resolve("docs"), dir.resolve("static-html"), false);
        BuildStages.reindex(dir.resolve("docs"), dir.resolve("search-index"));
        return dir;
    }

    static String send(String base, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) b.header("Content-Type", "application/json");
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    public static void main(String[] args) throws Exception {
        Path works = Files.createTempDirectory("merged-portal-e2e");
        List<Path> projects = List.of(project(works, "guide1", "Setup/Install"), project(works, "guide2", "Setup/Daily"),
                project(works, "beta", "Intro"));
        HttpServer http = new PortalServer(works, projects, 0, null, 0, BasePath.none(), null, false).start();
        String base = "http://localhost:" + http.getAddress().getPort();
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(20_000);
            List<String> errors = new ArrayList<>();
            page.onPageError(e -> errors.add(e.toString()));
            page.navigate(base + "/");
            page.waitForSelector("#pane-menu");

            // The dropdown menu offers every panel, Search first, and shows the Search panel on a fresh browser.
            List<String> options = page.locator("#pane-menu option").allInnerTexts();
            check(options.equals(List.of("Search", "Projects", "Import", "Batch Job", "Export", "Revise")),
                    "the left pane's menu lists Search first and the five other panels", options.toString());
            check("search".equals(page.inputValue("#pane-menu")), "Search is the panel shown on a fresh browser", page.inputValue("#pane-menu"));
            check(page.isVisible("#tab-search") && !page.isVisible("#tab-projects") && !page.isVisible("#tab-revise"),
                    "only the Search panel is visible", "search=" + page.isVisible("#tab-search"));
            check(page.locator(".tab-btn").count() == 0, "no row of tab buttons is left beside the menu", "" + page.locator(".tab-btn").count());
            check(page.isVisible("#search-input") && page.isVisible("#scope-list"), "the Search panel holds the form and the scopes", "");

            // Each panel opens from the menu; the Projects list is the two-level one.
            page.selectOption("#pane-menu", "projects");
            check(page.isVisible("#tab-projects") && !page.isVisible("#tab-search"), "Projects opens from the menu", "");
            check(page.locator("details.project-series").count() == 1 && page.locator("details.project-series summary").innerText().startsWith("guide"),
                    "the Projects panel groups guide1 and guide2 as a series and lists beta alone",
                    page.locator("#tab-projects").innerText());
            page.selectOption("#pane-menu", "revise");
            check(page.isVisible("#tab-revise") && !page.isVisible("#tab-projects"), "Revise opens from the menu", "");
            check(page.locator("#tab-revise").innerText().toLowerCase().contains("revis"), "the Revise panel has its content",
                    page.locator("#tab-revise").innerText());
            for (String panel : List.of("import", "batch", "export")) {
                page.selectOption("#pane-menu", panel);
                check(page.isVisible("#tab-" + panel), panel + " opens from the menu", "");
            }

            // The choice is kept for the next visit.
            page.selectOption("#pane-menu", "revise");
            page.reload();
            page.waitForSelector("#pane-menu");
            check("revise".equals(page.inputValue("#pane-menu")) && page.isVisible("#tab-revise"), "a reload keeps the chosen panel", page.inputValue("#pane-menu"));
            page.selectOption("#pane-menu", "search");

            // Search scopes through the browser: a ticked category narrows the keyword search in the right pane.
            String scopes = send(base, "GET", "/api/search-scopes", null);
            check(scopes.contains("\"categories\":[]") && scopes.contains("\"rest\""), "/api/search-scopes answers", scopes);
            String added = send(base, "POST", "/api/search-scopes", "{\"name\":\"Install\",\"query\":\"path_idx:\\\\/guide1\\\\/*\"}");
            check(added.contains("\"id\":\"c1\""), "a search category can be added", added);
            page.reload();
            page.waitForSelector(".scope-cb");
            check(page.locator(".scope-cb").count() == 2, "the category and Everything else are listed", "" + page.locator(".scope-cb").count());

            String all = searchIn(page, "needle", List.of());
            check(all.contains("Install") && all.contains("Daily") && all.contains("Intro"), "with nothing ticked every page is found", all);
            String narrowed = searchIn(page, "needle", List.of("c1"));
            check(narrowed.contains("Install") && !narrowed.contains("Daily") && !narrowed.contains("Intro"),
                    "a ticked category narrows the search to its pages", narrowed);
            String rest = searchIn(page, "needle", List.of("rest"));
            check(!rest.contains("Install") && rest.contains("Daily") && rest.contains("Intro"), "Everything else is the pages no category selects", rest);
            check(errors.isEmpty(), "no script error on the page", errors.toString());
            browser.close();
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Ticks exactly {@code ids}, searches in the Search panel, and returns the text of the results in the right pane. */
    static String searchIn(Page page, String text, List<String> ids) {
        page.selectOption("#pane-menu", "search");
        for (var cb : page.locator(".scope-cb").all()) {
            boolean want = ids.contains(cb.getAttribute("value"));
            if (cb.isChecked() != want) cb.setChecked(want);
        }
        page.fill("#search-input", text);
        page.click("#search-btn");
        // Polled from here: the portal's content security policy does not allow the string form of waitForFunction.
        Frame frame = null;
        String body = "";
        for (int i = 0; i < 80; i++) {
            page.waitForTimeout(250);
            frame = page.frame("doc-frame");
            if (frame == null || !frame.url().contains("search")) continue;
            try {
                body = frame.locator("body").innerText();
            } catch (RuntimeException e) {
                continue;
            }
            if (body.contains("needle")) return body;
        }
        return body;
    }
}
