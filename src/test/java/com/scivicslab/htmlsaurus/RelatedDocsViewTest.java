package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RelatedDocsView#hitJson(Map)}: pure JSON serialization of one hit map,
 * no {@code HttpExchange} or file I/O involved.
 */
class RelatedDocsViewTest {

    @Test
    void withoutCategoryKey_omitsCategoryField() {
        // /api/related, /api/siblings etc. never populate "category"; the field must not appear
        // for them, so adding prerequisites' category support cannot change their JSON shape.
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("id", "DocA_260101_oo01");
        hit.put("title", "Doc A");
        hit.put("path", "/site/DocA.html");
        hit.put("srcPath", "/abs/DocA.md");
        hit.put("summary", "A summary.");

        String json = RelatedDocsView.hitJson(hit);

        assertFalse(json.contains("\"category\""));
    }

    @Test
    void withCategoryKey_includesCategoryField_evenWhenEmpty() {
        // /api/prerequisites always sets "category" (possibly to ""), for a stable response
        // shape regardless of whether the author wrote a data-category attribute.
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("id", "DocA_260101_oo01");
        hit.put("title", "Doc A");
        hit.put("path", "/site/DocA.html");
        hit.put("srcPath", "/abs/DocA.md");
        hit.put("summary", "A summary.");
        hit.put("category", "");

        String json = RelatedDocsView.hitJson(hit);

        assertTrue(json.contains("\"category\":\"\""));
    }

    @Test
    void withNonEmptyCategory_isEscapedLikeAnyOtherJsonString() {
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("id", "DocA_260101_oo01");
        hit.put("category", "physics");

        String json = RelatedDocsView.hitJson(hit);

        assertTrue(json.contains("\"category\":\"physics\""));
    }

    @Test
    void allFixedFields_areAlwaysPresent() {
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("id", "DocA_260101_oo01");
        hit.put("title", "Doc A");
        hit.put("path", "/site/DocA.html");
        hit.put("srcPath", "/abs/DocA.md");
        hit.put("summary", "A summary.");

        String json = RelatedDocsView.hitJson(hit);

        assertEquals(
                "{\"id\":\"DocA_260101_oo01\",\"title\":\"Doc A\",\"path\":\"/site/DocA.html\","
                        + "\"srcPath\":\"/abs/DocA.md\",\"summary\":\"A summary.\"}",
                json);
    }
}
