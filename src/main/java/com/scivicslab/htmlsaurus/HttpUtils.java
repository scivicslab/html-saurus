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

    /**
     * The 10 portal colour themes as CSS custom-property blocks (no {@code <style>} wrapper, so it
     * can be appended inside an existing style element). Single source of truth for the theme
     * palette: both the portal page and the doc-body pages emit these same definitions so the
     * portal's theme selection renders identically in the right-pane iframe. Contains no
     * {@code %}, so it is safe to append into a {@code String.formatted(...)} text block.
     */
    static String themeVariables() {
        return """
            :root, [data-theme="dark-catppuccin"] {
              --bg-primary:#1e1e2e; --bg-secondary:#313244; --bg-tertiary:#45475a;
              --text-primary:#cdd6f4; --text-secondary:#a6adc8;
              --accent-green:#a6e3a1; --border-color:#585b70; }
            [data-theme="dark-nord"] {
              --bg-primary:#2e3440; --bg-secondary:#3b4252; --bg-tertiary:#434c5e;
              --text-primary:#eceff4; --text-secondary:#d8dee9;
              --accent-green:#a3be8c; --border-color:#4c566a; }
            [data-theme="dark-blue"] {
              --bg-primary:#0d1b2a; --bg-secondary:#1b2838; --bg-tertiary:#2a3a4e;
              --text-primary:#d4dce8; --text-secondary:#8a9bb5;
              --accent-green:#6ec87a; --border-color:#3a4e68; }
            [data-theme="dark-green"] {
              --bg-primary:#0f1e14; --bg-secondary:#1a2e20; --bg-tertiary:#2a4030;
              --text-primary:#d0e4d4; --text-secondary:#88a890;
              --accent-green:#5ec87a; --border-color:#3a5842; }
            [data-theme="dark-red"] {
              --bg-primary:#1e0f0f; --bg-secondary:#2e1a1a; --bg-tertiary:#402a2a;
              --text-primary:#e4d0d0; --text-secondary:#a88888;
              --accent-green:#68b870; --border-color:#583a3a; }
            [data-theme="light-clean"] {
              --bg-primary:#ffffff; --bg-secondary:#f0f2f5; --bg-tertiary:#e4e6eb;
              --text-primary:#1c1e21; --text-secondary:#606770;
              --accent-green:#31a24c; --border-color:#ced0d4; }
            [data-theme="light-warm"] {
              --bg-primary:#faf6f0; --bg-secondary:#f0ebe3; --bg-tertiary:#e6dfd5;
              --text-primary:#3d3529; --text-secondary:#7a6f60;
              --accent-green:#6a8f5e; --border-color:#d5cec4; }
            [data-theme="light-blue"] {
              --bg-primary:#eef4fb; --bg-secondary:#dce8f5; --bg-tertiary:#c8d9ed;
              --text-primary:#1a2a40; --text-secondary:#4a6080;
              --accent-green:#3a9e50; --border-color:#b0c8e4; }
            [data-theme="light-green"] {
              --bg-primary:#f0f8f0; --bg-secondary:#e0f0e0; --bg-tertiary:#cce4cc;
              --text-primary:#1a301a; --text-secondary:#4a704a;
              --accent-green:#2e8b48; --border-color:#a8cca8; }
            [data-theme="light-red"] {
              --bg-primary:#fbf0f0; --bg-secondary:#f5dce0; --bg-tertiary:#ecc8cc;
              --text-primary:#401a1e; --text-secondary:#804a50;
              --accent-green:#3a9850; --border-color:#e0b0b8; }
            """;
    }

    /**
     * Remaps the doc stylesheet's own {@code --c-*} colour variables onto the shared portal
     * palette ({@link #themeVariables()}), each with the original light default as a fallback.
     * Placed after page.css in the document so it overrides the baked-in defaults; the top
     * navbar variables are intentionally left alone (page.css keeps them a fixed dark bar).
     * Single source of truth for the mapping, used both when building dev pages and when
     * injecting the theme into already-built pages at serve time.
     */
    static String docThemeMapping() {
        return """
            :root {
              --c-text: var(--text-primary, #3d4451);
              --c-bg: var(--bg-primary, #fff);
              --c-sidebar-bg: var(--bg-secondary, #f6f7f8);
              --c-border: var(--border-color, #e3e4e5);
              --c-heading: var(--text-primary, #1a202c);
              --c-hover: var(--bg-tertiary, #e3e4e5);
              --c-act-bg: var(--bg-tertiary, #e8f4fd);
              --c-act-text: var(--accent-green, #1877f2);
              --c-cat-label: var(--text-secondary, #444);
              --c-arrow: var(--text-secondary, #888);
              --c-pre-bg: var(--bg-secondary, #f6f7f8);
              --c-code-bg: var(--bg-tertiary, #f0f0f0);
              --c-th-bg: var(--bg-secondary, #f6f7f8);
              --c-bq-border: var(--border-color, #ddd);
              --c-bq-text: var(--text-secondary, #555);
              --c-h2-border: var(--border-color, #e3e4e5);
            }
            """;
    }

    /** The full theme block injected into portal-served doc pages: the shared palette, the
     *  {@code --c-*} remap onto it, and a script that applies the saved {@code portal-theme}.
     *  Carries the {@code data-hs-theme} marker so {@link #injectDocTheme(String)} is idempotent. */
    static String docThemeInjection() {
        return "<style data-hs-theme>\n" + themeVariables() + docThemeMapping() + "</style>\n"
            + "<script>(function(){var t=localStorage.getItem('portal-theme');"
            + "if(t)document.documentElement.setAttribute('data-theme',t);})();</script>\n";
    }

    /** Inserts {@link #docThemeInjection()} before {@code </head>} so a portal-served doc page
     *  follows the portal theme without rebuilding its static HTML. Idempotent: pages that already
     *  carry the marker (freshly built dev pages) are returned unchanged. */
    static String injectDocTheme(String html) {
        if (html == null || html.contains("data-hs-theme")) return html;
        int i = html.indexOf("</head>");
        return i >= 0 ? html.substring(0, i) + docThemeInjection() + html.substring(i)
                      : docThemeInjection() + html;
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
