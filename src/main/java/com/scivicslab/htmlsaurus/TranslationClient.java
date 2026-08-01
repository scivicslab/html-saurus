package com.scivicslab.htmlsaurus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for the shared generation server (Gemma-4), using the OpenAI-compatible
 * {@code /v1/chat/completions} API to translate one block of text on demand.
 *
 * <p>The server is a separate process — by default the W206 GPU host at
 * {@code http://192.168.5.17:8000} — so html-saurus never loads the model itself.
 */
public class TranslationClient {

    private static final Logger logger = Logger.getLogger(TranslationClient.class.getName());

    /** Default generation server (W206 GPU host). Node/port may move — see config. */
    public static final String DEFAULT_BASE_URL = "http://192.168.5.17:8000";
    /** Default model id sent in the request. */
    public static final String DEFAULT_MODEL = "google/gemma-4-26B-A4B-it";

    private static final String SYSTEM_PROMPT_TEMPLATE =
            "You are a translation engine. Translate the user's text into %s. "
          + "Output only the translation, with no explanation, quotes, or preamble.";

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;

    /**
     * Creates a client for the given generation server base URL.
     *
     * @param baseUrl base URL of the generation server (no trailing slash), e.g.
     *                {@code http://192.168.5.17:8000}; blank/null uses
     *                {@link #DEFAULT_BASE_URL}
     */
    public TranslationClient(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL
                : baseUrl.replaceAll("/+$", "");
        String envModel = System.getenv("TRANSLATION_MODEL");
        this.model = (envModel == null || envModel.isBlank()) ? DEFAULT_MODEL : envModel;
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
     * Translates a single block of text.
     *
     * @param text       the source text
     * @param targetLang target language name sent to the model, e.g. {@code "English"} or
     *                   {@code "Japanese"}
     * @return the translated text, or {@code null} on failure
     */
    public String translate(String text, String targetLang) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            String body = buildRequestJson(text, targetLang, model);
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
                return null;
            }
            return parseContent(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Translation call to " + baseUrl + " failed", e);
            return null;
        }
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
