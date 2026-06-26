package com.scivicslab.htmlsaurus;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end test: does a document id (as written in {@code keyword-map.tsv}) resolve, via
 * html-saurus search, back to the original document — and does that document actually get served?
 *
 * <p>For every document reference in the keyword map it checks, against a RUNNING portal:
 * <ol>
 *   <li><b>search</b> — {@code GET /api/search?q=<ref>} returns a hit whose id (or path) matches the
 *       reference, exposing the served page URL;</li>
 *   <li><b>display</b> — fetching that served URL returns HTTP 200 and the page actually contains the
 *       document's title (i.e. the original document is shown, not a 404 or the wrong page);</li>
 *   <li><b>curated lookup</b> — for each rule, {@code GET /api/keyword-map?q=<the rule's terms>}
 *       returns the same referenced document (the deterministic table path works end to end).</li>
 * </ol>
 *
 * <p>Requires an already-running portal (defaults to the live one on :28005):
 * <pre>
 *   # against the live portal, using ~/works/html-saurus/keyword-map.tsv:
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.htmlsaurus.DocIdResolutionE2E \
 *     -Dexec.classpathScope=test
 *
 *   # override target / map / extra ids:
 *   PORTAL_URL=http://localhost:8500 KEYWORD_MAP_PATH=/path/to/keyword-map.tsv \
 *   mvn test-compile exec:java -Dexec.mainClass=com.scivicslab.htmlsaurus.DocIdResolutionE2E \
 *     -Dexec.classpathScope=test -Dexec.args="ExtraDocId_001 AnotherId_002"
 * </pre>
 * Exits non-zero if any check fails.
 */
public class DocIdResolutionE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("PORTAL_URL", "http://localhost:28005");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== Document-id resolution E2E: " + BASE_URL + " ===");

        // Collect the document references to test: explicit args first, then every docRef in the map.
        List<String> refs = new ArrayList<>(List.of(args));
        Path mapPath = Path.of(System.getenv().getOrDefault(
                "KEYWORD_MAP_PATH", System.getProperty("user.home") + "/works/html-saurus/keyword-map.tsv"));
        KeywordMap map = KeywordMap.load(mapPath);
        for (KeywordMap.Rule r : map.rules()) {
            for (String ref : r.docRefs()) {
                if (!refs.contains(ref)) refs.add(ref);
            }
        }
        System.out.println("Keyword map: " + map.size() + " rule(s) from " + mapPath);
        System.out.println("Testing " + refs.size() + " document reference(s): " + refs);
        System.out.println();

        if (refs.isEmpty()) {
            System.err.println("No document references to test (empty map and no args).");
            System.exit(1);
        }

        // (1)+(2) per referenced document id: search finds it, and its served page shows the original.
        // (1b) the dedicated /api/resolve endpoint returns the canonical URL deterministically.
        for (String ref : refs) {
            checkSearchAndDisplay(ref);
            checkResolve(ref);
        }
        // (3) per rule: the curated lookup returns the referenced document.
        for (KeywordMap.Rule r : map.rules()) {
            checkKeywordMapLookup(r);
        }
        // (4) negative: a bogus id must NOT resolve (no silent fallback to an unrelated top hit).
        checkResolveNotFound("__no_such_doc_zzz999__");

        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    /** (1) search by reference finds the doc; (2) its served URL returns the original document. */
    private static void checkSearchAndDisplay(String ref) {
        try {
            List<Map<String, String>> hits = getHits("/api/search?q=" + enc(ref) + "&lang=ja");
            Map<String, String> hit = pick(hits, ref);
            check(hit != null, "search '" + ref + "': no hit with matching id/path among "
                    + hits.size() + " results");
            String served = hit.get("path");      // project-prefixed served URL
            String title = hit.getOrDefault("title", "");
            check(served != null && !served.isBlank(), "search '" + ref + "': hit has no served path");
            pass("search resolves '" + ref + "' -> " + served);

            // (2) fetch the served page and confirm it is the original document.
            HttpResponse<String> page = rawGet(BASE_URL + served);
            check(page.statusCode() == 200,
                    "display '" + ref + "': GET " + served + " returned HTTP " + page.statusCode());
            String body = page.body();
            boolean shows = !title.isBlank()
                    && (body.contains(title) || body.contains(HttpUtils.escapeHtml(title)));
            check(shows, "display '" + ref + "': served page does not contain the title \"" + title + "\"");
            pass("served page for '" + ref + "' shows the original document (title: " + title + ")");
        } catch (AssertionError e) {
            fail(e.getMessage());
        } catch (Exception e) {
            fail("'" + ref + "': " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** (1b) the dedicated /api/resolve endpoint maps the id to a canonical URL that serves the doc. */
    private static void checkResolve(String ref) {
        try {
            HttpResponse<String> resp = rawGet(BASE_URL + "/api/resolve?id=" + enc(ref));
            check(resp.statusCode() == 200, "resolve '" + ref + "': HTTP " + resp.statusCode());
            List<Map<String, String>> objs = parseFlatJsonArray(resp.body());
            check(!objs.isEmpty(), "resolve '" + ref + "': empty response");
            String served = objs.get(0).getOrDefault("path", "");
            check(!served.isBlank(), "resolve '" + ref + "': no path returned");
            HttpResponse<String> page = rawGet(BASE_URL + served);
            check(page.statusCode() == 200,
                    "resolve '" + ref + "': resolved URL " + served + " returned HTTP " + page.statusCode());
            pass("/api/resolve '" + ref + "' -> " + served + " (served OK)");
        } catch (AssertionError e) {
            fail(e.getMessage());
        } catch (Exception e) {
            fail("resolve '" + ref + "': " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** (4) a reference that matches no document must return HTTP 404 (deterministic, no fallback). */
    private static void checkResolveNotFound(String bogusRef) {
        try {
            HttpResponse<String> resp = rawGet(BASE_URL + "/api/resolve?id=" + enc(bogusRef));
            check(resp.statusCode() == 404,
                    "resolve(bogus '" + bogusRef + "') must be 404, got HTTP " + resp.statusCode()
                    + " body=" + resp.body());
            pass("/api/resolve bogus id '" + bogusRef + "' -> 404 (no silent fallback)");
        } catch (AssertionError e) {
            fail(e.getMessage());
        } catch (Exception e) {
            fail("resolve-404 '" + bogusRef + "': " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** (3) the deterministic keyword-map lookup returns each rule's referenced document(s). */
    private static void checkKeywordMapLookup(KeywordMap.Rule r) {
        String prompt = String.join(" ", r.terms());
        try {
            List<Map<String, String>> hits = getHits("/api/keyword-map?q=" + enc(prompt));
            for (String ref : r.docRefs()) {
                check(pick(hits, ref) != null,
                        "keyword-map '" + prompt + "': did not return referenced doc '" + ref + "'");
            }
            pass("keyword-map '" + prompt + "' -> " + r.docRefs());
        } catch (AssertionError e) {
            fail(e.getMessage());
        } catch (Exception e) {
            fail("keyword-map '" + prompt + "': " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Returns the hit whose id equals the reference, else whose srcPath/path contains it. */
    private static Map<String, String> pick(List<Map<String, String>> hits, String ref) {
        for (Map<String, String> h : hits) {
            if (ref.equalsIgnoreCase(h.getOrDefault("id", ""))) return h;
        }
        for (Map<String, String> h : hits) {
            if (h.getOrDefault("srcPath", "").contains(ref) || h.getOrDefault("path", "").contains(ref)) return h;
        }
        return null;
    }

    // ---- HTTP + minimal JSON ----------------------------------------------

    private static List<Map<String, String>> getHits(String pathAndQuery) throws Exception {
        return parseFlatJsonArray(rawGet(BASE_URL + pathAndQuery).body());
    }

    private static HttpResponse<String> rawGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(15)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Parses a JSON array of flat string-valued objects (the shape html-saurus search APIs return)
     * into a list of maps. Quote-aware so braces/brackets inside string values do not confuse it.
     * Only handles string values, which is all these endpoints emit.
     */
    static List<Map<String, String>> parseFlatJsonArray(String json) {
        List<Map<String, String>> out = new ArrayList<>();
        if (json == null) return out;
        int i = 0, n = json.length();
        Map<String, String> cur = null;
        String key = null;
        boolean inStr = false, inKey = false;
        StringBuilder buf = new StringBuilder();
        while (i < n) {
            char c = json.charAt(i);
            if (inStr) {
                if (c == '\\' && i + 1 < n) {
                    char e = json.charAt(i + 1);
                    switch (e) {
                        case 'n' -> buf.append('\n');
                        case 'r' -> buf.append('\r');
                        case 't' -> buf.append('\t');
                        case 'b' -> buf.append('\b');
                        case 'f' -> buf.append('\f');
                        case '"' -> buf.append('"');
                        case '\\' -> buf.append('\\');
                        case '/' -> buf.append('/');
                        case 'u' -> {
                            if (i + 5 < n) { buf.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16)); i += 4; }
                        }
                        default -> buf.append(e);
                    }
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inStr = false;
                    if (inKey) { key = buf.toString(); }
                    else if (cur != null && key != null) { cur.put(key, buf.toString()); key = null; }
                    buf.setLength(0);
                } else {
                    buf.append(c);
                }
                i++;
                continue;
            }
            switch (c) {
                case '{' -> cur = new LinkedHashMap<>();
                case '}' -> { if (cur != null) { out.add(cur); cur = null; } }
                case '"' -> { inStr = true; inKey = (key == null); }
                case ':' -> inKey = false;
                case ',' -> { /* between pairs or objects */ }
                default -> { /* whitespace and array brackets */ }
            }
            i++;
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- result accounting -------------------------------------------------

    private static void pass(String msg) { System.out.println("PASS: " + msg); passed++; }
    private static void fail(String msg) { System.err.println("FAIL: " + msg); failed++; }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
