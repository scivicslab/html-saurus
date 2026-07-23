package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Shared HTTP utility methods used by {@link SearchServer} and {@link PortalServer}.
 */
final class HttpUtils {

    private static final String CSP =
        "default-src 'self'; " +
        "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
        "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
        "img-src 'self' data:; " +
        "font-src 'self' https://cdn.jsdelivr.net; " +
        "connect-src 'self'; " +
        "frame-ancestors 'self';";

    private HttpUtils() {}

    /** Sends an HTTP response with the given status code, content type, and UTF-8 body. */
    static void respond(HttpExchange ex, int code, String ct, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
        if (ct.startsWith("text/html")) {
            ex.getResponseHeaders().set("Content-Security-Policy", CSP);
        }
        ex.sendResponseHeaders(code, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }

    /** Sends an HTTP response with the given status code, content type, and raw byte body. */
    static void respond(HttpExchange ex, int code, String ct, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
        if (ct.startsWith("text/html")) {
            ex.getResponseHeaders().set("Content-Security-Policy", CSP);
        }
        ex.sendResponseHeaders(code, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }

    /**
     * Shared responsive rules for every html-saurus-generated SSR page (portal, search,
     * related, semantic, keyword-map editor, upload). Kept in one place so responsiveness
     * is systematic rather than per-page: it protects unexpectedly wide content (tables,
     * pre, images) from causing horizontal scroll and trims chrome padding on narrow
     * screens. Injected via {@link #injectResponsive(String)}. Not applied to docusaurus
     * static pages, which ship their own responsive stylesheet.
     */
    static String responsiveStyle() {
        return """
            <style data-hs-responsive>
            img, video { max-width: 100%; height: auto; }
            table { display: block; overflow-x: auto; }
            pre { overflow-x: auto; }
            @media (max-width: 700px) {
              header { padding-left: 1rem; padding-right: 1rem; }
              main { padding-left: 1rem; padding-right: 1rem; }
            }
            </style>
            """;
    }

    /** Inserts {@link #responsiveStyle()} just before {@code </head>} (or prepends it when no
     *  head is present). Idempotent: a page that already carries the marker is returned as is. */
    static String injectResponsive(String html) {
        if (html == null || html.contains("data-hs-responsive")) return html;
        String style = responsiveStyle();
        int i = html.indexOf("</head>");
        return i >= 0 ? html.substring(0, i) + style + html.substring(i) : style + html;
    }

    /** Maps a file path's extension to the corresponding MIME type. */
    static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".js"))   return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".xml"))  return "text/xml; charset=UTF-8";
        return "application/octet-stream";
    }

    /** HTML-escapes {@code &}, {@code <}, {@code >}, {@code "}, and {@code '} in the given string; null-safe. */
    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#x27;");
    }

    /** Wraps a string in JSON double quotes, escaping backslashes, quotes, and all control
     *  characters (tab, newline, CR, and any other char below U+0020 as {@code \\u00XX}); null-safe.
     *  Document titles/summaries can contain literal tabs, so every control char must be escaped or
     *  the emitted array is not valid JSON. */
    static String jsonStr(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
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
        sb.append('"');
        return sb.toString();
    }

    /** Extracts a single query parameter value from the request URL; returns empty string if absent. */
    static String queryParam(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return "";
        for (String kv : raw.split("&")) {
            if (kv.startsWith(key + "=")) {
                return URLDecoder.decode(kv.substring(key.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
