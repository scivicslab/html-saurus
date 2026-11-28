package com.scivicslab.htmlsaurus;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Decides whether a document prints on A4 portrait or A4 landscape, from the tables in its HTML.
 *
 * <p>The estimate imitates the automatic table layout of a browser without laying anything out: each column gets
 * a minimum width (its longest unbreakable run) and a maximum width (its longest cell on one line), and the
 * columns share the content width in proportion to {@code max - min}. Text may break between any two
 * East Asian wide characters and at spaces; a run of Latin letters inside {@code code} may break anywhere (the
 * print CSS sets {@code overflow-wrap: anywhere}), so it counts as at most {@link #CODE_RUN_CHARS} characters.</p>
 *
 * <p>A table "fits" an orientation when the columns' minimum widths together fit the content width and no
 * column is squeezed below {@link #MIN_COLUMN_FULL_WIDTH_CHARS} full-width characters. A column whose natural
 * width is already below that limit (a number, a mark) is not squeezed and does not count.</p>
 */
public final class PrintWidthEstimator {

    /** The one setting to change: the narrowest a squeezed column may get, in full-width characters. */
    public static final int MIN_COLUMN_FULL_WIDTH_CHARS = 6;

    /** Content width of A4 portrait with 16 mm side margins, in CSS px (96 per inch). */
    public static final double PORTRAIT_CONTENT_PX = 673;
    /** Content width of A4 landscape with 16 mm side margins, in CSS px. */
    public static final double LANDSCAPE_CONTENT_PX = 1001;

    static final double BODY_FONT_PX = 16;
    static final double CODE_FONT_PX = 14;
    /** Width of a half-width character as a fraction of the font size, measured in Noto Sans JP. */
    static final double HALF_WIDTH_EM = 0.55;
    /** Width of a half-width character inside {@code code} (monospace). */
    static final double CODE_HALF_WIDTH_EM = 0.6;
    /** Padding (12 px twice) and border (1 px) of a cell. */
    static final double CELL_EXTRA_PX = 25;
    /** How many characters of a Latin run inside {@code code} count as unbreakable. */
    static final int CODE_RUN_CHARS = 8;

    private PrintWidthEstimator() {}

    /** The two orientations a document can print in. */
    public enum Layout { PORTRAIT, LANDSCAPE }

    /**
     * What the estimate says about one table.
     *
     * @param columns             number of columns
     * @param sumMin              sum of the columns' minimum widths in px
     * @param sumMax              sum of the columns' natural widths in px
     * @param portraitNarrowest   narrowest squeezed column on portrait in px; {@link Double#POSITIVE_INFINITY} if none is squeezed
     * @param landscapeNarrowest  the same on landscape
     */
    public record TableFit(int columns, double sumMin, double sumMax, double portraitNarrowest, double landscapeNarrowest) {
        public boolean fitsPortrait(int fullWidthChars) { return fits(sumMin, PORTRAIT_CONTENT_PX, portraitNarrowest, fullWidthChars); }
        public boolean fitsLandscape(int fullWidthChars) { return fits(sumMin, LANDSCAPE_CONTENT_PX, landscapeNarrowest, fullWidthChars); }

        private static boolean fits(double sumMin, double width, double narrowest, int chars) {
            return sumMin <= width && narrowest >= limitPx(chars);
        }
    }

    /** @return the width of {@code chars} full-width characters plus the cell's padding and border, in px */
    public static double limitPx(int chars) {
        return chars * BODY_FONT_PX + CELL_EXTRA_PX;
    }

    /** @return the orientation of a document whose content is {@code html}, with the default limit */
    public static Layout layoutOf(String html) {
        return layoutOf(html, MIN_COLUMN_FULL_WIDTH_CHARS);
    }

    /**
     * Portrait when every table fits portrait. Landscape when some table does not fit portrait and fits landscape.
     * Portrait again when the tables that do not fit portrait fit landscape no better.
     */
    public static Layout layoutOf(String html, int fullWidthChars) {
        if (html == null || !html.contains("<table")) return Layout.PORTRAIT;
        boolean landscapeHelps = false;
        for (TableFit t : analyze(html)) {
            if (t.fitsPortrait(fullWidthChars)) continue;
            if (t.fitsLandscape(fullWidthChars)) landscapeHelps = true;
        }
        return landscapeHelps ? Layout.LANDSCAPE : Layout.PORTRAIT;
    }

    /** @return the estimate of every {@code <table>} in the HTML, in document order, nested tables excluded */
    public static List<TableFit> analyze(String html) {
        List<TableFit> result = new ArrayList<>();
        for (Element table : Jsoup.parseBodyFragment(html).select("table")) {
            if (!table.parents().stream().anyMatch(p -> p.tagName().equals("table"))) result.add(analyze(table));
        }
        return result;
    }

    static TableFit analyze(Element table) {
        List<double[]> columns = new ArrayList<>(); // {min, max}
        for (Element row : table.select("tr")) {
            if (!row.closest("table").equals(table)) continue;
            int index = 0;
            for (Element cell : row.children()) {
                if (!cell.tagName().equals("td") && !cell.tagName().equals("th")) continue;
                int span = Math.max(1, parseInt(cell.attr("colspan")));
                while (columns.size() < index + span) columns.add(new double[] {CELL_EXTRA_PX, CELL_EXTRA_PX});
                if (span == 1) {
                    double[] widths = measure(cell);
                    double[] column = columns.get(index);
                    column[0] = Math.max(column[0], widths[0] + CELL_EXTRA_PX);
                    column[1] = Math.max(column[1], widths[1] + CELL_EXTRA_PX);
                }
                index += span;
            }
        }
        double sumMin = 0, sumMax = 0;
        for (double[] c : columns) { sumMin += c[0]; sumMax += c[1]; }
        return new TableFit(columns.size(), sumMin, sumMax, narrowestSqueezed(columns, sumMin, sumMax, PORTRAIT_CONTENT_PX),
                narrowestSqueezed(columns, sumMin, sumMax, LANDSCAPE_CONTENT_PX));
    }

    /** Shares {@code width} among the columns like automatic table layout and returns the narrowest column that got less than its natural width. */
    private static double narrowestSqueezed(List<double[]> columns, double sumMin, double sumMax, double width) {
        double narrowest = Double.POSITIVE_INFINITY;
        for (double[] c : columns) {
            double allocated;
            if (sumMax <= width) continue;                       // everything fits as written
            else if (sumMin >= width || sumMax == sumMin) allocated = c[0];
            else allocated = c[0] + (width - sumMin) * (c[1] - c[0]) / (sumMax - sumMin);
            if (allocated < c[1] - 0.5) narrowest = Math.min(narrowest, allocated);
        }
        return narrowest;
    }

    /** @return {min, max} width of the text of a cell, without padding */
    private static double[] measure(Element cell) {
        Measure m = new Measure();
        walk(cell, false, m);
        m.endLine();
        return new double[] {m.minRun, m.maxLine};
    }

    private static void walk(Node node, boolean inCode, Measure m) {
        if (node instanceof TextNode text) {
            for (int i = 0; i < text.text().length(); ) {
                int cp = text.text().codePointAt(i);
                i += Character.charCount(cp);
                m.add(cp, inCode);
            }
        } else if (node instanceof Element e) {
            if (e.tagName().equals("br")) { m.endLine(); return; }
            boolean code = inCode || e.tagName().equals("code");
            for (Node child : e.childNodes()) walk(child, code, m);
        }
    }

    /** Accumulates the widest line and the widest unbreakable run of a cell. */
    private static final class Measure {
        double minRun, maxLine, line, run, runCode;
        boolean pendingSpace;

        void add(int cp, boolean code) {
            if (Character.isWhitespace(cp)) {
                endRun();
                pendingSpace = line > 0;
                return;
            }
            double w = widthOf(cp, code);
            if (pendingSpace) { line += BODY_FONT_PX * HALF_WIDTH_EM; pendingSpace = false; }
            line += w;
            if (isWide(cp)) {                                   // a break is allowed on both sides
                endRun();
                minRun = Math.max(minRun, w);
            } else if (code) {
                runCode += w;
            } else {
                run += w;
            }
        }

        void endRun() {
            double codeCap = CODE_RUN_CHARS * CODE_FONT_PX * CODE_HALF_WIDTH_EM;
            minRun = Math.max(minRun, run + Math.min(runCode, codeCap));
            run = 0;
            runCode = 0;
        }

        void endLine() {
            endRun();
            maxLine = Math.max(maxLine, line);
            line = 0;
            pendingSpace = false;
        }
    }

    private static double widthOf(int cp, boolean code) {
        double font = code ? CODE_FONT_PX : BODY_FONT_PX;
        return isWide(cp) ? font : font * (code ? CODE_HALF_WIDTH_EM : HALF_WIDTH_EM);
    }

    /** @return true for East Asian Wide and Fullwidth characters (an approximation by block) */
    static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x2E80 && cp <= 0xA4CF) || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE30 && cp <= 0xFE6F) || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6) || (cp >= 0x20000 && cp <= 0x3FFFD);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 1; }
    }
}
