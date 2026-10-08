package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link MarkerOcrClient#splitParagraphs} and {@link MarkerOcrClient#parseImages}:
 *  pure logic, no HTTP. */
class MarkerOcrClientTest {

    @Test
    void splitParagraphs_blankLineSeparated() {
        String md = "First paragraph.\n\nSecond paragraph.\n\nThird.";
        assertEquals(List.of("First paragraph.", "Second paragraph.", "Third."),
                MarkerOcrClient.splitParagraphs(md));
    }

    @Test
    void splitParagraphs_keepsFencedCodeBlockIntact() {
        String md = "Before.\n\n```\nline one\n\nline two\n```\n\nAfter.";
        List<String> result = MarkerOcrClient.splitParagraphs(md);
        assertEquals(3, result.size());
        assertEquals("Before.", result.get(0));
        assertEquals("```\nline one\n\nline two\n```", result.get(1));
        assertEquals("After.", result.get(2));
    }

    @Test
    void splitParagraphs_emptyInput_returnsEmptyList() {
        assertEquals(List.of(), MarkerOcrClient.splitParagraphs(""));
    }

    @Test
    void splitParagraphs_noBlankLines_isOneParagraph() {
        assertEquals(List.of("line one\nline two"), MarkerOcrClient.splitParagraphs("line one\nline two"));
    }

    @Test
    void parseImages_decodesBase64Values() {
        byte[] raw = {(byte) 0xFF, 0x00, 0x10, 0x20};
        Map<String, Object> root = Map.of("images", Map.of(
                "_page_0_Picture_0.jpeg", Base64.getEncoder().encodeToString(raw)));
        Map<String, byte[]> images = MarkerOcrClient.parseImages(root);
        assertEquals(1, images.size());
        assertArrayEquals(raw, images.get("_page_0_Picture_0.jpeg"));
    }

    @Test
    void parseImages_missingImagesField_returnsEmpty() {
        assertTrue(MarkerOcrClient.parseImages(Map.of("output", "text")).isEmpty());
    }

    @Test
    void parseImages_emptyImagesObject_returnsEmpty() {
        assertTrue(MarkerOcrClient.parseImages(Map.of("images", Map.of())).isEmpty());
    }

    @Test
    void parseImages_invalidBase64Value_isSkippedNotThrown() {
        Map<String, Object> root = Map.of("images", Map.of("bad.jpeg", "not valid base64!!"));
        assertTrue(MarkerOcrClient.parseImages(root).isEmpty());
    }

    @Test
    void parseResult_combinesOutputAndImages_fromARawJsonBody() throws Exception {
        byte[] raw = {0x01, 0x02};
        String body = "{\"output\":\"First.\\n\\nSecond.\",\"images\":{\"a.jpeg\":\""
                + Base64.getEncoder().encodeToString(raw) + "\"}}";

        OcrClient.Result result = MarkerOcrClient.parseResult(body);

        assertEquals(List.of("First.", "Second."), result.paragraphs());
        assertArrayEquals(raw, result.images().get("a.jpeg"));
    }

    @Test
    void parseResult_missingOutputField_isEmptyParagraphs() throws Exception {
        OcrClient.Result result = MarkerOcrClient.parseResult("{\"success\":true}");
        assertEquals(List.of(), result.paragraphs());
        assertTrue(result.images().isEmpty());
    }

    /** Marker answers HTTP 200 with {@code success:false} when the conversion failed (seen live as
     *  a CUDA out-of-memory on one of two endpoints behind gpu-broker). Such a body must not read as
     *  an empty page: the import would store a blank page and drop its figure. */
    @Test
    void parseResult_successFalse_throwsWithMarkersErrorText() {
        String body = "{\"success\":false,\"error\":\"CUDA out of memory. Tried to allocate 88.00 MiB.\"}";
        java.io.IOException e = assertThrows(java.io.IOException.class, () -> MarkerOcrClient.parseResult(body));
        assertTrue(e.getMessage().contains("CUDA out of memory"), e.getMessage());
    }

    @Test
    void parseResult_successFalseWithoutErrorField_stillThrows() {
        assertThrows(java.io.IOException.class, () -> MarkerOcrClient.parseResult("{\"success\":false}"));
    }
}
