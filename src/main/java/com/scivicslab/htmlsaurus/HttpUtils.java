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
        "frame-ancestors 'none';";

    private HttpUtils() {}

    /** Sends an HTTP response with the given status code, content type, and UTF-8 body. */
    static void respond(HttpExchange ex, int code, String ct, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
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
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        if (ct.startsWith("text/html")) {
            ex.getResponseHeaders().set("Content-Security-Policy", CSP);
        }
        ex.sendResponseHeaders(code, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
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
        return "application/octet-stream";
    }

    /** HTML-escapes {@code &}, {@code <}, {@code >}, {@code "}, and {@code '} in the given string; null-safe. */
    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#x27;");
    }

    /** Wraps a string in JSON double quotes, escaping backslashes, quotes, and newlines. */
    static String jsonStr(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "") + "\"";
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
