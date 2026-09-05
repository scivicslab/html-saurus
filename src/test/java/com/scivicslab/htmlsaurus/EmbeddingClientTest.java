package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure (no network) helpers of {@link EmbeddingClient}:
 * JSON escaping and cosine similarity. The HTTP methods are not exercised here
 * (touching the embedding server is an integration concern, not a unit test).
 */
class EmbeddingClientTest {

    @Test
    void jsonEscape_specialCharacters_areEscaped() {
        String escaped = EmbeddingClient.jsonEscape("a\"b\\c\nd\te");
        assertEquals("a\\\"b\\\\c\\nd\\te", escaped);
    }

    @Test
    void jsonEscape_controlCharacter_becomesUnicodeEscape() {
        String escaped = EmbeddingClient.jsonEscape("xy");
        assertEquals("x\\u0001y", escaped);
    }

    @Test
    void cosine_identicalUnitVectors_isOne() {
        float[] v = {0.6f, 0.8f}; // already unit length
        assertEquals(1.0, EmbeddingClient.cosine(v, v), 1e-6);
    }

    @Test
    void cosine_orthogonalVectors_isZero() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};
        assertEquals(0.0, EmbeddingClient.cosine(a, b), 1e-6);
    }

    @Test
    void cosine_mismatchedLengths_isMinusOne() {
        assertEquals(-1.0, EmbeddingClient.cosine(new float[]{1f}, new float[]{1f, 0f}), 0);
    }

    /**
     * An explicit URL wins over everything, so a caller that names a server gets that server.
     * The environment-driven default is exercised by {@link #theDefaultIsTheFixedNodeOrTheBroker}.
     */
    @Test
    void anExplicitUrlIsUsedAsGivenAndItsTrailingSlashesAreDropped() {
        assertEquals("http://example:9000", new EmbeddingClient("http://example:9000").baseUrl());
        assertEquals("http://example:9000", new EmbeddingClient("http://example:9000//").baseUrl());
    }

    /**
     * With no explicit URL, embeddings follow the same rule OCR follows: the broker when this
     * process was started with GPU_BROKER_URL, the fixed node otherwise. Whichever the environment
     * says, the two are the only answers.
     */
    @Test
    void theDefaultIsTheFixedNodeOrTheBroker() {
        String broker = System.getenv("GPU_BROKER_URL");
        String expected = (broker == null || broker.isBlank())
                ? EmbeddingClient.DEFAULT_BASE_URL
                : broker.replaceAll("/+$", "");
        assertEquals(expected, EmbeddingClient.defaultBaseUrl());
        assertEquals(expected, new EmbeddingClient(null).baseUrl());
        assertEquals(expected, new EmbeddingClient("  ").baseUrl());
    }

    /** The answer quarkus-gpu-broker's GET /queues gives, one object per queue. */
    private static final String QUEUES = """
        [{"name":"embedding-e5large","activeSlots":0,"idleSlots":16,"totalSlots":16,
          "pendingJobs":0,"completedLastHour":128,"ready":true,
          "endpoints":[{"address":"192.168.5.16:8012","health":"UP","probeOk":10,"probeTotal":10}]},
         {"name":"yomitoku-ocr","activeSlots":1,"idleSlots":0,"totalSlots":1,
          "pendingJobs":3,"completedLastHour":9,"ready":false,
          "endpoints":[{"address":"192.168.5.16:8013","health":"DOWN","probeOk":0,"probeTotal":10}]}]
        """;

    @Test
    void aQueueThatIsListedAndReadyIsReady() {
        assertTrue(EmbeddingClient.queueReady(QUEUES, "embedding-e5large"));
    }

    /**
     * A queue that is present but not ready, and a queue that is not there at all, are the same
     * answer: neither will produce an embedding.
     */
    @Test
    void aQueueThatIsNotReadyOrNotThereIsNotReady() {
        assertFalse(EmbeddingClient.queueReady(QUEUES, "yomitoku-ocr"));
        assertFalse(EmbeddingClient.queueReady(QUEUES, "embedding-bge"));
        assertFalse(EmbeddingClient.queueReady("[]", "embedding-e5large"));
    }

    /** An answer that is not the queue list — an error page, a truncated body — is not ready. */
    @Test
    void anAnswerThatIsNotTheQueueListIsNotReady() {
        assertFalse(EmbeddingClient.queueReady("<html>Resource not found</html>", "embedding-e5large"));
        assertFalse(EmbeddingClient.queueReady("{\"error\":\"nope\"}", "embedding-e5large"));
        assertFalse(EmbeddingClient.queueReady("", "embedding-e5large"));
    }
}
