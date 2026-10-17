package com.scivicslab.htmlsaurus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for the shared generation server (Gemma-4), using the OpenAI-compatible
 * {@code /v1/chat/completions} API to translate one block of text on demand.
 *
 * <p>The server is a separate process — by default the GPU broker at
 * {@code http://192.168.5.21:30805}, which routes the model name to whichever node serves it —
 * so html-saurus never loads the model itself.
 *
 * <p>Circuit breaker: after a failed call, {@link #translate} stops attempting a connection
 * for {@link #DOWN_BACKOFF_MS} and returns {@code null} immediately. Without this, a single
 * page's on-demand translation fires one request per paragraph/heading/list item/table row;
 * if the GPU node is down, each of those would otherwise pay its own connect-timeout instead
 * of failing fast after the first one.
 */
public class TranslationClient {

    private static final Logger logger = Logger.getLogger(TranslationClient.class.getName());

    /** Default generation server: the cluster's GPU broker, which serves the OpenAI-compatible
     *  {@code /v1/chat/completions} and picks the node by model name. */
    public static final String DEFAULT_BASE_URL = "http://192.168.5.21:30805";
    /** Default model id sent in the request. */
    public static final String DEFAULT_MODEL = "google/gemma-4-26B-A4B-it";
    /** How long a model name fetched from AI workspace is reused, so a page that translates
     *  dozens of paragraphs asks once rather than once per paragraph. */
    private static final long MODEL_CACHE_MS = 5_000;
    /** How long to stop attempting connections after a failed call. */
    private static final long DOWN_BACKOFF_MS = 30_000;

    private static final String SYSTEM_PROMPT_TEMPLATE =
            "You are a translation engine. Translate the user's text into %s. "
          + "Output only the translation, with no explanation, quotes, or preamble.";

    private final String baseUrl;
    /** The model used when AI workspace names none: {@code TRANSLATION_MODEL}, else the default. */
    private final String fallbackModel;
    /** AI workspace's address ({@code AI_WORKSPACE_URL}), or {@code null} when not launched by it. */
    private final String workspaceUrl;
    private final HttpClient httpClient;
    private volatile String cachedModel;
    private volatile long cachedAtMs;
    /** Epoch millis until which {@link #translate} should not attempt a connection; 0 = up. */
    private final AtomicLong downUntilMs = new AtomicLong(0);

    /**
     * Creates a client for the given generation server base URL.
     *
     * @param baseUrl base URL of the generation server (no trailing slash), e.g.
     *                {@code http://192.168.5.21:30805}; blank/null uses {@code GPU_BROKER_URL}
     *                when set, otherwise {@link #DEFAULT_BASE_URL}
     */
    public TranslationClient(String baseUrl) {
        this(baseUrl, System.getenv("AI_WORKSPACE_URL"));
    }

    /**
     * @param workspaceUrl AI workspace's base URL, asked for the model chosen on its Settings
     *                     screen at each translation; {@code null}/blank never asks
     */
    TranslationClient(String baseUrl, String workspaceUrl) {
        this.workspaceUrl = workspaceUrl == null || workspaceUrl.isBlank() ? null
                : workspaceUrl.replaceAll("/+$", "");
        String brokerUrl = System.getenv("GPU_BROKER_URL");
        String chosen = baseUrl != null && !baseUrl.isBlank() ? baseUrl
                : brokerUrl != null && !brokerUrl.isBlank() ? brokerUrl
                : DEFAULT_BASE_URL;
        this.baseUrl = chosen.replaceAll("/+$", "");
        String envModel = System.getenv("TRANSLATION_MODEL");
        this.fallbackModel = (envModel == null || envModel.isBlank()) ? DEFAULT_MODEL : envModel;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Returns the configured base URL of the generation server. */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Translates a single block of text. Returns {@code null} without attempting a connection
     * if a previous call failed within the last {@link #DOWN_BACKOFF_MS} (see class doc).
     *
     * @param text       the source text
     * @param targetLang target language name sent to the model, e.g. {@code "English"} or
     *                   {@code "Japanese"}
     * @return the translated text, or {@code null} on failure or while backed off
     */
    public String translate(String text, String targetLang) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (isDown(System.currentTimeMillis())) {
            return null;
        }
        try {
            String body = buildRequestJson(text, targetLang, currentModel());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warning("Translation server status " + response.statusCode()
                        + " from " + baseUrl);
                markDown();
                return null;
            }
            String content = parseContent(response.body());
            downUntilMs.set(0);
            return content;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Translation call to " + baseUrl + " failed"
                    + " — pausing attempts for " + (DOWN_BACKOFF_MS / 1000) + "s", e);
            markDown();
            return null;
        }
    }

    /**
     * The model to translate with now: the one chosen on AI workspace's Settings screen, else
     * {@code TRANSLATION_MODEL}, else {@link #DEFAULT_MODEL}. AI workspace being absent or silent
     * is not an error here — the fallback is used and translation goes on.
     */
    String currentModel() {
        if (workspaceUrl == null) {
            return fallbackModel;
        }
        long now = System.currentTimeMillis();
        String cached = cachedModel;
        if (cached != null && now - cachedAtMs < MODEL_CACHE_MS) {
            return cached;
        }
        String chosen = null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(workspaceUrl + "/api/settings/translation-model"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                chosen = parseModel(response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not ask " + workspaceUrl + " for the translation model", e);
        }
        String model = chosen == null || chosen.isBlank() ? fallbackModel : chosen.trim();
        cachedModel = model;
        cachedAtMs = now;
        return model;
    }

    /** Extracts {@code model} from AI workspace's {@code {"model": "..."}} answer; {@code null} if absent. */
    static String parseModel(String responseBody) {
        Object model = McpJsonParser.parseObject(responseBody).get("model");
        return model == null ? null : model.toString();
    }

    /** Whether {@link #translate} should skip attempting a connection at time {@code nowMs}. */
    boolean isDown(long nowMs) {
        return nowMs < downUntilMs.get();
    }

    void markDown() {
        downUntilMs.set(System.currentTimeMillis() + DOWN_BACKOFF_MS);
    }

    /** Builds the OpenAI-style chat-completions request body for a translation call. */
    private static String buildRequestJson(String text, String targetLang, String model) {
        String system = SYSTEM_PROMPT_TEMPLATE.formatted(targetLang);
        return "{\"model\":" + HttpUtils.jsonStr(model) + ","
             + "\"temperature\":0.2,"
             + "\"messages\":["
             + "{\"role\":\"system\",\"content\":" + HttpUtils.jsonStr(system) + "},"
             + "{\"role\":\"user\",\"content\":" + HttpUtils.jsonStr(text) + "}"
             + "]}";
    }

    /** Extracts {@code choices[0].message.content} from a chat-completions response. */
    static String parseContent(String responseBody) {
        Map<String, Object> root = McpJsonParser.parseObject(responseBody);
        if (!(root.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            logger.warning("Translation response has no 'choices' array");
            return null;
        }
        if (!(choices.get(0) instanceof Map<?, ?> choice)) {
            return null;
        }
        if (!(choice.get("message") instanceof Map<?, ?> message)) {
            return null;
        }
        Object content = message.get("content");
        return content == null ? null : content.toString();
    }
}
