package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whether two adjacent paragraphs are one sentence cut in two is asked of a local LLM, one yes/no question
 * per candidate pair, and remembered ({@code PageTranslationBlocks_261115_oo01}). Measured on Tanenbaum,
 * Structured Computer Organization, chapter 1.
 */
class ParagraphJoinJudgeTest {

    @TempDir
    Path dir;

    static final ParagraphJoinJudge.Pair SCO = new ParagraphJoinJudge.Pair(
            "This simple observation has, over the course of time, led to a way of structuring computers as a "
                    + "sequence of abstractions, each abstraction building on the one",
            "below it. In this way, the complexity can be mastered and computer systems can be designed in a "
                    + "systematic, organized way.");
    static final ParagraphJoinJudge.Pair HEADING_LIKE = new ParagraphJoinJudge.Pair(
            "Level 2: The Instruction Set Architecture Level",
            "the ISA level is the level that the compiler writers see, and the hardware engineers build for.");

    /** Answers yes when it is asked about the SCO pair, no otherwise; counts the questions. */
    static final class StubLlm implements ParagraphJoinJudge.Asker {
        final List<String> asked = new ArrayList<>();
        @Override
        public CompletableFuture<String> ask(String system, String user) {
            asked.add(user);
            return CompletableFuture.completedFuture(user.contains("building on the one") ? "Yes." : "no");
        }
    }

    private ParagraphJoinJudge judge(ParagraphJoinJudge.Asker asker, List<String> log) {
        return new ParagraphJoinJudge(TranslationCache.load(dir, "join-decisions.tsv"), asker, log::add);
    }

    @Test
    void theLlmDecides_theScoPairIsOneSentence_theHeadingLikeLineStandsAlone() {
        StubLlm llm = new StubLlm();
        List<String> log = new ArrayList<>();
        assertEquals(List.of(true, false), judge(llm, log).judge(List.of(SCO, HEADING_LIKE)));
        assertEquals(2, llm.asked.size(), "one question per pair");
        assertTrue(llm.asked.get(0).contains("building on the one") && llm.asked.get(0).contains("below it."),
                "the question holds the end of A and the start of B: " + llm.asked.get(0));
        assertTrue(log.stream().anyMatch(l -> l.contains("yes") && l.contains("This simple observation") && l.contains("below it.")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("no") && l.contains("Level 2:") && l.contains("the ISA level")), log.toString());
    }

    @Test
    void onlyTheEndOfAAndTheStartOfBAreSent() {
        StubLlm llm = new StubLlm();
        String longA = "x ".repeat(2000) + "each abstraction building on the one";
        String longB = "below it. " + "y ".repeat(2000);
        judge(llm, new ArrayList<>()).judge(List.of(new ParagraphJoinJudge.Pair(longA, longB)));
        String q = llm.asked.get(0);
        assertTrue(q.length() < 1500, "a few hundred characters of each, not the whole page: " + q.length());
        assertTrue(q.contains("building on the one") && q.contains("below it."), q);
    }

    @Test
    void aClippedParagraphIsCutBetweenWords_soTheLogStartsWithAWholeWord() {
        String longA = "alpha beta gamma ".repeat(100) + "building on the one";
        String longB = "below it. " + "delta epsilon zeta ".repeat(100);
        String tail = ParagraphJoinJudge.tail(longA);
        String head = ParagraphJoinJudge.head(longB);
        assertTrue(tail.endsWith("building on the one") && tail.length() <= ParagraphJoinJudge.CONTEXT_CHARS, tail);
        assertTrue(tail.matches("^(alpha|beta|gamma) .*"), "starts with a whole word: " + tail.substring(0, 20));
        assertTrue(head.startsWith("below it.") && head.length() <= ParagraphJoinJudge.CONTEXT_CHARS, head);
        assertTrue(head.matches(".* (delta|epsilon|zeta)$"), "ends with a whole word: " + head.substring(head.length() - 20));
    }

    @Test
    void textWithoutSpacesKeepsAFullClip() {
        String ja = "これは日本語の段落で、" + "あ".repeat(500) + " 改ページで切れた";
        String tail = ParagraphJoinJudge.tail(ja);
        assertEquals(ParagraphJoinJudge.CONTEXT_CHARS, tail.length(),
                "a space near the end does not make the clip drop everything before it: " + tail);
        String head = ParagraphJoinJudge.head("い".repeat(500));
        assertEquals(ParagraphJoinJudge.CONTEXT_CHARS, head.length());
    }

    @Test
    void aDecisionIsCached_theSamePairIsNotAskedAgain_evenAfterARestart() {
        StubLlm llm = new StubLlm();
        List<String> log = new ArrayList<>();
        judge(llm, log).judge(List.of(SCO, HEADING_LIKE));
        assertEquals(List.of(true, false), judge(llm, log).judge(List.of(SCO, HEADING_LIKE)), "same answers from the cache");
        assertEquals(2, llm.asked.size(), "the second page view asks nothing");
        assertTrue(log.stream().anyMatch(l -> l.contains("cached")), log.toString());
    }

    @Test
    void whenTheBrokerIsDownNothingIsMerged_andNothingIsRemembered() {
        List<String> log = new ArrayList<>();
        ParagraphJoinJudge.Asker down = (s, u) -> CompletableFuture.failedFuture(new IOException("connection refused"));
        assertEquals(List.of(false, false), judge(down, log).judge(List.of(SCO, HEADING_LIKE)));
        assertTrue(log.stream().anyMatch(l -> l.contains("undecided") && l.contains("connection refused")), log.toString());
        StubLlm llm = new StubLlm();
        assertEquals(List.of(true, false), judge(llm, log).judge(List.of(SCO, HEADING_LIKE)), "asked again once the broker is back");
        assertEquals(2, llm.asked.size());
    }

    @Test
    void anAnswerThatIsNeitherYesNorNoMergesNothing() {
        List<String> log = new ArrayList<>();
        ParagraphJoinJudge.Asker vague = (s, u) -> CompletableFuture.completedFuture("It depends on the context.");
        assertEquals(List.of(false), judge(vague, log).judge(List.of(SCO)));
        assertTrue(log.stream().anyMatch(l -> l.contains("undecided")), log.toString());
    }
}
