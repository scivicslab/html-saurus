package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An author names another document by writing its id in the text, not by writing a link, so the
 * only way to find a name that points nowhere is to find the ids in the Markdown source. The id
 * has one shape: a CamelCase content name, a six-digit date, and an author code of two letters
 * and two digits, joined by underscores.
 */
@Tag("S1")
class DocIdScannerTest {

    private static List<String> ids(String markdown) {
        return DocIdScanner.scan(markdown).stream().map(DocIdScanner.Mention::id).toList();
    }

    @Test
    void findsAnIdInProseAndInInlineCode() {
        assertEquals(List.of("AgentBrief_260905_oo01", "SearchBeforeReading_260923_oo01"),
                ids("Read `AgentBrief_260905_oo01` first, then SearchBeforeReading_260923_oo01."));
    }

    @Test
    void reportsTheLineOfEachMention() {
        List<DocIdScanner.Mention> found = DocIdScanner.scan("first\n\nsee AgentBrief_260905_oo01\n");
        assertEquals(1, found.size());
        assertEquals(3, found.get(0).line());
    }

    @Test
    void aFileNameIsNotAnId() {
        assertEquals(List.of(), ids("the file 010_AgentBrief_260905_oo01.md sits in a directory"));
    }

    @Test
    void anIdFollowedByAnExtensionOrASlashIsStillAnId() {
        assertEquals(List.of("AgentBrief_260905_oo01", "AgentBrief_260905_oo01"),
                ids("see AgentBrief_260905_oo01.md and /x/AgentBrief_260905_oo01/"));
    }

    @Test
    void textThatOnlyResemblesAnIdIsIgnored() {
        assertEquals(List.of(), ids("snake_case_name, Version_2026, Foo_26090_oo01, Foo_260905_ooo1"));
    }

    @Test
    void fencedCodeBlocksAreSkipped() {
        assertEquals(List.of("RealOne_260905_oo01"),
                ids("```\nExample_260101_aa01\n```\nRealOne_260905_oo01\n~~~\nOther_260101_aa02\n~~~\n"));
    }

    @Test
    void theFrontMatterIsSkippedBecauseItDeclaresTheDocumentsOwnId() {
        assertEquals(List.of("Other_260905_oo01"),
                ids("---\nid: Own_260905_oo01\ntitle: x\n---\n\nSee Other_260905_oo01.\n"));
    }

    @Test
    void aMarkdownRuleAtTheTopIsNotTakenForFrontMatter() {
        assertEquals(List.of("Other_260905_oo01"), ids("\n---\n\nSee Other_260905_oo01.\n"));
    }
}
