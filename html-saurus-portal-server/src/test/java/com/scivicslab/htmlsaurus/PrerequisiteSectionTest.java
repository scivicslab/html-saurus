package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link PrerequisiteSection}: pure Markdown/XML parsing, no external services. */
class PrerequisiteSectionTest {

    @Test
    void noHeading_returnsEmpty() {
        String md = """
            # Title

            ## Problem Definition

            Some text without any prerequisite section.
            """;
        assertTrue(PrerequisiteSection.extractRefs(md).isEmpty());
    }

    @Test
    void singleRef_withReason() {
        String md = """
            ## 参考文献

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="POJOActorConcept_251023_oo01">Turing-workflow is built on POJO-actor's actor model</span></li>
            </ul>
            """;
        List<PrerequisiteSection.Ref> refs = PrerequisiteSection.extractRefs(md);
        assertEquals(1, refs.size());
        assertEquals("POJOActorConcept_251023_oo01", refs.get(0).docId());
        assertEquals("", refs.get(0).category());
    }

    /**
     * A document written in English heads the same section with the English word. Requiring the
     * Japanese heading would mean an English document could not declare a reference at all.
     */
    @Test
    void englishHeading_isReadTheSameWay() {
        String md = """
            ## References

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="DocA_260101_oo01">reason A</span></li>
            </ul>
            """;
        List<PrerequisiteSection.Ref> refs = PrerequisiteSection.extractRefs(md);
        assertEquals(1, refs.size());
        assertEquals("DocA_260101_oo01", refs.get(0).docId());
    }

    /** The heading is matched without case, so "REFERENCES" and "references" both open a section. */
    @Test
    void englishHeading_isMatchedWithoutCase() {
        for (String heading : List.of("References", "REFERENCES", "references")) {
            String md = "## " + heading + """

                <ul>
                <li><span data-relation="prerequisite" data-doc-id="DocA_260101_oo01">reason A</span></li>
                </ul>
                """;
            assertEquals(1, PrerequisiteSection.extractRefs(md).size(), heading);
        }
    }

    /**
     * {@code 参考資料} is the name {@code 参考文献} replaced. The documents written under the older
     * name are read without editing all of them.
     */
    @Test
    void theOlderJapaneseHeading_isStillRead() {
        String md = """
            ## 参考資料

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="DocA_260101_oo01">reason A</span></li>
            </ul>
            """;
        assertEquals(1, PrerequisiteSection.extractRefs(md).size());
    }

    /** A heading that is none of the three opens nothing, so an unrelated section is not read. */
    @Test
    void anotherHeading_opensNoSection() {
        String md = """
            ## Bibliography

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="DocA_260101_oo01">reason A</span></li>
            </ul>
            """;
        assertTrue(PrerequisiteSection.extractRefs(md).isEmpty());
    }

    @Test
    void multipleRefs_inOrder() {
        String md = """
            ## 参考文献

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="DocA_260101_oo01">reason A</span></li>
            <li><span data-relation="prerequisite" data-doc-id="DocB_260102_oo01"></span></li>
            <li><span data-relation="prerequisite" data-doc-id="DocC_260103_oo01">reason C</span></li>
            </ul>
            """;
        assertEquals(
                List.of("DocA_260101_oo01", "DocB_260102_oo01", "DocC_260103_oo01"),
                PrerequisiteSection.extractRefs(md).stream().map(PrerequisiteSection.Ref::docId).toList());
    }

    @Test
    void categoryAttribute_isCaptured() {
        String md = """
            ## 参考文献

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="ClassicalMechanics_260101_oo01" data-category="physics">直接の物理的関係</span></li>
            <li><span data-relation="prerequisite" data-doc-id="LinearAlgebra_260101_oo01" data-category="mathematics">測定に使う数学的前提</span></li>
            </ul>
            """;
        List<PrerequisiteSection.Ref> refs = PrerequisiteSection.extractRefs(md);
        assertEquals(2, refs.size());
        assertEquals("physics", refs.get(0).category());
        assertEquals("mathematics", refs.get(1).category());
    }

