package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OCR client for Marker — math-heavy/LaTeX-preserving, GPU-hosted. On two-column Japanese/English
 * books it merges the columns into one line (a known Marker limitation), so {@link YomiTokuOcrClient}
 * is the correct choice for those; Marker exists for the opposite case (scanned math/technical
 * PDFs where preserving LaTeX matters more than column layout).
 *
 * <p>{@code POST {baseUrl}/marker/upload}, multipart fields {@code file} (single-page PDF bytes) +
 * {@code page_range} (always {@code "0"}, since the caller already extracted one page) +
 * {@code output_format=markdown}, response
 * {@code {"format":"markdown","output":"## ...","success":true,"images":{...}}}. This client does
 * not extract Marker's returned images — image handling for OCR imports is not yet implemented
 * (unlike the Word/.docx import path, which does extract embedded images directly).
 */
class MarkerOcrClient implements OcrClient {

    private static final Logger logger = Logger.getLogger(MarkerOcrClient.class.getName());

    /** Default Marker server (W206 GPU host). Node/port may move — see config. */
    public static final String DEFAULT_BASE_URL = "http://192.168.5.13:8001";

    private final String baseUrl;
    private final HttpClient httpClient;

    MarkerOcrClient(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL
                : baseUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String backendId() {
        return "marker";
    }

    @Override
    public List<String> ocrPage(byte[] onePagePdfBytes) throws IOException, InterruptedException {
        String boundary = "----htmlsaurus" + System.nanoTime();
        var fields = new LinkedHashMap<String, String>();
        fields.put("page_range", "0");
        fields.put("output_format", "markdown");
        byte[] body = HttpUtils.buildMultipart(boundary, fields, "file", "page.pdf", onePagePdfBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/marker/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(120))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            logger.log(Level.WARNING, "Marker status " + response.statusCode() + " from " + baseUrl);
            throw new IOException("Marker OCR failed with status " + response.statusCode());
        }
        return splitParagraphs(parseMarkdown(response.body()));
    }

    private static String parseMarkdown(String responseBody) {
        Map<String, Object> root = McpJsonParser.parseObject(responseBody);
        Object output = root.get("output");
        return output == null ? "" : output.toString();
    }

    /** Splits Markdown on blank lines into paragraphs, keeping fenced code blocks intact. */
    static List<String> splitParagraphs(String markdown) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inFence = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                inFence = !inFence;
            }
            if (!inFence && line.isBlank()) {
                if (!current.isEmpty()) {
                    out.add(current.toString().strip());
                    current.setLength(0);
                }
            } else {
                if (!current.isEmpty()) current.append('\n');
                current.append(line);
            }
        }
        if (!current.isEmpty()) out.add(current.toString().strip());
        return out;
    }
}
