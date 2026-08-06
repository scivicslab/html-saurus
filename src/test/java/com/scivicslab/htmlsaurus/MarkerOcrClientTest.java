package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link MarkerOcrClient#splitParagraphs}: pure text logic, no HTTP. */
class MarkerOcrClientTest {

    @Test
    void splitParagraphs_blankLineSeparated() {
        String md = "First paragraph.\n\nSecond paragraph.\n\nThird.";
        assertEquals(List.of("First paragraph.", "Second paragraph.", "Third."),
                MarkerOcrClient.splitParagraphs(md));
    }

    @Test
    void splitParagraphs_keepsFencedCodeBlockIntact() {
        String md = "Before.\n\n```\nline one\n\nline two\n```\n\nAfter.";
        List<String> result = MarkerOcrClient.splitParagraphs(md);
        assertEquals(3, result.size());
        assertEquals("Before.", result.get(0));
        assertEquals("```\nline one\n\nline two\n```", result.get(1));
        assertEquals("After.", result.get(2));
    }

    @Test
    void splitParagraphs_emptyInput_returnsEmptyList() {
        assertEquals(List.of(), MarkerOcrClient.splitParagraphs(""));
    }

    @Test
    void splitParagraphs_noBlankLines_isOneParagraph() {
        assertEquals(List.of("line one\nline two"), MarkerOcrClient.splitParagraphs("line one\nline two"));
    }
}