    @Test
    void relatedDocsSectionBeforeHeading_isNotCaptured() {
        String md = """
            ### 関連文書

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="UnrelatedDoc_260101_oo01">this is a plain related-doc link, not a prerequisite</span></li>
            </ul>

            ## 参考文献

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="RealPrereq_260101_oo01"></span></li>
            </ul>
            """;
        assertEquals(List.of("RealPrereq_260101_oo01"),
                PrerequisiteSection.extractRefs(md).stream().map(PrerequisiteSection.Ref::docId).toList());
    }

    @Test
    void emptyList_returnsEmpty() {
        String md = """
            ## 参考文献

            <ul>
            </ul>
            """;
        assertTrue(PrerequisiteSection.extractRefs(md).isEmpty());
    }

    @Test
    void duplicateRefs_areDeduplicated() {
        String md = """
            ## 参考文献

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="DupDoc_260101_oo01">first mention</span></li>
            <li><span data-relation="prerequisite" data-doc-id="DupDoc_260101_oo01">accidental repeat</span></li>
            </ul>
            """;
        assertEquals(List.of("DupDoc_260101_oo01"),
                PrerequisiteSection.extractRefs(md).stream().map(PrerequisiteSection.Ref::docId).toList());
    }

    @Test
    void blankAndNullInput_returnsEmpty() {
        assertTrue(PrerequisiteSection.extractRefs(null).isEmpty());
        assertTrue(PrerequisiteSection.extractRefs("").isEmpty());
        assertTrue(PrerequisiteSection.extractRefs("   ").isEmpty());
    }

    @Test
    void headingInsideFencedExample_isIgnored_realSectionAfterItStillFound() {
        // A document explaining this feature (e.g. this feature's own reference doc) may show
        // a "## 参考文献" example inside a fenced code block. CommonMark itself never turns fence
        // contents into a real Heading AST node, so this cannot be mistaken for a real section.
        String md = """
            Example of how to write the section:
            ```markdown
            ## 参考文献

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="ExampleOnly_260101_oo01"></span></li>
            </ul>
            ```

            ## 参考文献

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="RealPrereq_260101_oo01"></span></li>
            </ul>
            """;
        assertEquals(List.of("RealPrereq_260101_oo01"),
                PrerequisiteSection.extractRefs(md).stream().map(PrerequisiteSection.Ref::docId).toList());
    }

    @Test
    void headingNotImmediatelyFollowedByHtmlBlock_returnsEmpty() {
        // Prose between the heading and the HTML block is not supported by design: the section
        // body must be HTML from the first line, so a missing/misplaced block is treated the
        // same as a missing section rather than guessed at.
        String md = """
            ## 参考文献

            以下の文書を先に読むこと。

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="DocA_260101_oo01"></span></li>
            </ul>
            """;
        assertTrue(PrerequisiteSection.extractRefs(md).isEmpty());
    }

    @Test
    void malformedHtml_throwsInsteadOfSilentlyDroppingItems() {
        String md = """
            ## 参考文献

            <ul>
            <li><span data-relation="prerequisite" data-doc-id="DocA_260101_oo01">unclosed li
            </ul>
            """;
        assertThrows(PrerequisiteSection.MalformedPrerequisitesException.class,
                () -> PrerequisiteSection.extractRefs(md));
    }

    // ---- withCategory: the merge shared by PortalServer (/api/prerequisites) and McpHandler
    //      (the prerequisites MCP tool). API is the base implementation, MCP is a thin wrapper
    //      around the same behavior, so both call this one function instead of duplicating it.

    @Test
    void withCategory_addsCategoryFromRef() {
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("id", "DocA_260101_oo01");
        hit.put("title", "Doc A");

        Map<String, String> result = PrerequisiteSection.withCategory(
                hit, new PrerequisiteSection.Ref("DocA_260101_oo01", "prerequisite", "physics"));

        assertEquals("physics", result.get("category"));
        assertEquals("DocA_260101_oo01", result.get("id"));
        assertEquals("Doc A", result.get("title"));
    }

