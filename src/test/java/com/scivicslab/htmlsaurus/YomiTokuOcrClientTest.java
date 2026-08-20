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
    void parseResult_hasNoImages() {
        OcrClient.Result result = YomiTokuOcrClient.parseResult("{\"paragraphs\":[\"Text.\"]}");
        assertEquals(List.of("Text."), result.paragraphs());
        assertTrue(result.images().isEmpty());
    }
}
