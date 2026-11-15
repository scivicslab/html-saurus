package com.scivicslab.htmlsaurus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Decides whether two adjacent paragraphs of a page are one sentence cut in two, for the page translation
 * ({@code PageTranslationBlocks_261115_oo01}). A PDF import that read the book one page at a time left
 * such cuts in the Markdown; translated one half at a time, each half comes back wrong.
 *
 * <p>The browser finds the candidates with a cheap rule (paragraph A does not end a sentence and B is a
 * plain paragraph). That rule alone also catches headings, labels, command output and index lines, which
 * stand alone, so each candidate is put to a local LLM as one yes/no question, with the end of A and the
 * start of B only. A decision is kept in a cache next to the translations, keyed by both texts, so a page
 * is judged once. When the LLM cannot be reached or its answer cannot be read, the pair is not merged and
 * nothing is remembered: that is how the page was translated before, and the pair is asked again next
 * time. Every decision is logged with the pair's first words, so a wrong merge can be traced.</p>
 */
final class ParagraphJoinJudge {

    /** The end of paragraph A and the start of paragraph B that follows it. */
    record Pair(String a, String b) {}

    /** One chat question to the local LLM: the same broker queue the translation uses. */
    interface Asker {
        CompletableFuture<String> ask(String system, String user);
    }

    /** How much of each paragraph the question shows: enough for the sentence across the cut. */
    static final int CONTEXT_CHARS = 300;
    /** How long one question may take, queue included, before the pair is left unmerged. */
    static final long ANSWER_TIMEOUT_SECONDS = 120;
    static final String CACHE_FILE = "join-decisions.tsv";

    static final String SYSTEM = "You check text extracted from a book page. Answer with one word: yes or no.";

    private final TranslationCache cache;
    private final Asker asker;
    private final Consumer<String> log;

    ParagraphJoinJudge(TranslationCache cache, Asker asker, Consumer<String> log) {
        this.cache = cache;
        this.asker = asker;
        this.log = log;
    }

    /**
     * @param pairs the candidate pairs of one page
     * @return for each pair, whether B continues A's sentence; {@code false} when it was not decided
     */
    List<Boolean> judge(List<Pair> pairs) {
        List<Pair> shown = pairs.stream().map(p -> new Pair(tail(p.a()), head(p.b()))).toList();
        List<CompletableFuture<String>> answers = new ArrayList<>();
        List<Boolean> cached = new ArrayList<>();
        for (Pair p : shown) {
            String hit = cache.get(key(p));
            cached.add(hit == null ? null : "yes".equals(hit));
            // All uncached questions are put at once; the broker's queue paces them.
            answers.add(hit != null ? null : asker.ask(SYSTEM, question(p)));
        }
        List<Boolean> out = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            Pair p = shown.get(i);
            // The log names the paragraphs by their own first words, not by the excerpt asked about.
            String who = firstWords(pairs.get(i));
            if (cached.get(i) != null) {
                out.add(cached.get(i));
                log.accept("join-check: " + (cached.get(i) ? "yes" : "no") + " (cached) | " + who);
                continue;
            }
            String answer;
            try {
                answer = answers.get(i).get(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.accept("join-check: undecided, not merged (" + cause.getMessage() + ") | " + who);
                out.add(false);
                continue;
            }
            Boolean yes = parse(answer);
            if (yes == null) {
                log.accept("join-check: undecided, not merged (answer \"" + abbreviate(answer, 60) + "\") | " + who);
                out.add(false);
                continue;
            }
            cache.put(key(p), yes ? "yes" : "no");
            log.accept("join-check: " + (yes ? "yes" : "no") + " | " + who);
            out.add(yes);
        }
        return out;
    }

    /** The question for one pair. */
    static String question(Pair p) {
        return "Paragraph A ends:\n<<<\n" + p.a() + "\n>>>\n\nParagraph B, which follows it on the page, starts:\n<<<\n"
                + p.b() + "\n>>>\n\nDoes B continue the sentence that A leaves unfinished, so that A and B are one "
                + "paragraph cut in two? Answer no if A is a heading, a label, a caption, a line of command output "
                + "or code, or an index line that stands alone. Answer with one word: yes or no.";
    }

    /** @return true for yes, false for no, null when the answer is neither */
    static Boolean parse(String answer) {
        if (answer == null) return null;
        String w = answer.strip().toLowerCase().replaceAll("^[^\\p{L}]+", "");
        if (w.startsWith("yes") || w.startsWith("はい")) return true;
        if (w.startsWith("no") || w.startsWith("いいえ")) return false;
        return null;
    }

    /** The cache key: both shown texts, in a namespace of their own. */
    static String key(Pair p) {
        return TranslationCache.key(p.a() + "\n‖\n" + p.b(), "paragraph-join");
    }

    /** The longest part of a word a clip drops, so that text without spaces (Japanese) keeps its clip. */
    private static final int MAX_WORD_PART = 40;

    /** The last {@link #CONTEXT_CHARS} characters of {@code s} or fewer; a word the cut falls in is left out. */
    static String tail(String s) {
        String t = s.strip();
        if (t.length() <= CONTEXT_CHARS) return t;
        int cut = t.length() - CONTEXT_CHARS;
        String clip = t.substring(cut);
        if (Character.isWhitespace(t.charAt(cut - 1))) return clip.strip();
        return clip.replaceFirst("^\\S{1," + MAX_WORD_PART + "}\\s+", "").strip();
    }

    /** The first {@link #CONTEXT_CHARS} characters of {@code s} or fewer; a word the cut falls in is left out. */
    static String head(String s) {
        String t = s.strip();
        if (t.length() <= CONTEXT_CHARS) return t;
        String clip = t.substring(0, CONTEXT_CHARS);
        if (Character.isWhitespace(t.charAt(CONTEXT_CHARS))) return clip.strip();
        return clip.replaceFirst("\\s+\\S{1," + MAX_WORD_PART + "}$", "").strip();
    }

    /** "A: <first words of A>… | B: <first words of B>…", for the log. */
    static String firstWords(Pair p) {
        return "A: \"" + abbreviate(p.a(), 50) + "\" | B: \"" + abbreviate(p.b(), 50) + "\"";
    }

    private static String abbreviate(String s, int n) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return one.length() <= n ? one : one.substring(0, n) + "…";
    }
}
