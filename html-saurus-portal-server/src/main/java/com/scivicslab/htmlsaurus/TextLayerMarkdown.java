package com.scivicslab.htmlsaurus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the glyphs of one PDF page, with the font each was set in and where it sits, into Markdown
 * paragraphs. Pure logic: no PDF, file or network access, so it is tested on glyph lists directly.
 *
 * <p>A PDF made by a typesetting system (pdfTeX, LaTeX, Word) carries its text. The font names say
 * what kind of text each glyph is, which is what a reader of the page sees and an OCR engine has to
 * guess: a typewriter font is code, a bold or italic font is emphasis, a small type out in the
 * margin is a side note, the line at the very top or bottom is the running head or the page number.
 * This class reads those signs instead of guessing.</p>
 *
 * <p>Known limits: a page set in two columns is read across both, line by line; a figure drawn with
 * PDF vector graphics is not in the glyph list and so is not in the output.</p>
 */
final class TextLayerMarkdown {

    /**
     * One glyph as the PDF placed it.
     *
     * @param text  what it stands for ({@code "fi"} for a ligature; empty for a glyph with no text)
     * @param font  the font's name without a subset prefix, e.g. {@code CMTT10}
     * @param size  the size it is set at, in points (only ever compared with other sizes of the page)
     * @param x     left edge
     * @param endX  right edge
     * @param y     baseline, counted down from the top of the page
     */
    record Glyph(String text, String font, float size, float x, float endX, float y) {}

    private static final Pattern MONO =
        Pattern.compile("(?i).*(CMTT|CMVTT|Courier|Mono|Consolas|Typewriter).*");
    private static final Pattern BOLD =
        Pattern.compile("(?i).*(CMBX|CMSSBX|Bold|Black|Heavy).*");
    private static final Pattern ITALIC =
        Pattern.compile("(?i).*(CMTI|CMSL|CMMI|CMSSQI|CMSSI|Italic|Oblique|Ital).*");
    /** Fonts whose glyphs are marks in the layout (a warning sign, a bullet picture), not words. */
    private static final Pattern DECORATIVE =
        Pattern.compile("(?i).*(manfnt|dingbat|zapf|wingding).*");

    /** A gap between two glyphs wider than this share of the font size is a space. */
    private static final float SPACE_GAP = 0.2f;
    /** A line is split where two glyphs are further apart than this many body-text sizes. */
    private static final float SEGMENT_GAP = 2.5f;
    /** Width of one character of a typewriter font, in ems. */
    private static final float MONO_ADVANCE = 0.525f;

    private TextLayerMarkdown() {}

    /** One segment of a line: glyphs that sit together, apart from the rest of the line. */
    private static final class Segment {
        final List<Glyph> glyphs = new ArrayList<>();
        float left() { return glyphs.get(0).x(); }
        float right() { return glyphs.get(glyphs.size() - 1).endX(); }
        float size() {
            float sum = 0;
            for (Glyph g : glyphs) sum += g.size();
            return sum / glyphs.size();
        }
    }

    /** A line of the page's body text, ready to be joined into a paragraph or a code block. */
    private static final class Line {
        float y;
        float left;
        float right;
        float size;
        String md = "";
        boolean mono;
        boolean hyphenEnd;
        boolean startsLower;
        int chars;
    }

    /**
     * @param glyphs every glyph of the page, in any order
     * @return the page as Markdown paragraphs, top to bottom; empty when the page has no text
     */
    static List<String> render(List<Glyph> glyphs) {
        return render(glyphs, true);
    }

