package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link YomiTokuOcrClient#parseResult}/{@link YomiTokuOcrClient#parseParagraphs}: pure logic, no HTTP. */
class YomiTokuOcrClientTest {

    @Test
    void parseParagraphs_readsTheParagraphsArray() {
        String body = "{\"paragraphs\":[\"First.\",\"Second.\"]}";
        assertEquals(List.of("First.", "Second."), YomiTokuOcrClient.parseParagraphs(body));
    }

    @Test
    void parseParagraphs_skipsBlankEntries() {
        String body = "{\"paragraphs\":[\"First.\",\"\",\"  \",\"Second.\"]}";
        assertEquals(List.of("First.", "Second."), YomiTokuOcrClient.parseParagraphs(body));
    }

    @Test
    void parseParagraphs_missingField_returnsEmptyList() {
        assertEquals(List.of(), YomiTokuOcrClient.parseParagraphs("{}"));
    }

    @Test
    void parseParagraphs_readsTheMarkdownStringOfTheMarkdownEndpoint() {
        // /ocr/markdown returns one string instead of an array; blank lines separate paragraphs.
        String body = "{\"markdown\":\"First.\\n\\nSecond.\",\"device\":\"cuda\"}";
        assertEquals(List.of("First.", "Second."), YomiTokuOcrClient.parseParagraphs(body));
    }

    @Test
    void parseParagraphs_keepsAMarkdownTableAsOneParagraph() {
        // A table has no blank line inside it, which is what makes it survive as a single paragraph
        // and reach the reader as one table rather than a run of pipes.
        String table = "|a|b|\\n|-|-|\\n|1|2|";
        String body = "{\"markdown\":\"Before.\\n\\n" + table + "\\n\\nAfter.\"}";
        List<String> paragraphs = YomiTokuOcrClient.parseParagraphs(body);
        assertEquals(3, paragraphs.size());
        assertEquals("|a|b|\n|-|-|\n|1|2|", paragraphs.get(1));
    }

    @Test
    void parseParagraphs_readsATextFieldTheSameWay() {
        assertEquals(List.of("Only this."), YomiTokuOcrClient.parseParagraphs("{\"text\":\"Only this.\"}"));
    }

    @Test
    void parseResult_hasNoImages() {
        OcrClient.Result result = YomiTokuOcrClient.parseResult("{\"paragraphs\":[\"Text.\"]}");
        assertEquals(List.of("Text."), result.paragraphs());
        assertTrue(result.images().isEmpty());
    }
}
