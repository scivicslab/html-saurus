package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks the local LLM where the parts of an imported book start. The book's pages are shown to the
 * model {@link ChapterSplitter#WINDOW_PAGES} at a time, each as a page number and the first
 * characters of its text; the model answers with the pages on which a chapter, an appendix, or
 * other part begins. Reading the answer and laying the parts out is {@link ChapterSplitter}'s job,
 * so the model only judges and never decides a file name.
 *
 * <p>The model is the one {@link TranslationClient} already talks to (AI workspace's choice, else
 * {@code TRANSLATION_MODEL}, else the default), reached through the GPU broker.</p>
 */
final class ChapterBoundaryClient implements ChapterSplitter.Planner {

    /** One call to the model: a system message, a user message, and how long to wait. */
    @FunctionalInterface
    interface Chat {
        String ask(String system, String user, int timeoutSeconds) throws IOException, InterruptedException;
    }

    /** A window of {@link ChapterSplitter#WINDOW_PAGES} pages with the model's answer takes a while. */
    private static final int TIMEOUT_SECONDS = 600;
    /** How many times one window is asked when the answer cannot be read. */
    private static final int ATTEMPTS = 2;

    private static final String SYSTEM_PROMPT = """
            You analyse the structure of a book. The user message lists some of the book's pages, one \
            line per page: the page number ("p12") and the beginning of that page's text, which is \
            OCR output in Markdown.

            Find the pages on which a new part of the book begins and answer with one JSON object and \
            nothing else:
            {"sections":[{"kind":"chapter","title":"...","startPage":12}]}

            kind is one of:
            - "chapter": a chapter, or a top-level part, of the main text.
            - "front": a preface, foreword or introduction before the first chapter. Do not list the \
            cover, the copyright page or the table of contents.
            - "appendix": an appendix.
            - "back": material after the main text, such as the index, acknowledgments, credits, or \
            advertisements for other books.

            title is the part's title as printed, without a leading "Chapter 3" or "Appendix A".
            startPage is the page number from the line on which the part begins, and must be a page \
            listed in this message.
            List a part only if it really begins on a listed page, in ascending page order. Text that \
            repeats at the top of many pages, such as a running header, is not the start of a part. \
            If no part begins on the listed pages, answer {"sections":[]}.
            """;

    private final Chat chat;

    /** @param llm supplies the server address and the model */
    ChapterBoundaryClient(TranslationClient llm) {
        this(llm::chat);
    }

    ChapterBoundaryClient(Chat chat) {
        this.chat = chat;
    }

    @Override
    public List<ChapterSplitter.Section> plan(String[] pageMarkdown) throws IOException, InterruptedException {
        int total = pageMarkdown.length;
        List<ChapterSplitter.Section> found = new ArrayList<>();
        for (int from = 0; from < total; from += ChapterSplitter.WINDOW_PAGES) {
            int to = Math.min(total, from + ChapterSplitter.WINDOW_PAGES);
            found.addAll(askWindow(pageMarkdown, from, to));
        }
        return ChapterSplitter.validate(found);
    }

    private List<ChapterSplitter.Section> askWindow(String[] pageMarkdown, int from, int to)
            throws IOException, InterruptedException {
        String user = "Pages " + (from + 1) + " to " + to + " of a book that has " + pageMarkdown.length
                + " pages:\n" + ChapterSplitter.digest(pageMarkdown, from, to);
        IllegalArgumentException unreadable = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            String answer = chat.ask(SYSTEM_PROMPT, user, TIMEOUT_SECONDS);
            try {
                return ChapterSplitter.parseWindowAnswer(answer, from + 1, to);
            } catch (IllegalArgumentException e) {
                unreadable = e;
            }
        }
        throw new IllegalArgumentException("pages " + (from + 1) + "-" + to + ": " + unreadable.getMessage(), unreadable);
    }
}