    private static List<String> render(List<Glyph> glyphs, boolean splitColumns) {
        List<Glyph> kept = new ArrayList<>();
        for (Glyph g : glyphs) {
            if (DECORATIVE.matcher(g.font()).matches()) continue;
            String t = normalise(g.text());
            if (t.isEmpty()) continue;
            kept.add(new Glyph(t, g.font(), g.size(), g.x(), g.endX(), g.y()));
        }
        if (kept.isEmpty()) return List.of();

        float body = bodySize(kept);
        if (splitColumns) {
            float gutter = gutter(kept, body);
            if (!Float.isNaN(gutter)) {
                // Two columns are read one after the other, not across.
                List<Glyph> left = new ArrayList<>();
                List<Glyph> right = new ArrayList<>();
                for (Glyph g : kept) (((g.x() + g.endX()) / 2 < gutter) ? left : right).add(g);
                List<String> both = new ArrayList<>(render(left, false));
                both.addAll(render(right, false));
                return both;
            }
        }
        List<List<Segment>> rows = rows(kept, body);

        // The right edge of the body text: the longest line of ordinary-size type. Small type
        // starting at or beyond it is a note in the margin.
        float bodyRight = 0;
        for (List<Segment> row : rows) {
            for (Segment s : row) {
                if (s.size() >= 0.85f * body && s.glyphs.size() >= 20) bodyRight = Math.max(bodyRight, s.right());
            }
        }

        List<Line> lines = new ArrayList<>();
        for (List<Segment> row : rows) {
            List<Segment> own = new ArrayList<>();
            for (Segment s : row) {
                boolean margin = s.size() <= 0.85f * body && bodyRight > 0 && s.left() >= bodyRight - 5;
                if (!margin) own.add(s);
            }
            if (own.isEmpty()) continue;
            lines.add(line(own, body));
        }
        dropRunningHeadAndFoot(lines, body);
        return blocks(lines, body, bodyRight);
    }

    // ------------------------------------------------------------------
    // Glyphs
    // ------------------------------------------------------------------

