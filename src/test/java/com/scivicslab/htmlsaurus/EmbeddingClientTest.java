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
}
