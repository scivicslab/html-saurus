package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Search scopes through the portal: the categories a reader adds and ticks, and what a search then returns.
 *
 * <p>Two projects hold three pages that all contain the word {@code needle}. A scope is a path pattern on
 * {@code path_idx}, the path every search result shows, so a scope can name part of one project or span
 * projects.
 */
class SearchScopesApiTest {

    @TempDir
    Path works;

    private HttpServer http;

    @BeforeEach
    void startPortal() throws Exception {
        Path alpha = project("alpha", "Guides/Setup", "Notes/Daily");
        Path beta = project("beta", "Intro");
        http = new PortalServer(works, List.of(alpha, beta), 0, null, 0, BasePath.none(), null, false).start();
    }

    @AfterEach
    void stopPortal() {
        http.stop(0);
    }

    private Path project(String name, String... pages) throws IOException {
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

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + http.getAddress().getPort() + path));
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) builder.header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String addScope(String name, String query) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"query\":\"" + query.replace("\\", "\\\\") + "\"}";
        var r = send("POST", "/api/search-scopes", body);
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    /** @return the served paths of the hits of a keyword search, in the order returned */
    private List<String> paths(String scopes) throws Exception {
        String url = "/api/search?q=needle" + (scopes == null ? "" : "&scopes=" + URLEncoder.encode(scopes, StandardCharsets.UTF_8));
        var r = send("GET", url, null);
        assertEquals(200, r.statusCode(), r.body());
        var hits = new java.util.ArrayList<String>();
        var m = java.util.regex.Pattern.compile("\"path\":\"([^\"]*)\"").matcher(r.body());
        while (m.find()) hits.add(m.group(1));
        java.util.Collections.sort(hits);
        return hits;
    }

    @Test
    void withoutScopesEverythingIsFound() throws Exception {
        assertEquals(List.of("/alpha/Guides/Setup/", "/alpha/Notes/Daily/", "/beta/Intro/"), paths(null));
    }

    @Test
    void aTickedCategoryNarrowsTheSearchToItsPages() throws Exception {
        String json = addScope("Guides", "path_idx:\\/alpha\\/Guides\\/*");
        assertTrue(json.contains("\"id\":\"c1\""), json);
        assertEquals(List.of("/alpha/Guides/Setup/"), paths("c1"));
    }

    @Test
    void aCategoryMaySpanProjects() throws Exception {
        addScope("Setup and Intro", "path_idx:\\/alpha\\/Guides\\/* OR path_idx:\\/beta\\/*");
        assertEquals(List.of("/alpha/Guides/Setup/", "/beta/Intro/"), paths("c1"));
    }

    @Test
    void everythingElseIsThePagesNoCategorySelects() throws Exception {
        addScope("Guides", "path_idx:\\/alpha\\/Guides\\/*");
        assertEquals(List.of("/alpha/Notes/Daily/", "/beta/Intro/"), paths("rest"));
        assertEquals(List.of("/alpha/Guides/Setup/", "/alpha/Notes/Daily/", "/beta/Intro/"), paths("c1,rest"));
    }

    @Test
    void theSearchPageNamesTheScopeItIsNarrowedTo() throws Exception {
        addScope("Guides", "path_idx:\\/alpha\\/Guides\\/*");
        var r = send("GET", "/search?q=needle&scopes=c1", null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("Setup"), "the page in the scope");
        assertFalse(r.body().contains("Daily"), "a page outside it");
        assertTrue(r.body().contains("in Guides"), "the page says what it was narrowed to");
        var findRelated = send("POST", "/find-related", "text=needle+setup&scopes=c1");
        assertEquals(200, findRelated.statusCode(), "the similarity search takes the scopes too");
    }

    @Test
    void aBadQueryOrNameIsRefusedAndNothingIsStored() throws Exception {
        var broken = send("POST", "/api/search-scopes", "{\"name\":\"Broken\",\"query\":\"path_idx:(\"}");
        assertEquals(400, broken.statusCode());
        assertTrue(broken.body().contains("\"error\""), broken.body());
        assertEquals(400, send("POST", "/api/search-scopes", "{\"name\":\" \",\"query\":\"path_idx:x\"}").statusCode());
        var list = send("GET", "/api/search-scopes", null);
        assertTrue(list.body().contains("\"categories\":[]"), list.body());
    }

    @Test
    void theCategoriesAreKeptOnTheServerAndCanBeRemoved() throws Exception {
        addScope("Guides", "path_idx:\\/alpha\\/Guides\\/*");
        Path file = works.resolve(".html-saurus/search-scopes.json");
        assertTrue(Files.isRegularFile(file), "stored in a file on the server");
        assertTrue(Files.readString(file).contains("Guides"));

        var removed = send("DELETE", "/api/search-scopes?id=c1", null);
        assertEquals(200, removed.statusCode());
        assertTrue(removed.body().contains("\"categories\":[]"), removed.body());
        assertFalse(Files.readString(file).contains("Guides"));
        assertEquals(405, send("PUT", "/api/search-scopes", "{}").statusCode());
    }

    @Test
    void theSearchTabHoldsTheSearchFormAndTheScopes() throws Exception {
        String page = send("GET", "/", null).body();
        int searchPanel = page.indexOf("id=\"tab-search\"");
        int projectsPanel = page.indexOf("id=\"tab-projects\"");
        assertTrue(searchPanel > 0 && projectsPanel > searchPanel, "a Search tab before the Projects tab");
        assertTrue(page.indexOf("id=\"search-input\"") > searchPanel && page.indexOf("id=\"search-input\"") < projectsPanel,
                "the search form is in the Search tab");
        assertTrue(page.indexOf("+ Add search category") > searchPanel && page.indexOf("+ Add search category") < projectsPanel,
                "the button that adds a category");
        assertTrue(page.indexOf("id=\"scope-list\"") > searchPanel, "the list the categories and Everything else are drawn in");
    }
}