    /** Ligatures to their letters, and no control or private-use characters. */
    private static String normalise(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'ﬀ' -> out.append("ff");
                case 'ﬁ' -> out.append("fi");
                case 'ﬂ' -> out.append("fl");
                case 'ﬃ' -> out.append("ffi");
                case 'ﬄ' -> out.append("ffl");
                default -> {
                    if (c >= ' ' && !(c >= '' && c <= '')) out.append(c);
                }
            }
        }
        return out.toString();
    }

    /** The size most of the page's characters are set in. */
    private static float bodySize(List<Glyph> glyphs) {
        Map<Integer, Integer> bySize = new HashMap<>();
        for (Glyph g : glyphs) bySize.merge(Math.round(g.size() * 2), g.text().length(), Integer::sum);
        int best = 0, bestCount = -1;
        for (Map.Entry<Integer, Integer> e : bySize.entrySet()) {
            if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
        }
        return best / 2f;
    }

    // ------------------------------------------------------------------
    // Columns
    // ------------------------------------------------------------------

    /**
     * Where the gutter between two columns is, or NaN when the page is not set in two columns: a
     * strip in the middle of the page, wider than a word space, that almost no glyph crosses, with
     * a good share of the page's glyphs on each side. A heading across both columns does not count
     * against it.
     */
    private static float gutter(List<Glyph> glyphs, float body) {
        float minX = Float.MAX_VALUE, maxX = 0;
        int counted = 0;
        for (Glyph g : glyphs) {
            if (g.size() > 1.2f * body) continue;
            minX = Math.min(minX, g.x());
            maxX = Math.max(maxX, g.endX());
            counted++;
        }
        if (counted < 200 || maxX - minX < 20 * body) return Float.NaN;
        int origin = (int) Math.floor(minX);
        int[] covered = new int[(int) Math.ceil(maxX) - origin + 2];
        for (Glyph g : glyphs) {
            if (g.size() > 1.2f * body) continue;
            for (int x = (int) Math.floor(g.x()); x <= (int) Math.ceil(g.endX()); x++) covered[x - origin]++;
        }
        int from = origin + (int) (0.35f * (maxX - minX));
        int to = origin + (int) (0.65f * (maxX - minX));
        int allowed = Math.max(1, counted / 100);
        int bestStart = -1, bestWidth = 0, start = -1;
        for (int x = from; x <= to + 1; x++) {
            boolean clear = x <= to && covered[x - origin] <= allowed;
            if (clear && start < 0) start = x;
            if (!clear && start >= 0) {
                if (x - start > bestWidth) { bestWidth = x - start; bestStart = start; }
                start = -1;
            }
        }
        if (bestStart < 0 || bestWidth < 1.2f * body) return Float.NaN;
        float centre = bestStart + bestWidth / 2f;
        int left = 0, right = 0;
        for (Glyph g : glyphs) {
            if ((g.x() + g.endX()) / 2 < centre) left++; else right++;
        }
        return left >= 0.25f * glyphs.size() && right >= 0.25f * glyphs.size() ? centre : Float.NaN;
    }

    // ------------------------------------------------------------------
    // Rows and segments
    // ------------------------------------------------------------------

    /** Glyphs grouped by baseline, each row cut into segments where the gap is wide. */
    private static List<List<Segment>> rows(List<Glyph> glyphs, float body) {
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x));
        List<List<Glyph>> byBaseline = new ArrayList<>();
        List<Glyph> current = null;
        float refY = 0;
        for (Glyph g : sorted) {
            // Raised and lowered glyphs (a subscript, the dropped E of the TeX logo) stay on their line.
            if (current == null || g.y() - refY > 0.55f * body) {
                current = new ArrayList<>();
                byBaseline.add(current);
                refY = g.y();
            }
            current.add(g);
            refY = (refY * (current.size() - 1) + g.y()) / current.size();
        }
        List<List<Segment>> rows = new ArrayList<>();
        for (List<Glyph> row : byBaseline) {
            row.sort(Comparator.comparingDouble(Glyph::x));
            List<Segment> segments = new ArrayList<>();
            Segment seg = null;
            Glyph prev = null;
            for (Glyph g : row) {
                if (seg == null || g.x() - prev.endX() > SEGMENT_GAP * body) {
                    seg = new Segment();
                    segments.add(seg);
                }
                seg.glyphs.add(g);
                prev = g;
            }
            rows.add(segments);
        }
        return rows;
    }

    /**
     * Removes the running head and the page number: the first or last line of the page when it is
     * short, no bigger than the body type, and set apart from the next line by more than a blank
     * line's worth of space. Where the body text starts on the page is not assumed (a book page
     * shrunk onto a larger sheet leaves the head far from the paper's edge), only that the head is
     * separated from the text.
     */
    private static void dropRunningHeadAndFoot(List<Line> lines, float body) {
        if (lines.size() == 1 && lines.get(0).md.matches("\\d{1,4}")) {
            lines.clear();
            return;
        }
        if (lines.size() < 3) return;
        List<Float> steps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) steps.add(lines.get(i).y - lines.get(i - 1).y);
        steps.sort(null);
        float step = steps.get(steps.size() / 2);
        Line first = lines.get(0);
        if (isEdgeLine(first, body) && lines.get(1).y - first.y >= 1.5f * step) lines.remove(0);
        Line last = lines.get(lines.size() - 1);
        if (isEdgeLine(last, body) && last.y - lines.get(lines.size() - 2).y >= 1.5f * step) {
            lines.remove(lines.size() - 1);
        }
    }

    private static boolean isEdgeLine(Line line, float body) {
        return line.chars <= 90 && line.size <= 1.15f * body && !line.mono;
    }

    // ------------------------------------------------------------------
    // Lines
    // ------------------------------------------------------------------

    private static Line line(List<Segment> segments, float body) {
        List<Glyph> glyphs = new ArrayList<>();
        for (Segment s : segments) glyphs.addAll(s.glyphs);
        Line line = new Line();
        line.left = glyphs.get(0).x();
        line.right = glyphs.get(glyphs.size() - 1).endX();
        float ySum = 0, sizeSum = 0;
        boolean allMono = true;
        for (Glyph g : glyphs) {
            ySum += g.y();
            sizeSum += g.size();
            allMono &= MONO.matcher(g.font()).matches();
            line.chars += g.text().length();
        }
        line.y = ySum / glyphs.size();
        line.size = sizeSum / glyphs.size();
        line.mono = allMono;

        // A hyphen that only breaks a word across lines is not part of the text.
        int n = glyphs.size();
        if (n >= 2 && glyphs.get(n - 1).text().equals("-") && Character.isLetter(lastChar(glyphs.get(n - 2).text()))) {
            line.hyphenEnd = true;
            glyphs = glyphs.subList(0, n - 1);
        }
        line.startsLower = Character.isLowerCase(glyphs.get(0).text().charAt(0));
        line.md = allMono ? plainWithSpaces(glyphs) : styled(glyphs);
        return line;
    }

    private static char lastChar(String s) {
        return s.charAt(s.length() - 1);
    }

    /** Text of a typewriter line: the number of spaces follows the width of the gap, so indentation survives. */
    private static String plainWithSpaces(List<Glyph> glyphs) {
        StringBuilder sb = new StringBuilder();
        Glyph prev = null;
        for (Glyph g : glyphs) {
            if (prev != null) {
                float gap = g.x() - prev.endX();
                float advance = MONO_ADVANCE * g.size();
                int spaces = Math.round(gap / advance);
                if (spaces <= 0 && gap > SPACE_GAP * g.size()) spaces = 1;
                for (int i = 0; i < spaces; i++) sb.append(' ');
            }
            sb.append(g.text());
            prev = g;
        }
        return sb.toString();
    }

    /** The line's text with code, bold and italic marked, and the TeX logo written out. */
    private static String styled(List<Glyph> glyphs) {
        StringBuilder out = new StringBuilder();
        String openStyle = "";
        StringBuilder run = new StringBuilder();
        boolean pendingSpace = false;
        Glyph prev = null;
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph g = glyphs.get(i);
            String text = logoLetter(glyphs, i);
            if (prev != null && g.x() - prev.endX() > SPACE_GAP * prev.size()) pendingSpace = true;
            if (text.equals(" ")) { pendingSpace = true; prev = g; continue; }
            String style = styleOf(g.font());
            if (!style.equals(openStyle)) {
                out.append(wrap(openStyle, run.toString()));
                run.setLength(0);
                openStyle = style;
                if (pendingSpace && out.length() > 0) out.append(' ');
                pendingSpace = false;
            } else if (pendingSpace) {
                run.append(' ');
                pendingSpace = false;
            }
            run.append(text);
            prev = g;
        }
        out.append(wrap(openStyle, run.toString()));
        return out.toString();
    }

    /** The letter to write for glyph {@code i}: the dropped E of the TeX logo is written as a lowercase e. */
    private static String logoLetter(List<Glyph> glyphs, int i) {
        Glyph g = glyphs.get(i);
        if (g.text().equals("E") && i > 0 && i + 1 < glyphs.size()
                && glyphs.get(i - 1).text().equals("T") && glyphs.get(i + 1).text().equals("X")
                && g.y() - glyphs.get(i - 1).y() > 0.1f * g.size()) {
            return "e";
        }
        if (g.text().equals("A") && i > 0 && i + 1 < glyphs.size()
                && glyphs.get(i - 1).text().equals("L") && glyphs.get(i + 1).text().equals("T")
                && glyphs.get(i - 1).y() - g.y() > 0.1f * g.size()) {
            return "a";
        }
        return g.text();
    }

    private static String styleOf(String font) {
        if (MONO.matcher(font).matches()) return "code";
        boolean bold = BOLD.matcher(font).matches();
        boolean italic = ITALIC.matcher(font).matches();
        if (bold && italic) return "bolditalic";
        if (bold) return "bold";
        if (italic) return "italic";
        return "";
    }

    private static String wrap(String style, String text) {
        if (text.isEmpty()) return "";
        return switch (style) {
            case "code" -> codeSpan(text);
            case "bold" -> "**" + escape(text) + "**";
            case "italic" -> "*" + escape(text) + "*";
            case "bolditalic" -> "***" + escape(text) + "***";
            default -> escape(text);
        };
    }

    private static String codeSpan(String text) {
        String fence = text.contains("`") ? "``" : "`";
        String pad = text.startsWith("`") || text.endsWith("`") ? " " : "";
        return fence + pad + text + pad + fence;
    }

    /** Characters Markdown would read as markup, in text that is not code. */
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\', '*', '_', '`', '[', ']' -> out.append('\\').append(c);
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------

    private static List<String> blocks(List<Line> lines, float body, float bodyRight) {
        float bodyLeft = commonLeft(lines);
        List<String> out = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        Line previous = null;          // the previous line of the paragraph being built
        List<Line> code = new ArrayList<>();
        List<Line> heading = new ArrayList<>();

        for (Line line : lines) {
            boolean isHeading = !line.mono && line.size >= 1.2f * body && line.chars <= 120;
            if (line.mono) {
                flush(out, paragraph);
                flushHeading(out, heading, body);
                previous = null;
                if (!code.isEmpty() && line.y - code.get(code.size() - 1).y > 1.8f * line.size) flushCode(out, code);
                code.add(line);
                continue;
            }
            flushCode(out, code);
            if (isHeading) {
                flush(out, paragraph);
                previous = null;
                if (!heading.isEmpty() && line.y - heading.get(heading.size() - 1).y > 2.5f * line.size) {
                    flushHeading(out, heading, body);
                }
                heading.add(line);
                continue;
            }
            flushHeading(out, heading, body);
            if (previous != null && startsNewParagraph(previous, line, body, bodyLeft, bodyRight)) {
                flush(out, paragraph);
                previous = null;
            }
            if (paragraph.length() > 0) {
                if (previous != null && previous.hyphenEnd && line.startsLower) {
                    // The word was broken across the lines: join it back.
                } else {
                    if (previous != null && previous.hyphenEnd) paragraph.append('-');
                    paragraph.append(' ');
                }
            }
            paragraph.append(line.md);
            previous = line;
        }
        flush(out, paragraph);
        flushCode(out, code);
        flushHeading(out, heading, body);
        return out;
    }

    private static boolean startsNewParagraph(Line prev, Line line, float body, float bodyLeft, float bodyRight) {
        float step = line.y - prev.y;
        if (step > 1.7f * Math.max(prev.size, line.size) * 1.2f) return true;       // a blank line between
        boolean indented = line.left - bodyLeft > 0.6f * body;
        boolean prevIndented = prev.left - bodyLeft > 0.6f * body;
        if (indented && !prevIndented) return true;                                   // first-line indent
        return bodyRight > 0 && prev.right < bodyRight - 2.5f * body;                // the previous line ended short
    }

    /** The left edge most lines start at: the body text's margin, not a first-line indent. */
    private static float commonLeft(List<Line> lines) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Line l : lines) counts.merge(Math.round(l.left), 1, Integer::sum);
        int best = 0, bestCount = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() < best)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static void flush(List<String> out, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            out.add(paragraph.toString().strip());
            paragraph.setLength(0);
        }
    }

    private static void flushCode(List<String> out, List<Line> code) {
        if (code.isEmpty()) return;
        float minLeft = Float.MAX_VALUE;
        for (Line l : code) minLeft = Math.min(minLeft, l.left);
        StringBuilder sb = new StringBuilder("```\n");
        for (Line l : code) {
            int indent = Math.round((l.left - minLeft) / (MONO_ADVANCE * l.size));
            sb.append(" ".repeat(Math.max(0, indent))).append(l.md).append('\n');
        }
        sb.append("```");
        out.add(sb.toString());
        code.clear();
    }

    private static void flushHeading(List<String> out, List<Line> heading, float body) {
        if (heading.isEmpty()) return;
        float ratio = heading.get(0).size / body;
        String marks = ratio >= 2.0f ? "#" : ratio >= 1.5f ? "##" : "###";
        StringBuilder text = new StringBuilder();
        for (Line l : heading) {
            if (text.length() > 0) text.append(' ');
            text.append(l.md);
        }
        out.add(marks + " " + text);
        heading.clear();
    }
}
