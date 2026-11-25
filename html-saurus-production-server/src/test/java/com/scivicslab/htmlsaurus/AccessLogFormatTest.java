package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

/** The line is Apache's {@code combined} format, byte for byte. */
class AccessLogFormatTest {

    private static final ZonedDateTime T = ZonedDateTime.of(2026, 11, 25, 14, 30, 12, 0, ZoneOffset.ofHours(9));

    @Test
    void theLineIsTheCombinedFormatOfTheApacheDocumentation() {
        assertEquals("203.0.113.5 - - [25/Nov/2026:14:30:12 +0900] \"GET /foo/ HTTP/1.1\" 200 5123 \"https://example/\" \"Mozilla/5.0 (X11)\"",
                AccessLogFormat.line("203.0.113.5", "GET /foo/ HTTP/1.1", 200, 5123, "https://example/", "Mozilla/5.0 (X11)", T, null));
    }

    @Test
    void zeroBytesAreADash_aMissingRefererAndAgentAreADash_anEmptyOneIsEmpty() {
        assertEquals("::1 - - [25/Nov/2026:14:30:12 +0900] \"HEAD / HTTP/1.1\" 200 - \"-\" \"-\"",
                AccessLogFormat.line("::1", "HEAD / HTTP/1.1", 200, 0, null, null, T, null));
        assertEquals("::1 - - [25/Nov/2026:14:30:12 +0900] \"GET / HTTP/1.1\" 304 - \"\" \"\"",
                AccessLogFormat.line("::1", "GET / HTTP/1.1", 304, 0, "", "", T, null));
    }

    @Test
    void theStatusHasThreeDigits() {
        assertTrue(AccessLogFormat.line("h", "r", 0, 1, null, null, T, null).contains("\" 000 1 "));
        assertTrue(AccessLogFormat.line("h", "r", 7, 1, null, null, T, null).contains("\" 007 1 "));
    }

    @Test
    void theTimeIsInTheZoneOfTheRequestWithItsOffsetAndEnglishMonth() {
        ZonedDateTime utc = ZonedDateTime.of(2026, 3, 5, 1, 2, 3, 0, ZoneOffset.UTC);
        assertEquals("[05/Mar/2026:01:02:03 +0000]", "[" + AccessLogFormat.line("h", "r", 200, 1, null, null, utc, null).split("\\[|\\]")[1] + "]");
        ZonedDateTime west = ZonedDateTime.of(2026, 12, 31, 23, 59, 59, 0, ZoneOffset.ofHoursMinutes(-5, -30));
        assertEquals("31/Dec/2026:23:59:59 -0530", AccessLogFormat.line("h", "r", 200, 1, null, null, west, null).split("\\[|\\]")[1]);
    }

    @Test
    void theResponseTimeIsAFinalFieldOnlyWhenAsked() {
        String plain = AccessLogFormat.line("h", "r", 200, 1, null, null, T, null);
        String timed = AccessLogFormat.line("h", "r", 200, 1, null, null, T, 1234L);
        assertEquals(plain + " 1234", timed);
    }

    @Test
    void quotesBackslashesAndControlBytesAreEscapedAsApacheDoes() {
        assertEquals("GET /a\\\"b\\\\c HTTP/1.1", AccessLogFormat.escape("GET /a\"b\\c HTTP/1.1"));
        assertEquals("a\\nb\\tc\\rd\\be\\vf", AccessLogFormat.escape("a\nb\tc\rd\be\u000bf"));
        assertEquals("x\\x01\\x7f\\xe9y", AccessLogFormat.escape("x\u0001\u007f\u00e9y"));
        assertEquals("\\xe3\\x81\\x82", AccessLogFormat.escape("\u3042"), "above U+00FF: the UTF-8 bytes");
        assertEquals("plain text 0-9 ~", AccessLogFormat.escape("plain text 0-9 ~"));
    }

    @Test
    void aClientCannotBreakALineOrCloseTheQuote() {
        String line = AccessLogFormat.line("h", "GET /\" 200 99 \"x\nFAKE HTTP/1.1", 404, 1, "ref\"\n", "ua\\", T, null);
        assertEquals(1, line.split("\n").length);
        assertEquals("h - - [25/Nov/2026:14:30:12 +0900] \"GET /\\\" 200 99 \\\"x\\nFAKE HTTP/1.1\" 404 1 \"ref\\\"\\n\" \"ua\\\\\"", line);
    }
}
