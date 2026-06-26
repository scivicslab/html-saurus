package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link KeywordMap}: pure parsing and AND-substring matching, no external services. */
class KeywordMapTest {

    /** A stub resolver that echoes the reference into a minimal hit map. */
    private static Map<String, String> echo(String ref) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", ref);
        m.put("title", "Title of " + ref);
        m.put("path", "/proj/" + ref + ".html");
        m.put("srcPath", "/abs/" + ref + ".md");
        m.put("summary", "summary of " + ref);
        return m;
    }

    @Test
    void parseLine_skipsBlanksAndComments() {
        assertNull(KeywordMap.parseLine(""));
        assertNull(KeywordMap.parseLine("   "));
        assertNull(KeywordMap.parseLine("# a comment"));
        assertNull(KeywordMap.parseLine("no colon here so invalid"));
    }

    @Test
    void parseLine_fullForm_subjectAxisTermsRefs() {
        KeywordMap.Rule r = KeywordMap.parseLine("POJO-actor > usage > tell, ask : doc_A, doc_B");
        assertNotNull(r);
        assertEquals("POJO-actor", r.subject());
        assertEquals("usage", r.axis());
        assertEquals(List.of("tell", "ask"), r.terms());
        assertEquals(List.of("doc_A", "doc_B"), r.docRefs());
    }

    @Test
    void parseLine_twoSegments_noAxis() {
        KeywordMap.Rule r = KeywordMap.parseLine("Slurm > sbatch : SlurmDoc");
        assertNotNull(r);
        assertEquals("Slurm", r.subject());
        assertEquals("", r.axis());
        assertEquals(List.of("sbatch"), r.terms());
        assertEquals(List.of("SlurmDoc"), r.docRefs());
    }

    @Test
    void lookup_firesOnlyWhenAllTermsPresent() {
        KeywordMap km = new KeywordMap(List.of(
            KeywordMap.parseLine("X > internals > search_docs, 決定論 : docX")));
        // Both terms present -> fires
        List<Map<String, String>> hit = km.lookup("search_docs を 決定論 的にしたい", KeywordMapTest::echo);
        assertEquals(1, hit.size());
        assertEquals("docX", hit.get(0).get("id"));
        // Only one term present -> no fire
        assertTrue(km.lookup("search_docs の話だけ", KeywordMapTest::echo).isEmpty());
    }

    @Test
    void lookup_caseInsensitiveSubstring() {
        KeywordMap km = new KeywordMap(List.of(KeywordMap.parseLine("P > usage > Tell : docP")));
        assertEquals(1, km.lookup("how does ActorRef.tell() work", KeywordMapTest::echo).size());
    }

    @Test
    void lookup_tagsSummaryAndCarriesAxisSubject() {
        KeywordMap km = new KeywordMap(List.of(KeywordMap.parseLine("P > usage > tell : docP")));
        Map<String, String> h = km.lookup("tell", KeywordMapTest::echo).get(0);
        assertEquals("usage", h.get("axis"));
        assertEquals("P", h.get("subject"));
        assertTrue(h.get("summary").startsWith("[curated: P / usage] "), h.get("summary"));
        assertEquals("/abs/docP.md", h.get("srcPath"));
    }

    @Test
    void lookup_dedupesSameDocAcrossRules() {
        KeywordMap km = new KeywordMap(List.of(
            KeywordMap.parseLine("P > usage > tell : docP"),
            KeywordMap.parseLine("P > usage > ask : docP")));
        // Both rules fire and reference docP; result is de-duplicated to one hit.
        assertEquals(1, km.lookup("tell and ask", KeywordMapTest::echo).size());
    }

    @Test
    void lookup_emptyPromptOrNoRules() {
        assertTrue(new KeywordMap(List.of()).lookup("anything", KeywordMapTest::echo).isEmpty());
        KeywordMap km = new KeywordMap(List.of(KeywordMap.parseLine("P > usage > tell : docP")));
        assertTrue(km.lookup("", KeywordMapTest::echo).isEmpty());
    }
}
