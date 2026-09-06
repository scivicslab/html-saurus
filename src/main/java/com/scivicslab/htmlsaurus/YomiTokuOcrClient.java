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
 * OCR client for YomiToku — correct on Japanese text and multi-column layouts (reads column by
 * column), no math support. Same server/contract as W206's other YomiToku deployment (see
 * {@code EmbeddingClient}'s W206 GPU host convention): {@code POST {baseUrl}/ocr}, multipart
 * fields {@code file} (single-page PDF bytes) + {@code page} (always {@code "0"}, since the
 * caller already extracted one page).
 *
 * <p>Requests go to {@code /ocr/markdown}, which returns one {@code markdown} string. The server's
 * other endpoint, {@code /ocr}, returns a {@code paragraphs} array holding only the analyzer's
 * paragraphs and drops the tables it read. {@link #parseParagraphs} accepts both shapes, which is
 * what let this move without a moment where an import silently read zero paragraphs — see
 * {@code YomiTokuMarkdownEndpoint_260907_oo01}.</p>
 */
class YomiTokuOcrClient implements OcrClient {

    private static final Logger logger = Logger.getLogger(YomiTokuOcrClient.class.getName());

    /** Default YomiToku server (W206 GPU host). Node/port may move — see config. */
    public static final String DEFAULT_BASE_URL = "http://192.168.5.16:8013";

    private final String baseUrl;
    private final HttpClient httpClient;

    YomiTokuOcrClient(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL
                : baseUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String backendId() {
        return "yomitoku";
    }

    @Override
    public Result ocrPage(byte[] onePagePdfBytes) throws IOException, InterruptedException {
        GpuBrokerOcrClient.MultipartRequest req = buildRequest(onePagePdfBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/ocr/markdown"))
                .header("Content-Type", req.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(req.body()))
                .timeout(Duration.ofSeconds(120))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            logger.log(Level.WARNING, "YomiToku status " + response.statusCode() + " from " + baseUrl);
            throw new IOException("YomiToku OCR failed with status " + response.statusCode());
        }
        return parseResult(response.body());
    }

    /** Builds the same multipart body {@link #ocrPage} sends directly, for {@link GpuBrokerOcrClient}
     *  to submit through {@code quarkus-gpu-broker} instead -- YomiToku's {@code /ocr} endpoint
     *  requires this exact shape (fields {@code page}, file {@code file}), not a raw PDF body. */
    static GpuBrokerOcrClient.MultipartRequest buildRequest(byte[] onePagePdfBytes) throws IOException {
        String boundary = "----htmlsaurus" + System.nanoTime();
        var fields = new LinkedHashMap<String, String>();
        fields.put("page", "0");
        byte[] body = HttpUtils.buildMultipart(boundary, fields, "file", "page.pdf", onePagePdfBytes);
        return new GpuBrokerOcrClient.MultipartRequest(body, "multipart/form-data; boundary=" + boundary);
    }

    /** Parses a YomiToku response body, shared with {@link GpuBrokerOcrClient} (whose job result
     *  carries the same body YomiToku itself returned). YomiToku has no images field — it is a
     *  plain-text OCR engine. */
    static Result parseResult(String responseBody) {
        return new Result(parseParagraphs(responseBody), Map.of());
    }

    /**
     * The paragraphs of one page, from either shape YomiToku's server can return: the {@code
     * paragraphs} array of {@code /ocr}, or the single {@code markdown} string of {@code
     * /ocr/markdown}, which is split on blank lines the same way Marker's Markdown is. Reading both
     * is what lets the request path move from one endpoint to the other without a moment where the
     * import silently reads zero paragraphs -- see {@code YomiTokuMarkdownEndpoint_260907_oo01}.
     */
    static List<String> parseParagraphs(String responseBody) {
        Map<String, Object> root = McpJsonParser.parseObject(responseBody);
        Object paragraphs = root.get("paragraphs");
        if (paragraphs instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object p : list) {
                if (p != null && !p.toString().isBlank()) out.add(p.toString());
            }
            return out;
        }
        Object markdown = root.get("markdown");
        if (markdown == null) {
            markdown = root.get("text");
        }
        return markdown == null ? new ArrayList<>()
                : MarkerOcrClient.splitParagraphs(markdown.toString());
    }
}
