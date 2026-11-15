package com.scivicslab.htmlsaurus;

import java.util.regex.Pattern;

/**
 * Joins the OCR text of two consecutive PDF pages. The OCR reads one page at a time, so a paragraph
 * that runs on to the next page comes back as two pieces; joined with a blank line, the import made them
 * two paragraphs, and the page translation translated each half on its own (Tanenbaum, Structured
 * Computer Organization, chapter 1: "... each abstraction building on the one" | "below it. In this
 * way, ..."). Here the two pieces become one paragraph when the first does not end a sentence and the
 * second does not start a block of its own.
 *
 * <p>The page translation applies the same rule in the browser ({@code PageScripts}), for pages
 * imported before this join existed.</p>
 */
final class PageBreakJoin {

    private PageBreakJoin() {
    }

    /** Sentence-ending punctuation: . ? ! : ; and the Japanese 。？！ */
    private static final Pattern SENTENCE_END = Pattern.compile("[.?!:;。？！]$");
    /** What may close a sentence after its punctuation: quotes, brackets, emphasis marks. */
    private static final Pattern CLOSERS = Pattern.compile("[\"'”’)）」』\\]】*_]+$");
    /** A line that starts a block of its own: heading, list item, table, code, quote, math, image, HTML. */
    private static final Pattern STARTS_BLOCK = Pattern.compile(
            "^(#{1,6}\\s|[-*+]\\s|\\d+[.)]\\s|\\||```|~~~|>|\\$\\$|!\\[|<)");
    /** A line that is not prose, so the page does not end in the middle of a sentence. */
    private static final Pattern NOT_PROSE = Pattern.compile("^(#{1,6}\\s|\\||```|~~~|\\$\\$|!\\[|<)");

    /**
     * @param before the text of one page (or of the pages before it)
     * @param after  the text of the next page
     * @return the two joined: with a blank line between them, or, when a paragraph runs across the break,
     *         with a space (or with nothing, dropping a line-end hyphen before a lowercase letter)
     */
    static String join(String before, String after) {
        if (before == null || before.isBlank()) return after == null ? "" : after;
        if (after == null || after.isBlank()) return before;
        String head = before.stripTrailing();
        String tail = after.stripLeading();
        if (!runsOn(head, tail)) return head + "\n\n" + tail;
        if (head.matches("(?s).*\\p{L}-") && Character.isLowerCase(tail.codePointAt(0))) {
            return head.substring(0, head.length() - 1) + tail;
        }
        return head + " " + tail;
    }

    /** Whether the paragraph ending {@code head} goes on at the start of {@code tail}. */
    static boolean runsOn(String head, String tail) {
        String lastLine = head.substring(head.lastIndexOf('\n') + 1).strip();
        String firstLine = tail.lines().findFirst().orElse("").strip();
        if (lastLine.isEmpty() || firstLine.isEmpty()) return false;
        if (insideOpenFence(head)) return false;
        if (NOT_PROSE.matcher(lastLine).find()) return false;
        if (STARTS_BLOCK.matcher(firstLine).find()) return false;
        String bare = CLOSERS.matcher(lastLine).replaceAll("");
        return !SENTENCE_END.matcher(bare).find();
    }

    /** Whether {@code text} ends inside a fenced code block (an odd number of fence lines). */
    private static boolean insideOpenFence(String text) {
        long fences = text.lines().map(String::strip).filter(l -> l.startsWith("```") || l.startsWith("~~~")).count();
        return fences % 2 == 1;
    }
}
