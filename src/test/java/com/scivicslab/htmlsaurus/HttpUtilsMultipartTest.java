package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HttpUtils#buildMultipart} and {@link HttpUtils#parseMultipart}: round-trips
 * a request through the same builder used to call OCR servers, and the parser used to receive a
 * browser upload — no HTTP or filesystem I/O.
 */
class HttpUtilsMultipartTest {

    @Test
    void roundTrip_textFieldsAndFileAllRecovered() throws Exception {
        var textFields = new LinkedHashMap<String, String>();
        textFields.put("project", "myproj");
        textFields.put("destPath", "papers/book");
        byte[] fileBytes = {1, 2, 3, 4, 5, (byte) 0xFF, 0};

        byte[] body = HttpUtils.buildMultipart("test-boundary-123", textFields, "file", "book.pdf", fileBytes);
        List<HttpUtils.MultipartField> fields =
                HttpUtils.parseMultipart(body, "multipart/form-data; boundary=test-boundary-123");

        assertEquals(3, fields.size());
        assertEquals("myproj", fieldByName(fields, "project").asText());
        assertEquals("papers/book", fieldByName(fields, "destPath").asText());
        HttpUtils.MultipartField file = fieldByName(fields, "file");
        assertTrue(file.isFile());
        assertEquals("book.pdf", file.filename());
        assertArrayEquals(fileBytes, file.data());
    }

    @Test
    void parseMultipart_noBoundaryInContentType_returnsEmpty() {
        assertTrue(HttpUtils.parseMultipart("irrelevant".getBytes(), "multipart/form-data").isEmpty());
    }

    @Test
    void parseMultipart_nullContentType_returnsEmpty() {
        assertTrue(HttpUtils.parseMultipart("irrelevant".getBytes(), null).isEmpty());
    }

    @Test
    void roundTrip_binaryFileWithEmbeddedCrlfBytes_survivesIntact() throws Exception {
        var textFields = new LinkedHashMap<String, String>();
        byte[] fileBytes = "line1\r\nline2\r\n--fake-boundary--".getBytes();

        byte[] body = HttpUtils.buildMultipart("b2", textFields, "file", "x.bin", fileBytes);
        List<HttpUtils.MultipartField> fields = HttpUtils.parseMultipart(body, "multipart/form-data; boundary=b2");

        assertEquals(1, fields.size());
        assertArrayEquals(fileBytes, fields.get(0).data());
    }

    private static HttpUtils.MultipartField fieldByName(List<HttpUtils.MultipartField> fields, String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no field named " + name));
    }
}
