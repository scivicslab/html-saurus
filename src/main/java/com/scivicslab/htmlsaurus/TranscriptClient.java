package com.scivicslab.htmlsaurus;

import com.scivicslab.gpubroker.client.GpuBrokerClient;
import com.scivicslab.gpubroker.client.GpuBrokerClientException;
import com.scivicslab.gpubroker.client.JobResult;
import com.scivicslab.gpubroker.client.Priority;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Transcribes a video URL through the W206 transcript server (yt-dlp + faster-whisper), so a video
 * can be imported the same way a PDF is: one Markdown document under a project's {@code docs/}.
 * One {@code POST /transcript} with {@code {"url": "..."}} returns the video's title, a poster
 * thumbnail and its timed segments.
 *
 * <p>Whether the request goes through {@code quarkus-gpu-broker}'s {@code whisper-transcript} queue
 * or straight to the transcript server is decided once, at {@code PortalServer} startup, by whether
 * {@code GPU_BROKER_URL} is set — the same rule {@link GpuBrokerOcrClient} follows for OCR, and
 * never a runtime fallback from one to the other.
 *
 * <p>Transcribing a long video takes minutes, so the read timeout is deliberately large and the
 * caller runs {@link #fetch} on a job actor's own thread (see {@link VideoImportJob}).
 */
class TranscriptClient {

    private static final Logger logger = Logger.getLogger(TranscriptClient.class.getName());

    /** Default transcript server (W206 LAN). Node/port may move — see {@code TRANSCRIPT_SERVER_URL}. */
    public static final String DEFAULT_BASE_URL = "http://192.168.5.13:8003";

    /** Whisper on a long video can run for minutes; do not cut it short. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);
    private static final long RESULT_TIMEOUT_SECONDS = REQUEST_TIMEOUT.toSeconds();
    private static final String QUEUE_NAME = "whisper-transcript";

    /**
     * A transcript is one unbroken run of short segments. Grouping them into paragraphs of at least
     * this many characters, broken at a sentence end, is what makes the imported document readable
     * as a document rather than as one wall of text.
     */
    private static final int PARAGRAPH_MIN_CHARS = 400;
    /** Closes a paragraph even mid-sentence, for a transcript whose punctuation never arrives. */
    private static final int PARAGRAPH_MAX_CHARS = 1200;

    private final String baseUrl;
    private final HttpClient httpClient;
    /** Non-null when this portal submits through quarkus-gpu-broker instead of calling {@link #baseUrl}. */
    private final GpuBrokerClient broker;

    /**
     * @param baseUrl the transcript server, or {@code null}/blank for {@link #DEFAULT_BASE_URL}
     * @param broker  submit through this gpu-broker instead of calling {@code baseUrl} directly,
     *                or {@code null} to call the server directly
     */
    TranscriptClient(String baseUrl, GpuBrokerClient broker) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL
                : baseUrl.replaceAll("/+$", "");
        this.broker = broker;
        this.httpClient = HttpClient.newBuilder()
                // HTTP/1.1 on purpose: h2c upgrades break the uvicorn/FastAPI transcript server.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * One transcribed video: its title, its poster thumbnail (a {@code data:} URI, or empty), and
     * the transcript grouped into paragraphs.
     */
    record TranscriptResult(String title, String thumbnail, List<String> paragraphs) {}

    /**
     * Transcribes one video URL.
     *
     * @throws IOException if the server is unreachable, answers non-2xx, reports failure, or
     *                     returns a transcript with no text
     */
    TranscriptResult fetch(String url) throws IOException, InterruptedException {
        byte[] requestBody = ("{\"url\":" + jsonString(url) + "}").getBytes(StandardCharsets.UTF_8);
        if (broker != null) {
            return parseResult(submitViaBroker(requestBody));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/transcript"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            logger.log(Level.WARNING, "Transcript server status " + response.statusCode() + " from " + baseUrl);
            throw new IOException("transcript server returned status " + response.statusCode());
        }
        return parseResult(response.body());
    }

    /** Submits one already-built request through gpu-broker and returns the transcript server's
     *  own response body — the same body a direct call would have returned. */
    private String submitViaBroker(byte[] requestBody) throws IOException {
        CompletableFuture<JobResult> resultFuture = new CompletableFuture<>();
        try {
            broker.submit(QUEUE_NAME, requestBody, "application/json", Priority.BACKGROUND,
                    resultFuture::complete);
        } catch (GpuBrokerClientException e) {
            throw new IOException("transcript submission to gpu-broker failed", e);
        }
        JobResult result;
        try {
            result = resultFuture.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("transcript via gpu-broker was interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("transcript via gpu-broker did not complete in time", e);
        }
        if (result.status() != JobResult.Status.DONE) {
            throw new IOException("transcript failed via gpu-broker: " + result.error());
        }
        return new String(result.body(), StandardCharsets.UTF_8);
    }

    /**
     * Parses a transcript-server response body, shared by the direct and gpu-broker paths (the
     * broker's job result carries the same body the transcript server itself returned).
     *
     * @throws IOException on {@code success:false} or a transcript with no text — the caller
     *                     reports that to the Import screen as a failed job
     */
    static TranscriptResult parseResult(String responseBody) throws IOException {
        Map<String, Object> root = McpJsonParser.parseObject(responseBody);
        if (!Boolean.TRUE.equals(root.get("success"))) {
            throw new IOException("transcript server reported failure");
        }
        String title = str(root.get("title"));
        String thumbnail = str(root.get("thumbnail"));
        List<String> segments = new ArrayList<>();
        if (root.get("segments") instanceof List<?> list) {
            for (Object seg : list) {
                if (seg instanceof Map<?, ?> m) {
                    String piece = str(m.get("text"));
                    if (!piece.isEmpty()) segments.add(piece);
                }
            }
        }
        List<String> paragraphs = toParagraphs(segments);
        if (paragraphs.isEmpty()) {
            throw new IOException("transcript has no text segments");
        }
        return new TranscriptResult(title.isBlank() ? "untitled" : title, thumbnail, paragraphs);
    }

    /**
     * Groups Whisper's short segments into readable paragraphs: a paragraph closes at the first
     * sentence end past {@link #PARAGRAPH_MIN_CHARS}, or unconditionally at
     * {@link #PARAGRAPH_MAX_CHARS} when no sentence end arrives.
     */
    static List<String> toParagraphs(List<String> segments) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (current.length() > 0) current.append(' ');
            current.append(segment);
            boolean longEnough = current.length() >= PARAGRAPH_MIN_CHARS && endsSentence(segment);
            if (longEnough || current.length() >= PARAGRAPH_MAX_CHARS) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    private static boolean endsSentence(String segment) {
        if (segment.isEmpty()) return false;
        return ".?!。？！".indexOf(segment.charAt(segment.length() - 1)) >= 0;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    /** Minimal JSON string literal, for the one-field request body this client sends. */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
