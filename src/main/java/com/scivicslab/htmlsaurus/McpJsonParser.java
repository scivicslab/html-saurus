package com.scivicslab.htmlsaurus;

import java.util.*;

/**
 * Minimal recursive-descent JSON parser for MCP JSON-RPC messages.
 * Produces {@code Map<String,Object>}, {@code List<Object>}, {@code String},
 * {@code Number} (Long or Double), {@code Boolean}, or {@code null}.
 *
 * <p>Thread-safe: instances are not shared; call {@link #parse(String)} as a static helper.
 */
final class McpJsonParser {

    private final String src;
    private int pos;

    private McpJsonParser(String src) {
        this.src = src;
        this.pos = 0;
    }

    /**
     * Parses a JSON string and returns the corresponding Java object.
     *
     * @param json the JSON text to parse
     * @return parsed value (Map, List, String, Number, Boolean, or null)
     * @throws IllegalArgumentException if the input is not valid JSON
     */
    static Object parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Empty JSON input");
        }
        var parser = new McpJsonParser(json);
        Object result = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos < parser.src.length()) {
            throw new IllegalArgumentException("Trailing content at position " + parser.pos);
        }
        return result;
    }

    /**
     * Convenience: parses JSON and returns it as a Map (throws if top-level is not an object).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String json) {
        Object v = parse(json);
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new IllegalArgumentException("Expected JSON object, got " + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    /**
     * Safe nested field access: {@code getString(map, "params", "name")} digs into nested maps.
     */
    @SuppressWarnings("unchecked")
    static String getString(Map<String, Object> map, String... keys) {
        Object current = map;
        for (int i = 0; i < keys.length; i++) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(keys[i]);
        }
        return current instanceof String s ? s : (current != null ? current.toString() : null);
    }

    /**
     * Safe nested field access returning a Map.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> getMap(Map<String, Object> map, String... keys) {
        Object current = map;
        for (String key : keys) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(key);
        }
        return current instanceof Map ? (Map<String, Object>) current : null;
    }

    /**
     * Safe nested field access returning a Number.
     */
    @SuppressWarnings("unchecked")
    static Number getNumber(Map<String, Object> map, String... keys) {
        Object current = map;
        for (String key : keys) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(key);
        }
        return current instanceof Number n ? n : null;
    }

    // ---- Recursive descent parser ----

    private Object readValue() {
        skipWhitespace();
        if (pos >= src.length()) throw new IllegalArgumentException("Unexpected end of input");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> readNull();
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield readNumber();
                throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
            }
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        var map = new LinkedHashMap<String, Object>();
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '}') { pos++; return map; }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            map.put(key, value);
            skipWhitespace();
            if (pos >= src.length()) throw new IllegalArgumentException("Unterminated object");
            if (src.charAt(pos) == '}') { pos++; return map; }
            expect(',');
        }
    }

    private List<Object> readArray() {
        expect('[');
        var list = new ArrayList<>();
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ']') { pos++; return list; }
        while (true) {
            list.add(readValue());
            skipWhitespace();
            if (pos >= src.length()) throw new IllegalArgumentException("Unterminated array");
            if (src.charAt(pos) == ']') { pos++; return list; }
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        var sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw new IllegalArgumentException("Unterminated escape");
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > src.length()) throw new IllegalArgumentException("Incomplete unicode escape");
                        String hex = src.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid escape '\\" + esc + "'");
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private Number readNumber() {
        int start = pos;
        if (pos < src.length() && src.charAt(pos) == '-') pos++;
        while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        boolean isFloat = false;
        if (pos < src.length() && src.charAt(pos) == '.') { isFloat = true; pos++; while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++; }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) { isFloat = true; pos++; if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++; while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++; }
        String num = src.substring(start, pos);
        return isFloat ? Double.parseDouble(num) : Long.parseLong(num);
    }

    private Boolean readBoolean() {
        if (src.startsWith("true", pos))  { pos += 4; return Boolean.TRUE; }
        if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        throw new IllegalArgumentException("Expected boolean at position " + pos);
    }

    private Object readNull() {
        if (src.startsWith("null", pos)) { pos += 4; return null; }
        throw new IllegalArgumentException("Expected null at position " + pos);
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private void expect(char c) {
        skipWhitespace();
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at position " + pos);
        }
        pos++;
    }
}
