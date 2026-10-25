package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure logic for splitting an imported book into chapter directories: what to show the model, how
 * to read its answer, and where each part of the book is written. No filesystem, HTTP or model
 * I/O happens here, so all of it is unit tested without a model server
 * ({@link ChapterBoundaryClient} is the one class that asks the model).
 *
 * <p>The resulting layout follows the existing books under {@code doc_Base011/docs}: front matter
 * keeps its original name, chapters are {@code Chap01}, {@code Chap02}, ..., appendices are
 * {@code Appendix_A}, ..., and the page numbers in a part's file names restart at {@code p001}.</p>
 */
final class ChapterSplitter {

    private ChapterSplitter() {}

    /** How many characters of each page's text the model is shown. */
    static final int HEAD_CHARS = 200;
    /** How many pages are shown to the model in one request. */
    static final int WINDOW_PAGES = 100;

    /** What kind of part of the book a section is. */
    enum Kind { FRONT, CHAPTER, APPENDIX, BACK }

    /** A part of the book the model found: its kind, its title, and its first page (0-based). */
    record Section(Kind kind, String title, int startPage) {}

    /** Where one part of the book is written: the directory under the import's destination, the
     *  stem of its file names, the title for its front matter, and its pages {@code [fromPage,
     *  toPage)} (0-based). An empty {@code dirName} means the destination directory itself. */
    record Placement(String dirName, String stem, String title, int fromPage, int toPage) {}

    /** Decides where the parts of a book start, given every page's OCR Markdown. */
    interface Planner {
        /**
         * @param pageMarkdown the Markdown of every page, indexed by 0-based page
         * @return the book's sections, validated (see {@link #validate})
         * @throws IOException              if the model cannot be reached or answers unusably
         * @throws IllegalArgumentException if the sections it found cannot make a layout
         */
        List<Section> plan(String[] pageMarkdown) throws IOException, InterruptedException;
    }

    /**
     * The table shown to the model for pages {@code [from, to)}: one line per page, the 1-based
     * page number and the first {@link #HEAD_CHARS} characters of its text on one line.
     */
    static String digest(String[] pageMarkdown, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int page = from; page < to; page++) {
            String text = pageMarkdown[page] == null ? "" : pageMarkdown[page].strip().replaceAll("\\s+", " ");
            if (text.length() > HEAD_CHARS) text = text.substring(0, HEAD_CHARS);
            out.append('p').append(page + 1).append(": ").append(text.isEmpty() ? "(blank)" : text).append('\n');
        }
        return out.toString();
    }

    /**
     * Reads the model's answer for one window of pages. The answer is a JSON object
     * {@code {"sections":[{"kind":"chapter","title":"...","startPage":12}, ...]}}, possibly wrapped
     * in a code fence or a sentence. An entry with an unknown kind, a blank title or a page outside
     * the window is dropped: one bad entry should not discard the rest of the book.
     *
     * @param firstPage the first page of the window, 1-based
     * @param lastPage  the last page of the window, 1-based
     * @throws IllegalArgumentException if the answer holds no JSON object or no {@code sections} list
     */
    static List<Section> parseWindowAnswer(String answer, int firstPage, int lastPage) {
        int open = answer == null ? -1 : answer.indexOf('{');
        int close = answer == null ? -1 : answer.lastIndexOf('}');
        if (open < 0 || close < open) {
            throw new IllegalArgumentException("the model's answer has no JSON object");
        }
        Map<String, Object> root = McpJsonParser.parseObject(answer.substring(open, close + 1));
        if (!(root.get("sections") instanceof List<?> entries)) {
            throw new IllegalArgumentException("the model's answer has no \"sections\" list");
        }
        List<Section> sections = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            Kind kind = kindOf(map.get("kind"));
            Object title = map.get("title");
            Object start = map.get("startPage");
            if (kind == null || title == null || title.toString().isBlank() || !(start instanceof Number n)) continue;
            int page = n.intValue();
            if (page < firstPage || page > lastPage) continue;
            sections.add(new Section(kind, title.toString().strip(), page - 1));
        }
        return sections;
    }

    private static Kind kindOf(Object value) {
        if (value == null) return null;
        try {
            return Kind.valueOf(value.toString().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Puts the sections found in all windows in page order, keeping the first one when two start
     * on the same page, and checks that they can make a layout.
     *
     * @throws IllegalArgumentException if there is no chapter among them
     */
    static List<Section> validate(List<Section> found) {
        List<Section> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingInt(Section::startPage));
        List<Section> sections = new ArrayList<>();
        for (Section section : sorted) {
            if (!sections.isEmpty() && sections.get(sections.size() - 1).startPage() == section.startPage()) continue;
            sections.add(section);
        }
        if (sections.stream().noneMatch(s -> s.kind() == Kind.CHAPTER)) {
            throw new IllegalArgumentException("the model found no chapter");
        }
        return sections;
    }

    /**
     * Decides where every part of the book is written. Pages before the first section are front
     * matter named after the book ({@code bookStem}); a chapter is {@code Chap01}, {@code Chap02}
     * ... in order; an appendix is {@code Appendix_A}, {@code Appendix_B} ...; front and back
     * matter the model named keep that name. Directory names are made unique.
     *
     * @param sections   the validated sections, in page order
     * @param totalPages how many pages the book has
     * @param bookStem   the book's name (the PDF's filename without extension)
     * @param bookTitle  the book's title, used for the pages before the first section
     */
    static List<Placement> layout(List<Section> sections, int totalPages, String bookStem, String bookTitle) {
        List<Placement> placements = new ArrayList<>();
        Set<String> used = new HashSet<>();
        if (sections.get(0).startPage() > 0) {
            String name = unique(safeName(bookStem), used);
            placements.add(new Placement(name, name, bookTitle, 0, sections.get(0).startPage()));
        }
        int chapters = 0;
        int appendices = 0;
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            int end = i + 1 < sections.size() ? sections.get(i + 1).startPage() : totalPages;
            String dirName = switch (section.kind()) {
                case CHAPTER -> String.format("Chap%02d", ++chapters);
                case APPENDIX -> "Appendix_" + appendixSuffix(appendices++);
                case FRONT, BACK -> safeName(section.title());
            };
            dirName = unique(dirName, used);
            placements.add(new Placement(dirName, safeName(section.title()), section.title(), section.startPage(), end));
        }
        return placements;
    }

    private static String appendixSuffix(int index) {
        return index < 26 ? String.valueOf((char) ('A' + index)) : String.valueOf(index + 1);
    }

    private static String unique(String name, Set<String> used) {
        String candidate = name;
        for (int n = 2; !used.add(candidate); n++) {
            candidate = name + "_" + n;
        }
        return candidate;
    }

    /**
     * The directory one book gets when several PDFs are imported into the same destination with the
     * chapter split: each book's {@code Chap01}, {@code Chap02} ... would otherwise overwrite those
     * of the book before it. It is the PDF's file name without the extension, made safe.
     */
    static String bookDirectoryName(String pdfFileName) {
        String lower = pdfFileName.toLowerCase(Locale.ROOT);
        String stem = lower.endsWith(".pdf") ? pdfFileName.substring(0, pdfFileName.length() - 4) : pdfFileName;
        return safeName(stem);
    }

    /**
     * A title made safe for a file or directory name: characters a filesystem or a URL path
     * dislikes become {@code _}, runs of whitespace become one space, and the length is limited.
     */
    static String safeName(String title) {
        String name = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").strip();
        if (name.length() > 80) name = name.substring(0, 80).strip();
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1).strip();
        return name.isEmpty() ? "Untitled" : name;
    }
}
