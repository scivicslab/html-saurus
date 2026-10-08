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

    @Test
    void isDown_freshClient_isUp() {
        TranslationClient client = new TranslationClient(null);
        assertFalse(client.isDown(System.currentTimeMillis()));
    }

    @Test
    void isDown_immediatelyAfterMarkDown_isTrue() {
        TranslationClient client = new TranslationClient(null);
        client.markDown();
        assertTrue(client.isDown(System.currentTimeMillis()));
    }

    @Test
    void isDown_afterBackoffWindowElapses_isFalse() {
        TranslationClient client = new TranslationClient(null);
        client.markDown();
        assertFalse(client.isDown(System.currentTimeMillis() + 31_000),
                "backoff window (30s) should have elapsed");
    }
}
