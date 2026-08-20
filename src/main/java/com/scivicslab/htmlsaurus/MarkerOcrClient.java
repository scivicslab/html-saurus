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
 * {@code {"format":"markdown","output":"## ...![](_page_0_Picture_0.jpeg)...","success":true,
 * "images":{"_page_0_Picture_0.jpeg":"<base64>"}}}. Every image filename Marker returns is scoped
 * to that single call (it always numbers from {@code _page_0_...} — each call is, from Marker's
 * point of view, a fresh one-page document), so two different real pages can produce the exact
 * same filename; the caller ({@link PdfImportService#ocrOnePage}) is responsible for making them
 * unique across the whole document before writing them to disk.
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
    public Result ocrPage(byte[] onePagePdfBytes) throws IOException, InterruptedException {
        GpuBrokerOcrClient.MultipartRequest req = buildRequest(onePagePdfBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/marker/upload"))
                .header("Content-Type", req.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(req.body()))
                .timeout(Duration.ofSeconds(120))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            logger.log(Level.WARNING, "Marker status " + response.statusCode() + " from " + baseUrl);
            throw new IOException("Marker OCR failed with status " + response.statusCode());
        }
        return parseResult(response.body());
    }

    /** Builds the same multipart body {@link #ocrPage} sends directly, for {@link GpuBrokerOcrClient}
     *  to submit through {@code quarkus-gpu-broker} instead -- Marker's {@code /marker/upload}
     *  endpoint requires this exact shape (fields {@code page_range}/{@code output_format}, file
     *  {@code file}), not a raw PDF body. */
    static GpuBrokerOcrClient.MultipartRequest buildRequest(byte[] onePagePdfBytes) throws IOException {
        String boundary = "----htmlsaurus" + System.nanoTime();
        var fields = new LinkedHashMap<String, String>();
        fields.put("page_range", "0");
        fields.put("output_format", "markdown");
        byte[] body = HttpUtils.buildMultipart(boundary, fields, "file", "page.pdf", onePagePdfBytes);
        return new GpuBrokerOcrClient.MultipartRequest(body, "multipart/form-data; boundary=" + boundary);
    }

    /** Parses a Marker {@code /marker/upload} response body, shared with {@link GpuBrokerOcrClient}
     *  (whose job result carries the same body Marker itself returned). */
    static Result parseResult(String responseBody) {
        Map<String, Object> root = McpJsonParser.parseObject(responseBody);
        String markdown = root.get("output") == null ? "" : root.get("output").toString();
        return new Result(splitParagraphs(markdown), parseImages(root));
    }

    /** Decodes the {@code images} object ({@code {filename: base64}}) into raw bytes. */
    @SuppressWarnings("unchecked")
    static Map<String, byte[]> parseImages(Map<String, Object> root) {
        Object imagesField = root.get("images");
        if (!(imagesField instanceof Map<?, ?> imagesMap) || imagesMap.isEmpty()) {
            return Map.of();
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (var entry : imagesMap.entrySet()) {
            if (entry.getValue() == null) continue;
            try {
                out.put(entry.getKey().toString(),
                        java.util.Base64.getDecoder().decode(entry.getValue().toString()));
            } catch (IllegalArgumentException e) {
                logger.log(Level.WARNING, "Marker image '" + entry.getKey() + "' was not valid base64, skipped");
            }
        }
        return out;
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
