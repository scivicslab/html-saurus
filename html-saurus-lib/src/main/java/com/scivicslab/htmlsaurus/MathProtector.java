package com.scivicslab.htmlsaurus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides math ({@code $...$} and {@code $$...$$}) from the CommonMark parser and puts it back
 * into the rendered HTML unchanged.
 *
 * <p>The browser typesets math with the KaTeX auto-render extension, which reads the
 * {@code $} delimiters from the page text. CommonMark has no notion of math, so it would read
 * {@code *} and {@code _} inside a formula as emphasis, {@code \,} as an escaped comma, and
 * brackets as link syntax. This class replaces every formula with a placeholder before
 * parsing and restores the original text (HTML-escaped) afterwards.</p>
 *
 * <p>Math inside fenced code blocks and inline code spans is not touched. The placeholder uses
 * only private-use characters (no ASCII letters or digits), so a heading anchor made from the
 * heading text does not depend on the formula.</p>
 */
final class MathProtector {

    private static final char OPEN = '';
    private static final char CLOSE = '';
    private static final char DIGIT_BASE = '';

    private static final Pattern FENCE_OPEN = Pattern.compile("^\\s*(`{3,}|~{3,})");
    /** An inline code span: a run of backticks, the content, and a run of the same length. */
    private static final Pattern CODE_SPAN = Pattern.compile("(?<!`)(`+)(?!`)(.+?)(?<!`)\\1(?!`)");
    /** Display math written on one line: {@code $$...$$}. */
    private static final Pattern DISPLAY_ONE_LINE = Pattern.compile("\\$\\$(?!\\$)(.+?)(?<!\\$)\\$\\$");
    /**
     * Inline math. The opening dollar is not followed by white space, the closing dollar is not
     * preceded by white space and not followed by a digit, so that prices such as
     * {@code $5 and $10} are not read as math. A backslash escapes the next character.
     *
     * <p>The body is written as an unrolled loop with possessive quantifiers: runs of ordinary
     * characters, each run optionally followed by an escaped character. A lazy loop over the
     * alternative {@code (?:\\.|[^$\\\n])+?} would match the same text, but {@code java.util.regex}
     * recurses once per character of such a loop, so a formula a few thousand characters long
     * (OCR output can contain one) throws {@code StackOverflowError} on a page-conversion thread.
     * The body cannot contain an unescaped {@code $}, so it always ends at the first one and
     * giving up the lazy search loses no match.</p>
     */
    private static final Pattern INLINE = Pattern.compile(
            "(?<![\\\\$])\\$(?![\\s$])((?:[^$\\\\\\n]|\\\\.)[^$\\\\\\n]*+(?:\\\\.[^$\\\\\\n]*+)*+)(?<![\\s\\\\])\\$(?!\\d)");

    private final List<String> formulas = new ArrayList<>();

    /** Returns the Markdown with every formula replaced by a placeholder. */
    String protect(String markdown) {
        String[] lines = markdown.split("\n", -1);
        StringBuilder out = new StringBuilder();
        String openFence = null;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (openFence == null) {
                Matcher fence = FENCE_OPEN.matcher(line);
                if (fence.find()) {
                    openFence = fence.group(1);
                    out.append(line);
                } else if (line.trim().equals("$$")) {
                    int close = findClosingDisplay(lines, i + 1);
                    if (close < 0) {
                        out.append(line);
                    } else {
                        StringBuilder block = new StringBuilder();
                        for (int k = i; k <= close; k++) {
                            if (k > i) {
                                block.append('\n');
                            }
                            block.append(k == i ? line.substring(line.indexOf('$')) : lines[k]);
                        }
                        out.append(line, 0, line.indexOf('$')).append(placeholder(block.toString()));
                        i = close;
                    }
                } else {
                    out.append(protectLine(line));
                }
            } else {
                if (line.trim().equals(openFence) || (line.startsWith(openFence) && line.trim().equals(openFence))) {
                    openFence = null;
                }
                out.append(line);
            }
            i++;
            if (i < lines.length) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static int findClosingDisplay(String[] lines, int from) {
        for (int k = from; k < lines.length; k++) {
            if (lines[k].trim().equals("$$")) {
                return k;
            }
            if (lines[k].isBlank() && k > from + 40) {
                return -1;
            }
        }
        return -1;
    }

    /** Replaces the formulas in one line, leaving inline code spans alone. */
    private String protectLine(String line) {
        if (line.indexOf('$') < 0) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        Matcher code = CODE_SPAN.matcher(line);
        int pos = 0;
        while (code.find()) {
            out.append(protectText(line.substring(pos, code.start())));
            out.append(code.group());
            pos = code.end();
        }
        out.append(protectText(line.substring(pos)));
        return out.toString();
    }

    private String protectText(String text) {
        text = replaceAll(DISPLAY_ONE_LINE, text);
        return replaceAll(INLINE, text);
    }

    private String replaceAll(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (m.find()) {
            out.append(text, pos, m.start()).append(placeholder(m.group()));
            pos = m.end();
        }
        return out.append(text, pos, text.length()).toString();
    }

    private String placeholder(String formula) {
        int index = formulas.size();
        formulas.add(formula);
        StringBuilder token = new StringBuilder().append(OPEN);
        for (char c : Integer.toString(index).toCharArray()) {
            token.append((char) (DIGIT_BASE + (c - '0')));
        }
        return token.append(CLOSE).toString();
    }

    /** Puts the formulas back into the rendered HTML, escaping {@code & < >}. */
    String restore(String html) {
        if (formulas.isEmpty()) {
            return html;
        }
        Matcher m = Pattern.compile(OPEN + "([-]+)" + CLOSE).matcher(html);
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (m.find()) {
            int index = 0;
            for (char c : m.group(1).toCharArray()) {
                index = index * 10 + (c - DIGIT_BASE);
            }
            out.append(html, pos, m.start());
            out.append(index < formulas.size() ? escape(formulas.get(index)) : m.group());
            pos = m.end();
        }
        return out.append(html, pos, html.length()).toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
