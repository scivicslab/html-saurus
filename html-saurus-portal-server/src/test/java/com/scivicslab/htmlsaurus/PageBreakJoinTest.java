package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A paragraph that runs across a PDF page boundary is one paragraph after import, not two
 * ({@code PdfImport} in the html-saurus doc tree). Measured on Tanenbaum, Structured Computer
 * Organization, chapter 1: page 21 ends "... each abstraction building on the one" and page 22 begins
 * "below it. In this way, ...", and the import wrote them as two paragraphs, so the page translation
 * translated "below it." on its own as 「その下にあります。」.
 */
class PageBreakJoinTest {

    static final String PAGE_21_END = "This simple observation has, over the course of time, led to a way of "
            + "structuring computers as a sequence of abstractions, each abstraction building on the one";
    static final String PAGE_22_START = "below it. In this way, the complexity can be mastered and computer systems "
            + "can be designed in a systematic, organized way.";

    @Test
    void theParagraphCutByThePageBreakOfChapterOneIsJoined() {
        String joined = PageBreakJoin.join("Earlier paragraph.\n\n" + PAGE_21_END, PAGE_22_START + "\n\n## 1.1 STRUCTURED COMPUTER ORGANIZATION");
        assertTrue(joined.contains("each abstraction building on the one below it. In this way"), joined);
        assertTrue(joined.startsWith("Earlier paragraph.\n\n"), "the earlier paragraph stays its own: " + joined);
        assertTrue(joined.contains("organized way.\n\n## 1.1"), "the heading after it stays a heading: " + joined);
    }

    @Test
    void assembleDocumentJoinsTheParagraphAcrossTheTwoPages() {
        List<String> pages = Arrays.asList(PAGE_21_END, PAGE_22_START);
        String md = PdfImportService.assembleDocument(pages, 0, 2, "SCO.pdf", "SCO", "stub");
        assertTrue(md.contains("building on the one below it."), md);
        assertFalse(md.contains("the one\n\nbelow"), md);
    }

    @Test
    void aHyphenatedWordAcrossTheBreakIsMadeWhole() {
        assertEquals("the computer", PageBreakJoin.join("the compu-", "ter"));
        // A compound split at the page end ("well-" + "known") cannot be told from a hyphenated word,
        // and loses its hyphen: the rarer case.
        assertEquals("a wellknown result", PageBreakJoin.join("a well-", "known result"));
        assertEquals("the Turing- Award", PageBreakJoin.join("the Turing-", "Award"),
                "before a capital the hyphen is kept, with a space");
    }

    @Test
    void aFinishedSentenceIsNotJoined() {
        for (String end : List.of("It is done.", "Is it?", "Yes!", "as follows:", "first;", "終わる。", "本当？", "そう！",
                "He said \"stop.\"", "(see Fig. 1.)")) {
            assertEquals(end + "\n\nNext part.", PageBreakJoin.join(end, "Next part."), end);
        }
    }

    @Test
    void aBlockThatStartsTheNextPageIsNotJoined() {
        for (String start : List.of("## 1.2 Heading", "- Check a number to see if it is zero.", "* item", "1. first",
                "2) second", "| a | b |", "```java", "~~~", "> quoted", "$$", "![fig](fig1.png)", "<div>")) {
            assertEquals("the text runs on\n\n" + start, PageBreakJoin.join("the text runs on", start), start);
        }
    }

    @Test
    void aPageThatEndsInAHeadingTableFenceOrImageIsNotJoined() {
        for (String end : List.of("## Heading", "| a | b |", "```", "$$", "![fig](fig1.png)")) {
            assertEquals(end + "\n\nnext words", PageBreakJoin.join(end, "next words"), end);
        }
        assertEquals("```\ncode line\n\nmore code", PageBreakJoin.join("```\ncode line", "more code"),
                "inside an open code fence nothing is joined");
    }

    @Test
    void anEmptySideIsLeftAsItIs() {
        assertEquals("only", PageBreakJoin.join("", "only"));
        assertEquals("only", PageBreakJoin.join("only", ""));
    }
}
