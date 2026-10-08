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

    /**
     * The kind of the edge, which {@code PrerequisiteSection} reads from {@code data-relation},
     * has to reach the caller. The tool that follows references groups its answer by this value,
     * and it is what the vocabulary of relation kinds is expressed in
     * ({@code RelationKind_260830_oo01}).
     */
    @Test
    void withRelationKey_includesRelationField() {
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("id", "NamingByTypeAndInstanceExamples_260811_oo01");
        hit.put("title", "\u5b9f\u4f53\u306e\u547d\u540d");
        hit.put("path", "/site/x.html");
        hit.put("srcPath", "/abs/x.md");
        hit.put("summary", "A summary.");
        hit.put("relation", "anti-pattern");
        hit.put("category", "anti-pattern");

        String json = RelatedDocsView.hitJson(hit);

        assertEquals("{\"id\":\"NamingByTypeAndInstanceExamples_260811_oo01\","
                + "\"title\":\"\u5b9f\u4f53\u306e\u547d\u540d\","
                + "\"path\":\"/site/x.html\","
                + "\"srcPath\":\"/abs/x.md\","
                + "\"summary\":\"A summary.\","
                + "\"relation\":\"anti-pattern\","
                + "\"category\":\"anti-pattern\"}", json);
    }

    /** Endpoints that declare no kind keep the JSON shape they had. */
    @Test
    void withoutRelationKey_omitsRelationField() {
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("id", "DocA_260101_oo01");
        hit.put("title", "Doc A");

        assertFalse(RelatedDocsView.hitJson(hit).contains("\"relation\""));
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
