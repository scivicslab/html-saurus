package com.scivicslab.htmlsaurus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for the shared embedding server (multilingual-e5-large), using the
 * OpenAI-compatible {@code /v1/embeddings} API — the same endpoint and request
 * shape that OpenWebUI's RAG uses, so html-saurus produces vectors consistent
 * with the rest of the W206 RAG stack.
 *
 * <p>The server is a separate process — by default the W206 GPU host at
 * {@code http://192.168.5.17:8012} — so html-saurus never loads the model
 * itself; it only sends text and receives vectors.
 *
 * <pre>
 *   POST {baseUrl}/v1/embeddings   {"input": ["..."], "model": "..."}
 *   -&gt; {"data": [{"index": 0, "embedding": [...]}, ...]}
 * </pre>
 *
 * <p>Returned vectors are L2-normalized by this client so that cosine similarity
 * equals the dot product, regardless of whether the server normalizes. Text is
 * sent verbatim (no {@code query:}/{@code passage:} prefix) to match how
 * OpenWebUI feeds the same server. JSON is parsed with {@link McpJsonParser} to
 * avoid adding a JSON library dependency.
 */
public class EmbeddingClient {

    private static final Logger logger = Logger.getLogger(EmbeddingClient.class.getName());

    /** Default embedding server (W206 GPU host). Node/port may move — see config. */
    public static final String DEFAULT_BASE_URL = "http://192.168.5.17:8012";
    /** Default model id sent in the request (the server reports this model). */
    public static final String DEFAULT_MODEL = "intfloat/multilingual-e5-large";

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;

    /**
     * Creates a client for the given embedding server base URL.
     *
     * @param baseUrl base URL of the embedding server (no trailing slash), e.g.
     *                {@code http://192.168.5.17:8012}; blank/null uses
     *                {@link #DEFAULT_BASE_URL}
     */
    public EmbeddingClient(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL
                : baseUrl.replaceAll("/+$", "");
        String envModel = System.getenv("EMBEDDING_MODEL");
        this.model = (envModel == null || envModel.isBlank()) ? DEFAULT_MODEL : envModel;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Returns the configured base URL of the embedding server. */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Checks that the embedding server is reachable and responding.
     *
     * @return {@code true} if {@code GET /} returns HTTP 200, {@code false} otherwise
     */
    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Embeds a single text.
     *
     * @param text the text to embed
     * @return the L2-normalized embedding vector, or {@code null} on failure
     */
    public float[] embed(String text) {
        List<float[]> all = embedBatch(List.of(text == null ? "" : text));
        return (all == null || all.isEmpty()) ? null : all.get(0);
    }

    /**
     * Embeds a batch of texts in one request.
     *
     * @param texts the texts to embed
     * @return one L2-normalized vector per input text, in input order, or
     *         {@code null} if the request fails or the response cannot be parsed
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            String body = buildRequestJson(texts, model);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warning("Embedding server status " + response.statusCode()
                        + " from " + baseUrl);
                return null;
            }
            return parseEmbeddings(response.body(), texts.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Embedding call to " + baseUrl + " failed", e);
            return null;
        }
    }

    /** Builds the OpenAI-style {@code {"input": [...], "model": "..."}} request body. */
    private static String buildRequestJson(List<String> texts, String model) {
        StringBuilder sb = new StringBuilder("{\"input\":[");
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(jsonEscape(texts.get(i) == null ? "" : texts.get(i))).append('"');
        }
        sb.append("],\"model\":\"").append(jsonEscape(model)).append("\"}");
        return sb.toString();
    }

    /** Escapes a string for inclusion inside a JSON string literal. */
    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Parses an OpenAI embeddings response {@code {"data":[{"index":i,"embedding":[...]}]}}
     * into a list of L2-normalized float vectors ordered by {@code index}.
     */
    @SuppressWarnings("unchecked")
    private static List<float[]> parseEmbeddings(String responseBody, int expected) {
        Map<String, Object> root = McpJsonParser.parseObject(responseBody);
        Object data = root.get("data");
        if (!(data instanceof List<?> rows)) {
            logger.warning("Embedding response has no 'data' array");
            return null;
        }
        float[][] ordered = new float[rows.size()][];
        int next = 0;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> m)) {
                return null;
            }
            Object emb = m.get("embedding");
            if (!(emb instanceof List<?> nums)) {
                return null;
            }
            float[] v = new float[nums.size()];
            for (int i = 0; i < nums.size(); i++) {
                v[i] = ((Number) nums.get(i)).floatValue();
            }
            l2normalize(v);
            int idx = (m.get("index") instanceof Number n) ? n.intValue() : next;
            if (idx < 0 || idx >= ordered.length) {
                idx = next;   // fall back to arrival order if index is missing/out of range
            }
            ordered[idx] = v;
            next++;
        }
        List<float[]> result = new ArrayList<>(ordered.length);
        for (float[] v : ordered) {
            if (v == null) {
                logger.warning("Embedding response missing a vector (expected " + expected + ")");
                return null;
            }
            result.add(v);
        }
        return result;
    }

    /** Normalizes a vector to unit L2 length in place (no-op for a zero vector). */
    private static void l2normalize(float[] v) {
        double norm = 0;
        for (float f : v) norm += (double) f * f;
        norm = Math.sqrt(norm);
        if (norm == 0) return;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
    }

    /**
     * Cosine similarity of two L2-normalized vectors (equals the dot product).
     *
     * @param a first vector
     * @param b second vector
     * @return the dot product, or {@code -1} if either vector is null or lengths differ
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1;
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        return dot;
    }
}
