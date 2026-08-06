package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HttpUtils#buildMultipart}: the request body it builds is what the OCR
 * clients ({@link YomiTokuOcrClient}, {@link MarkerOcrClient}) send to the external OCR servers —
 * no HTTP or filesystem I/O here, just the raw byte layout.
 */
class HttpUtilsMultipartTest {

    @Test
    void body_containsTextFieldsAndFilePartInOrder() throws Exception {
        var textFields = new LinkedHashMap<String, String>();
        textFields.put("project", "myproj");
        textFields.put("destPath", "papers/book");
        byte[] fileBytes = {1, 2, 3, 4, 5, (byte) 0xFF, 0};

        byte[] body = HttpUtils.buildMultipart("test-boundary-123", textFields, "file", "book.pdf", fileBytes);
        String text = new String(body, StandardCharsets.ISO_8859_1);

        assertTrue(text.contains("--test-boundary-123\r\n"));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"project\"\r\n\r\nmyproj\r\n"));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"destPath\"\r\n\r\npapers/book\r\n"));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"file\"; filename=\"book.pdf\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"));
        assertTrue(text.endsWith("--test-boundary-123--\r\n"));
        assertTrue(contains(body, fileBytes), "raw file bytes must appear intact in the body");

        int projectPos = text.indexOf("name=\"project\"");
        int filePos = text.indexOf("name=\"file\"");
        assertTrue(projectPos >= 0 && projectPos < filePos, "text fields must precede the file part");
    }

    @Test
    void body_binaryFileWithEmbeddedCrlfAndDashes_survivesIntact() throws Exception {
        var textFields = new LinkedHashMap<String, String>();
        byte[] fileBytes = "line1\r\nline2\r\n--fake-boundary--".getBytes(StandardCharsets.UTF_8);

        byte[] body = HttpUtils.buildMultipart("b2", textFields, "file", "x.bin", fileBytes);

        assertTrue(contains(body, fileBytes), "embedded CRLF/dash bytes must not be altered or truncated");
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
