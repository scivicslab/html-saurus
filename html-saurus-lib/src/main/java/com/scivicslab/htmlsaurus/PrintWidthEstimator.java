package com.scivicslab.htmlsaurus;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Decides how each table of a document is printed on A4, and so whether the document is portrait or landscape.
 *
 * <p>Every column is classified by its content, and the class fixes the narrowest the column may get:</p>
 * <ul>
 *   <li>{@link ColumnKind#SHORT}: the longest cell is at most {@link #SHORT_MAX_CHARS} full-width characters. It does
 *       not wrap; its minimum is the width of its longest cell on one line.</li>
 *   <li>{@link ColumnKind#IDENTIFIER}: most cells are {@code code}. It may wrap anywhere (the print CSS sets
 *       {@code overflow-wrap: anywhere}); its minimum is {@link #IDENTIFIER_MIN_CHARS} full-width characters.</li>
 *   <li>{@link ColumnKind#TEXT}: anything else. It may wrap; its minimum is {@link #TEXT_MIN_CHARS} full-width
 *       characters.</li>
 * </ul>
 *
 * <p>A table whose column minimums fit A4 portrait is {@link Verdict#PORTRAIT}; failing that, one that fits A4 landscape
 * is {@link Verdict#LANDSCAPE}; one that fits neither is {@link Verdict#REFLOW}. A table that fits but would wrap into
 * too many tall rows (see {@link #TALL_ROW_LINES} and {@link #TALL_ROW_SHARE}) is {@code REFLOW} too: each of its rows
 * becomes a small two-column table of heading, name and value. A document is landscape when any of its tables is.</p>
 *
 * <p>Widths imitate the automatic table layout of a browser without laying anything out: the columns share the
 * content width in proportion to {@code max - min}. Full-width characters are 16 px, half-width 0.55 em, half-width
 * inside {@code code} 0.6 em of 14 px; a cell adds 25 px of padding and border. These figures agreed with Chrome's
 * measurement of real tables to within a few pixels.</p>
 */
public final class PrintWidthEstimator {

    /** A column whose longest cell is at most this many full-width characters is {@link ColumnKind#SHORT}. */
    public static final int SHORT_MAX_CHARS = 8;
    /** Narrowest an {@link ColumnKind#IDENTIFIER} column may get, in full-width characters. */
    public static final int IDENTIFIER_MIN_CHARS = 6;
    /** Narrowest a {@link ColumnKind#TEXT} column may get, in full-width characters. */
    public static final int TEXT_MIN_CHARS = 10;
    /** A row estimated at more lines than this is tall. */
    public static final int TALL_ROW_LINES = 6;
    /** A table is reflowed when more than this share of its rows are tall. */
    public static final double TALL_ROW_SHARE = 0.3;
    /** A column is {@link ColumnKind#IDENTIFIER} when more than this share of its cells are mostly {@code code}. */
    public static final double IDENTIFIER_CELL_SHARE = 0.5;

    /** Content width of A4 portrait with 16 mm side margins, in CSS px (96 per inch). */
    public static final double PORTRAIT_CONTENT_PX = 673;
    /** Content width of A4 landscape with 16 mm side margins, in CSS px. */
    public static final double LANDSCAPE_CONTENT_PX = 1001;

    static final double BODY_FONT_PX = 16;
    static final double CODE_FONT_PX = 14;
    static final double HALF_WIDTH_EM = 0.55;
    static final double CODE_HALF_WIDTH_EM = 0.6;
    /** Padding (12 px twice) and border (1 px) of a cell. */
    static final double CELL_EXTRA_PX = 25;

    /** Class that marks a table that has a reflowed copy. */
    public static final String HAS_CARDS_CLASS = "has-cards";
    /** Class of the block that holds the reflowed copy. */
    public static final String CARDS_CLASS = "print-cards";

    private PrintWidthEstimator() {}

    /** The two orientations a document can print in. */
    public enum Layout { PORTRAIT, LANDSCAPE }

    /** What a column holds, which decides how narrow it may get. */
    public enum ColumnKind { SHORT, IDENTIFIER, TEXT }

    /** How one table is printed. */
    public enum Verdict { PORTRAIT, LANDSCAPE, REFLOW }

    /**
     * One column.
     *
     * @param kind what it holds
     * @param min  narrowest width in px, with padding
     * @param max  width in px with its longest cell on one line
     */
    public record Column(ColumnKind kind, double min, double max) {}

    /**
     * The estimate for one table.
     *
     * @param columns         its columns, left to right
     * @param sumMin          sum of the columns' minimum widths
     * @param tallShare       share of rows estimated as tall, on the orientation the table would print in
     *                        (portrait when it fits portrait, else landscape; 0 when it fits neither)
     * @param verdict         how it prints
     */
    public record TableJudgement(List<Column> columns, double sumMin, double tallShare, Verdict verdict) {}

    /**
     * @param layout orientation of the document
     * @param html   the content to put on the page: the input, or the input with a reflowed copy after each table
     *               that needs one
     */
    public record Result(Layout layout, String html) {}

    /** @return the orientation of the document and its content with the reflowed copies added */
    public static Result decide(String html) {
        if (html == null || !html.contains("<table")) return new Result(Layout.PORTRAIT, html);
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        boolean landscape = false, reflow = false;
        for (Element table : topLevelTables(doc)) {
            Table t = read(table);
            TableJudgement j = judge(t);
            if (j.verdict() == Verdict.LANDSCAPE) landscape = true;
            if (j.verdict() == Verdict.REFLOW) {
                reflow = true;
                table.addClass(HAS_CARDS_CLASS);
                table.after(cardsOf(t));
            }
        }
        return new Result(landscape ? Layout.LANDSCAPE : Layout.PORTRAIT, reflow ? doc.body().html() : html);
    }

    /** @return the orientation of a document whose content is {@code html} */
    public static Layout layoutOf(String html) {
        return decide(html).layout();
    }

    /** @return the judgement of every table in the HTML, in document order, nested tables excluded */
    public static List<TableJudgement> analyze(String html) {
        List<TableJudgement> result = new ArrayList<>();
        for (Element table : topLevelTables(Jsoup.parseBodyFragment(html))) result.add(judge(read(table)));
        return result;
    }

    private static List<Element> topLevelTables(Document doc) {
        List<Element> tables = new ArrayList<>();
        for (Element table : doc.select("table")) {
            if (table.parents().stream().noneMatch(p -> p.tagName().equals("table")) && !table.hasClass(HAS_CARDS_CLASS)
                    && table.parents().stream().noneMatch(p -> p.hasClass(CARDS_CLASS))) tables.add(table);
        }
        return tables;
    }

    // ---------------------------------------------------------------- reading a table

    /** One cell: its text measured, and the element it came from. */
    private record Cell(Element element, List<Double> lines, double codeWidth, double totalWidth) {
        double oneLine() { return lines.stream().mapToDouble(Double::doubleValue).max().orElse(0); }
        boolean empty() { return totalWidth == 0; }
        boolean mostlyCode() { return totalWidth > 0 && codeWidth * 2 >= totalWidth; }
    }

    /** A table read: header names, and the cells of each body row (null where a row has no cell). */
    private record Table(List<String> names, List<List<Cell>> rows, List<Cell> headers, int columns) {}

    private static Table read(Element table) {
        List<Cell> headers = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<List<Cell>> rows = new ArrayList<>();
        int columns = 0;
        boolean headerTaken = false;
        for (Element row : table.select("tr")) {
            if (!table.equals(row.closest("table"))) continue;
            List<Cell> cells = new ArrayList<>();
            boolean allTh = true;
            int index = 0;
            for (Element cell : row.children()) {
                if (!cell.tagName().equals("td") && !cell.tagName().equals("th")) continue;
                if (!cell.tagName().equals("th")) allTh = false;
                int span = Math.max(1, parseInt(cell.attr("colspan")));
                while (cells.size() < index) cells.add(null);
                cells.add(span == 1 ? measure(cell) : null);
                for (int k = 1; k < span; k++) cells.add(null);
                index += span;
            }
            columns = Math.max(columns, cells.size());
            if (!headerTaken && allTh && !cells.isEmpty()) {
                headerTaken = true;
                headers = cells;
                for (Element cell : row.children()) names.add(cell.text());
            } else {
                rows.add(cells);
            }
        }
        return new Table(names, rows, headers, columns);
    }

    private static Cell measure(Element cell) {
        Measure m = new Measure();
        walk(cell, false, m);
        m.endLine();
        return new Cell(cell, m.lines, m.codeWidth, m.totalWidth);
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

    /** Accumulates the width of each line of a cell, and how much of it is {@code code}. */
    private static final class Measure {
        final List<Double> lines = new ArrayList<>();
        double line, codeWidth, totalWidth;
        boolean pendingSpace;

        void add(int cp, boolean code) {
            if (Character.isWhitespace(cp)) {
                pendingSpace = line > 0;
                return;
            }
            if (pendingSpace) { double s = BODY_FONT_PX * HALF_WIDTH_EM; line += s; totalWidth += s; pendingSpace = false; }
            double w = widthOf(cp, code);
            line += w;
            totalWidth += w;
            if (code) codeWidth += w;
        }

        void endLine() {
            if (line > 0) lines.add(line);
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

    // ---------------------------------------------------------------- judging

    private static TableJudgement judge(Table t) {
        List<Column> columns = new ArrayList<>();
        for (int c = 0; c < t.columns(); c++) columns.add(classify(t, c));
        double sumMin = columns.stream().mapToDouble(Column::min).sum();
        Verdict verdict;
        double tall = 0;
        if (sumMin <= PORTRAIT_CONTENT_PX) {
            tall = tallShare(t, columns, PORTRAIT_CONTENT_PX);
            verdict = tall > TALL_ROW_SHARE ? Verdict.REFLOW : Verdict.PORTRAIT;
        } else if (sumMin <= LANDSCAPE_CONTENT_PX) {
            tall = tallShare(t, columns, LANDSCAPE_CONTENT_PX);
            verdict = tall > TALL_ROW_SHARE ? Verdict.REFLOW : Verdict.LANDSCAPE;
        } else {
            verdict = Verdict.REFLOW;
        }
        return new TableJudgement(columns, sumMin, tall, verdict);
    }

    private static Column classify(Table t, int c) {
        double longest = 0;
        int cells = 0, codeCells = 0;
        for (List<Cell> row : t.rows()) {
            Cell cell = c < row.size() ? row.get(c) : null;
            if (cell == null || cell.empty()) continue;
            longest = Math.max(longest, cell.oneLine());
            cells++;
            if (cell.mostlyCode()) codeCells++;
        }
        if (cells == 0) {                       // no body cell: the header alone decides
            Cell h = c < t.headers().size() ? t.headers().get(c) : null;
            longest = h == null ? 0 : h.oneLine();
        }
        double max = longest + CELL_EXTRA_PX;
        double header = c < t.headers().size() && t.headers().get(c) != null ? t.headers().get(c).oneLine() : 0;
        if (longest <= SHORT_MAX_CHARS * BODY_FONT_PX) {
            // Kept on one line, but never narrower than a header that is itself a short word.
            double min = max;
            return new Column(ColumnKind.SHORT, min, Math.max(max, Math.min(header, SHORT_MAX_CHARS * BODY_FONT_PX) + CELL_EXTRA_PX));
        }
        if (codeCells > cells * IDENTIFIER_CELL_SHARE) {
            return new Column(ColumnKind.IDENTIFIER, Math.min(max, IDENTIFIER_MIN_CHARS * BODY_FONT_PX + CELL_EXTRA_PX), max);
        }
        return new Column(ColumnKind.TEXT, Math.min(max, TEXT_MIN_CHARS * BODY_FONT_PX + CELL_EXTRA_PX), max);
    }

    /** Shares {@code width} among the columns like automatic table layout. */
    private static double[] allocate(List<Column> columns, double width) {
        double sumMin = columns.stream().mapToDouble(Column::min).sum();
        double sumMax = columns.stream().mapToDouble(Column::max).sum();
        double[] w = new double[columns.size()];
        for (int i = 0; i < w.length; i++) {
            Column c = columns.get(i);
            if (sumMax <= width) w[i] = c.max();
            else if (sumMin >= width || sumMax == sumMin) w[i] = c.min();
            else w[i] = c.min() + (width - sumMin) * (c.max() - c.min()) / (sumMax - sumMin);
        }
        return w;
    }

    /** @return the share of body rows estimated at more than {@link #TALL_ROW_LINES} lines */
    private static double tallShare(Table t, List<Column> columns, double width) {
        if (t.rows().isEmpty()) return 0;
        double[] alloc = allocate(columns, width);
        int tall = 0;
        for (List<Cell> row : t.rows()) {
            int lines = 1;
            for (int c = 0; c < columns.size() && c < row.size(); c++) {
                Cell cell = row.get(c);
                if (cell == null || cell.empty() || columns.get(c).kind() == ColumnKind.SHORT) continue;
                double avail = Math.max(BODY_FONT_PX, alloc[c] - CELL_EXTRA_PX);
                int n = 0;
                for (double line : cell.lines()) n += Math.max(1, (int) Math.ceil(line / avail));
                lines = Math.max(lines, n);
            }
            if (lines > TALL_ROW_LINES) tall++;
        }
        return tall / (double) t.rows().size();
    }

    // ---------------------------------------------------------------- the reflowed copy

    /** One small table per body row: a heading (the first column's value), then a name and value for each other column. */
    private static Element cardsOf(Table t) {
        Element block = new Element("div").addClass(CARDS_CLASS);
        int number = 0;
        for (List<Cell> row : t.rows()) {
            number++;
            Element card = block.appendElement("table").addClass("print-card");
            String heading = row.isEmpty() || row.get(0) == null ? "" : row.get(0).element().text();
            card.appendElement("caption").text(heading.isBlank() ? "Row " + number : heading);
            Element body = card.appendElement("tbody");
            for (int c = 1; c < row.size(); c++) {
                Cell cell = row.get(c);
                if (cell == null || cell.empty()) continue;
                Element tr = body.appendElement("tr");
                tr.appendElement("th").text(c < t.names().size() ? t.names().get(c) : "Column " + (c + 1));
                tr.appendElement("td").html(cell.element().html());
            }
        }
        return block;
    }
}
