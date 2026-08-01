package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TranslationClient}'s pure (no network) response parsing.
 * The HTTP call itself is not exercised here (touching the generation server is
 * an integration concern, not a unit test).
 */
class TranslationClientTest {

    @Test
    void parseContent_validResponse_returnsContent() {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"}}]}";
        assertEquals("Hello", TranslationClient.parseContent(body));
    }

    @Test
    void parseContent_missingChoices_returnsNull() {
        assertNull(TranslationClient.parseContent("{\"choices\":[]}"));
        assertNull(TranslationClient.parseContent("{}"));
    }
}
