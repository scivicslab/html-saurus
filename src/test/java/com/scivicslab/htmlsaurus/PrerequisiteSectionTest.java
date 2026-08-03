package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link PrerequisiteSection}: pure Markdown parsing, no external services. */
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
            ## 前提文書

            - `POJOActorConcept_251023_oo01` — Turing-workflow is built on POJO-actor's actor model
            """;
        assertEquals(List.of("POJOActorConcept_251023_oo01"), PrerequisiteSection.extractRefs(md));
    }

    @Test
    void multipleRefs_inOrder() {
        String md = """
            ## 前提文書

            - `DocA_260101_oo01` — reason A
            - `DocB_260102_oo01`
            - `DocC_260103_oo01` — reason C
            """;
        assertEquals(List.of("DocA_260101_oo01", "DocB_260102_oo01", "DocC_260103_oo01"),
                PrerequisiteSection.extractRefs(md));
    }

    @Test
    void stopsAtNextHeading_doesNotLeakFollowingSections() {
        String md = """
            ## 前提文書

            - `DocA_260101_oo01`

            ### Under the Hood

            - `DocB_260102_oo01` (unrelated bullet in another section)
            """;
        assertEquals(List.of("DocA_260101_oo01"), PrerequisiteSection.extractRefs(md));
    }

    @Test
    void relatedDocsSectionBeforeHeading_isNotCaptured() {
        String md = """
            ### 関連文書

            - `UnrelatedDoc_260101_oo01` — this is a plain related-doc link, not a prerequisite

            ## 前提文書

            - `RealPrereq_260101_oo01`
            """;
        assertEquals(List.of("RealPrereq_260101_oo01"), PrerequisiteSection.extractRefs(md));
    }

    @Test
    void emptySection_returnsEmpty() {
        String md = """
            ## 前提文書

            (no bullets here, just prose)
            """;
        assertTrue(PrerequisiteSection.extractRefs(md).isEmpty());
    }

    @Test
    void headingIsLastLineOfFile_scansToEnd() {
        String md = "## 前提文書\n\n- `LastDoc_260101_oo01`";
        assertEquals(List.of("LastDoc_260101_oo01"), PrerequisiteSection.extractRefs(md));
    }

    @Test
    void duplicateRefs_areDeduplicated() {
        String md = """
            ## 前提文書

            - `DupDoc_260101_oo01` — first mention
            - `DupDoc_260101_oo01` — accidental repeat
            """;
        assertEquals(List.of("DupDoc_260101_oo01"), PrerequisiteSection.extractRefs(md));
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
        // a "## 前提文書" example inside a fenced code block. That must not be mistaken for the
        // document's own real prerequisites section further down.
        String md = """
            Example of how to write the section:
            ```markdown
            ## 前提文書

            - `ExampleOnly_260101_oo01`
            ```

            ## 前提文書

            - `RealPrereq_260101_oo01`
            """;
        assertEquals(List.of("RealPrereq_260101_oo01"), PrerequisiteSection.extractRefs(md));
    }

    @Test
    void headingLevelFour_stillStopsTheScan() {
        String md = """
            ## 前提文書

            - `DocA_260101_oo01`

            #### Some Level-4 Subsection

            - `DocB_260102_oo01` (unrelated bullet under a level-4 heading)
            """;
        assertEquals(List.of("DocA_260101_oo01"), PrerequisiteSection.extractRefs(md));
    }
}
