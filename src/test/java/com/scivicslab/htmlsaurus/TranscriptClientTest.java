package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TranscriptClient}'s response parsing and paragraph grouping — pure logic,
 * no HTTP, so they run without the W206 transcript server.
 */
class TranscriptClientTest {

    @Test
    void parseResult_readsTitleThumbnailAndSegments() throws IOException {
        String body = "{\"success\":true,\"title\":\"A Talk\",\"thumbnail\":\"data:image/jpeg;base64,AAA\","
                + "\"segments\":[{\"text\":\" Hello \"},{\"text\":\"world.\"}]}";

        TranscriptClient.TranscriptResult result = TranscriptClient.parseResult(body);

        assertEquals("A Talk", result.title());
        assertEquals("data:image/jpeg;base64,AAA", result.thumbnail());
        assertEquals(List.of("Hello world."), result.paragraphs());
    }

    @Test
    void parseResult_blankTitleBecomesUntitled() throws IOException {
        String body = "{\"success\":true,\"title\":\"\",\"segments\":[{\"text\":\"Words.\"}]}";
        assertEquals("untitled", TranscriptClient.parseResult(body).title());
    }

    @Test
    void parseResult_successFalseFailsTheImport() {
        assertThrows(IOException.class, () -> TranscriptClient.parseResult("{\"success\":false}"));
    }

    @Test
    void parseResult_noTextSegmentsFailsTheImport() {
        assertThrows(IOException.class,
                () -> TranscriptClient.parseResult("{\"success\":true,\"segments\":[]}"));
    }

    @Test
    void toParagraphs_breaksAtTheFirstSentenceEndPastTheMinimum() {
        // Two 300-character sentences: the first cannot close a paragraph on its own (under the
        // 400-character minimum), the second closes it at its full stop.
        String sentence = "x".repeat(299) + ".";
        List<String> paragraphs = TranscriptClient.toParagraphs(List.of(sentence, sentence, "Tail."));

        assertEquals(2, paragraphs.size());
        assertEquals(sentence + " " + sentence, paragraphs.get(0));
        assertEquals("Tail.", paragraphs.get(1));
    }

    @Test
    void toParagraphs_doesNotBreakBeforeTheMinimum() {
        List<String> paragraphs = TranscriptClient.toParagraphs(List.of("One.", "Two.", "Three."));
        assertEquals(List.of("One. Two. Three."), paragraphs);
    }

    @Test
    void toParagraphs_breaksMidSentenceWhenPunctuationNeverArrives() {
        List<String> unpunctuated = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            unpunctuated.add("y".repeat(100));
        }
        List<String> paragraphs = TranscriptClient.toParagraphs(unpunctuated);

        assertTrue(paragraphs.size() > 1, "a transcript with no full stops must still be broken up");
        for (String p : paragraphs) {
            assertTrue(p.length() <= 1200 + 100,
                    "no paragraph may run away past the hard limit, got " + p.length());
        }
    }

    @Test
    void toParagraphs_acceptsJapaneseSentenceEndings() {
        String sentence = "あ".repeat(399) + "。";
        List<String> paragraphs = TranscriptClient.toParagraphs(List.of(sentence, "続き。"));
        assertEquals(List.of(sentence, "続き。"), paragraphs);
    }

    @Test
    void toParagraphs_emptyInputYieldsNoParagraphs() {
        assertTrue(TranscriptClient.toParagraphs(List.of()).isEmpty());
    }
}
