package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link McpHandler#formatHitList(List, String, String)}: pure text formatting
 * of a hit-map list into the MCP tool response, no server/IO involved.
 */
class McpHandlerFormatHitListTest {

    @Test
    void withoutCategoryKey_noCategoryLine() {
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("title", "Doc A");
        hit.put("path", "/site/DocA.html");

        String text = McpHandler.formatHitList(List.of(hit), "empty", "Found");

        assertFalse(text.contains("Category:"));
    }

    @Test
    void withEmptyCategory_noCategoryLine() {
        // "category" present but "" (uncategorized) should not print a bare "Category: " line.
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("title", "Doc A");
        hit.put("path", "/site/DocA.html");
        hit.put("category", "");

        String text = McpHandler.formatHitList(List.of(hit), "empty", "Found");

        assertFalse(text.contains("Category:"));
    }

    @Test
    void withNonEmptyCategory_printsCategoryLine() {
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("title", "Doc A");
        hit.put("path", "/site/DocA.html");
        hit.put("category", "physics");

        String text = McpHandler.formatHitList(List.of(hit), "empty", "Found");

        assertTrue(text.contains("  Category: physics\n"));
    }

    @Test
    void emptyList_usesEmptyMessage() {
        String text = McpHandler.formatHitList(List.of(), "No prerequisites found.", "Found");
        assertTrue(text.contains("No prerequisites found."));
    }
}
