package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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

    @Test
    void parseModel_readsTheChosenModel() {
        assertEquals("GLM-5.3-Flash-EXL3", TranslationClient.parseModel("{\"model\":\"GLM-5.3-Flash-EXL3\"}"));
        assertNull(TranslationClient.parseModel("{}"));
    }

    @Test
    void currentModel_withoutWorkspace_usesTheFallback() {
        TranslationClient client = new TranslationClient(null, null);
        assertFalse(client.currentModel().isBlank());
    }

    @Test
    void currentModel_takesTheModelAiWorkspaceNames() throws Exception {
        HttpServer server = answering("{\"model\":\"chosen-model\"}");
        try {
            TranslationClient client = new TranslationClient(null, "http://localhost:" + server.getAddress().getPort());
            assertEquals("chosen-model", client.currentModel());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void currentModel_blankAnswerKeepsTheFallback() throws Exception {
        HttpServer server = answering("{\"model\":\"\"}");
        try {
            TranslationClient client = new TranslationClient(null, "http://localhost:" + server.getAddress().getPort());
            assertEquals(new TranslationClient(null, null).currentModel(), client.currentModel());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void currentModel_unreachableWorkspaceKeepsTheFallback() throws Exception {
        HttpServer closed = answering("{}");
        int port = closed.getAddress().getPort();
        closed.stop(0);
        TranslationClient client = new TranslationClient(null, "http://localhost:" + port);
        assertEquals(new TranslationClient(null, null).currentModel(), client.currentModel());
    }

    private static HttpServer answering(String json) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/settings/translation-model", ex -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return server;
    }
}
