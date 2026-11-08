package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The script of every built page calls the server ({@code /api/...}). Under a base path each call must go through
 * {@code BASE}; with none, {@code BASE} is empty and the calls are the ones they always were.
 */
class PageScriptsBasePathTest {

    @Test
    void noBasePath_baseIsEmpty() {
        String js = PageScripts.render(BasePath.none());
        assertTrue(js.contains("var BASE = '';"));
        assertFalse(js.contains("YADOC_BASE_PATH"), "The placeholder must always be filled");
    }

    @Test
    void basePath_isTheValueOfBase() {
        String js = PageScripts.render(BasePath.of("/pups-with-audit/session/abc123/"));
        assertTrue(js.contains("var BASE = '/pups-with-audit/session/abc123';"));
        assertFalse(js.contains("YADOC_BASE_PATH"));
    }

    @Test
    void noCallToTheServerLeavesBaseOut() {
        String js = PageScripts.render(BasePath.of("/s/1"));
        for (String call : new String[] {"fetch('/", "postJson('/", "window.open('/", "fetch(\"/"}) {
            assertFalse(js.contains(call), "A call that begins at the host root escapes the base path: " + call);
        }
        assertTrue(js.contains("a.href = BASE + item.path;"), "Search results are links from the host root");
    }
}