    @Test
    void withCategory_emptyRefCategory_setsEmptyString() {
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("id", "DocA_260101_oo01");

        Map<String, String> result = PrerequisiteSection.withCategory(
                hit, new PrerequisiteSection.Ref("DocA_260101_oo01", "prerequisite", ""));

        assertEquals("", result.get("category"));
    }

    @Test
    void withCategory_doesNotMutateInputMap() {
        // resolveDocRef()/docRefResolver.resolve() may return a cached/shared map instance;
        // withCategory must return a copy so other callers never see a "category" key leak in.
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("id", "DocA_260101_oo01");

        PrerequisiteSection.withCategory(hit, new PrerequisiteSection.Ref("DocA_260101_oo01", "prerequisite", "physics"));

        assertFalse(hit.containsKey("category"));
    }

    // ---- data-relation: the kind of relation, required on every entry
    //      (RelationKind_260830_oo01). The vocabulary is open, so only its shape is checked.

    @Test
    void extractRefs_readsTheRelationEachEntryDeclares() {
        String md = """
            # Doc

            ## 参考文献

            <ul>
            <li><span data-doc-id="Setup_260101_oo01" data-relation="prerequisite">read first</span></li>
            <li><span data-doc-id="Old_251201_oo01" data-relation="supersedes">this replaced it</span></li>
            </ul>
            """;

        List<PrerequisiteSection.Ref> refs = PrerequisiteSection.extractRefs(md);

        assertEquals(2, refs.size());
        assertEquals("prerequisite", refs.get(0).relation());
        assertEquals("supersedes", refs.get(1).relation());
    }

    /**
     * An entry without the attribute is rejected rather than given a default. A default would make
     * an entry whose author forgot the kind indistinguishable from one that says "prerequisite".
     */
    @Test
    void extractRefs_entryWithoutRelation_isRejected() {
        String md = """
            # Doc

            ## 参考文献

            <ul>
            <li><span data-doc-id="Setup_260101_oo01">read first</span></li>
            </ul>
            """;

        PrerequisiteSection.MalformedPrerequisitesException e =
                assertThrows(PrerequisiteSection.MalformedPrerequisitesException.class,
                        () -> PrerequisiteSection.extractRefs(md));
        assertTrue(e.getMessage().contains("data-relation"), e.getMessage());
        assertTrue(e.getMessage().contains("Setup_260101_oo01"), e.getMessage());
    }

    /**
     * The vocabulary is open, so the value is not checked against a list; only its shape is, so
     * that one relation does not split across spellings differing by case or spacing.
     */
    @Test
    void extractRefs_relationOutsideTheIdentifierShape_isRejected() {
        String md = """
            # Doc

            ## 参考文献

            <ul>
            <li><span data-doc-id="Setup_260101_oo01" data-relation="Read First">read first</span></li>
            </ul>
            """;

        assertThrows(PrerequisiteSection.MalformedPrerequisitesException.class,
                () -> PrerequisiteSection.extractRefs(md));
    }

    /** A kind nobody has used before is accepted: there is no list to check it against. */
    @Test
    void extractRefs_unknownRelation_isAccepted() {
        String md = """
            # Doc

            ## 参考文献

            <ul>
            <li><span data-doc-id="Data_260101_oo01" data-relation="cites-data-from">the figures</span></li>
            </ul>
            """;

        assertEquals("cites-data-from", PrerequisiteSection.extractRefs(md).get(0).relation());
    }

    @Test
    void withCategory_addsRelationFromRef() {
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("id", "DocA_260101_oo01");

        Map<String, String> result = PrerequisiteSection.withCategory(
                hit, new PrerequisiteSection.Ref("DocA_260101_oo01", "best-practice", ""));

        assertEquals("best-practice", result.get("relation"));
    }
}
