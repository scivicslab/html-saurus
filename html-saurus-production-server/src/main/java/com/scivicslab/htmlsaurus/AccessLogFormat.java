package com.scivicslab.htmlsaurus;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * One line of Apache's {@code combined} log format:
 * {@code LogFormat "%h %l %u %t \"%r\" %>s %b \"%{Referer}i\" \"%{User-Agent}i\""}.
 *
 * <p>{@code %l} and {@code %u} are always {@code -} (no ident, no authentication here). {@code %b} is the size of the
 * response body, {@code -} when it is zero. {@code %t} is the time the request was received, in the local zone with its
 * offset. The request line and the two headers are escaped the way Apache's {@code ap_escape_logitem} does it, so a
 * client cannot put a quote or a line break into the log.
 */
final class AccessLogFormat {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss xx", Locale.ENGLISH);

    private AccessLogFormat() {}

    /**
     * @param host         {@code %h}, the client's address
     * @param requestLine  {@code %r}, the request line as the client sent it (method, target and protocol)
     * @param status       {@code %>s}, the status the server answered with
     * @param bytes        {@code %b}, the bytes of the response body
     * @param referer      the {@code Referer} header, or {@code null} when the request had none
     * @param userAgent    the {@code User-Agent} header, or {@code null} when the request had none
     * @param received     when the request was received
     * @param micros       the time the request took in microseconds, appended as a last field; {@code null} for the plain
     *                     combined line
     * @return the line, without a line break
     */
    static String line(String host, String requestLine, int status, long bytes, String referer, String userAgent,
                       ZonedDateTime received, Long micros) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(host).append(" - - [").append(TIME.format(received)).append("] \"")
                .append(escape(requestLine)).append("\" ")
                .append(String.format("%03d", status)).append(' ')
                .append(bytes > 0 ? Long.toString(bytes) : "-")
                .append(" \"").append(referer == null ? "-" : escape(referer))
                .append("\" \"").append(userAgent == null ? "-" : escape(userAgent)).append('"');
        if (micros != null) sb.append(' ').append(micros);
        return sb.toString();
    }

    /**
     * Escapes a client-supplied string as Apache does: a quote and a backslash get a backslash, the control characters
     * that have a short name are written as it ({@code \n}, {@code \t}...), and every other byte outside printable
     * ASCII is written as {@code \xHH}. A character above U+00FF is written as the {@code \xHH} bytes of its UTF-8 form.
     */
    static String escape(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean plain = c >= 0x20 && c < 0x7f && c != '"' && c != '\\';
            if (plain) {
                if (sb != null) sb.append(c);
                continue;
            }
            if (sb == null) sb = new StringBuilder(s.length() + 8).append(s, 0, i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case 0x0b -> sb.append("\\v");
                default -> {
                    if (c < 0x100) sb.append(String.format("\\x%02x", (int) c));
                    else {
                        int end = Character.isHighSurrogate(c) && i + 1 < s.length() ? i + 2 : i + 1;
                        for (byte b : s.substring(i, end).getBytes(StandardCharsets.UTF_8)) sb.append(String.format("\\x%02x", b & 0xff));
                        i = end - 1;
                    }
                }
            }
        }
        return sb == null ? s : sb.toString();
    }
}
