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
 * <p>Whether a table is shown as a table or as one small table per row (a card) depends on the table alone, not on the
 * screen or the paper. The length of a column is the length of its longest body cell in full-width characters
 * (a half-width character counts 0.55; one in {@code code} a little less). A table of two or more
 * columns is {@link Verdict#CARDS} when</p>
 * <ol>
 *   <li>({@link Rule#MANY_SHORT_COLUMNS}) every column is {@link ColumnKind#SHORT} and there are at least
 *       {@link #MANY_SHORT_COLUMNS} of them; or</li>
 *   <li>({@link Rule#TWO_VERY_LONG}) at least {@link #VERY_LONG_COLUMNS_FOR_CARDS} columns are
 *       {@link #VERY_LONG_COLUMN_CHARS} characters or longer; or</li>
 *   <li>({@link Rule#THREE_LONG}) there are at most {@link #MAX_COLUMNS_FOR_LONG_RULE} columns and at least
 *       {@link #LONG_COLUMNS_FOR_CARDS} of them are {@link #LONG_COLUMN_CHARS} characters or longer; or</li>
 *   <li>({@link Rule#WIDER_THAN_LANDSCAPE}) the minimum widths of its columns add up to more than
 *       {@link #LANDSCAPE_CONTENT_PX} px, the content area of A4 landscape. Such a table is cut on paper whatever the
 *       length of its columns; the estimate does not depend on the orientation, so screen and print agree.</li>
 * </ol>
 * <p>Any other table stays a table: {@link Verdict#PORTRAIT} when its column minimums fit A4 portrait, else
 * {@link Verdict#LANDSCAPE} (it fits, by rule 4). A document is landscape when any of its tables is.</p>
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
    /** Rule 1: a table whose columns are all short is cards from this many columns on. */
    public static final int MANY_SHORT_COLUMNS = 13;
    /** Rule 2: a column this long, in full-width characters, is very long. */
    public static final int VERY_LONG_COLUMN_CHARS = 100;
    /** Rule 2: this many very long columns make cards, whatever the number of columns. */
    public static final int VERY_LONG_COLUMNS_FOR_CARDS = 2;
    /** Rule 3: a column this long, in full-width characters, is long. */
    public static final int LONG_COLUMN_CHARS = 25;
    /** Rule 3: this many long columns make cards ... */
    public static final int LONG_COLUMNS_FOR_CARDS = 3;
    /** Rule 3: ... in a table of at most this many columns. */
    public static final int MAX_COLUMNS_FOR_LONG_RULE = 12;
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

    /** Class of a table the rules turn into cards. */
    public static final String HAS_CARDS_CLASS = "rule-cards";
    /** Class of a table the rules keep as a table (it still has cards, shown only when the reader forces them). */
    public static final String KEPT_TABLE_CLASS = "rule-table";
    /** Class of the block of cards that follows every table of two or more columns. */
    public static final String CARDS_CLASS = "table-cards";

    private PrintWidthEstimator() {}

    /** The two orientations a document can print in. */
    public enum Layout { PORTRAIT, LANDSCAPE }

    /** What a column holds, which decides how narrow it may get. */
    public enum ColumnKind { SHORT, IDENTIFIER, TEXT }

    /** How one table is printed. */
    public enum Verdict { PORTRAIT, LANDSCAPE, CARDS }

    /** Which rule turned a table into cards. */
    public enum Rule { NONE, MANY_SHORT_COLUMNS, TWO_VERY_LONG, THREE_LONG, WIDER_THAN_LANDSCAPE }

    /**
     * One column.
     *
     * @param kind what it holds
     * @param min  narrowest width in px, with padding
     * @param max  width in px with its longest cell on one line
     * @param chars length of its longest body cell in full-width characters
     */
    public record Column(ColumnKind kind, double min, double max, double chars) {}

    /**
     * The estimate for one table.
     *
     * @param columns         its columns, left to right
     * @param sumMin          sum of the columns' minimum widths
     * @param rule            the rule that made it cards, or {@link Rule#NONE}
     * @param verdict         how it is shown
     */
    public record TableJudgement(List<Column> columns, double sumMin, Rule rule, Verdict verdict) {}

    /**
     * @param layout orientation of the document
     * @param html   the content to put on the page: the input with a block of cards after each table of two or more
     *               columns, each table marked with the verdict of the rules
     */
    public record Result(Layout layout, String html) {}

    /** @return the orientation of the document and its content with the reflowed copies added */
    public static Result decide(String html) {
        if (html == null || !html.contains("<table")) return new Result(Layout.PORTRAIT, html);
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        boolean landscape = false, marked = false;
        for (Element table : topLevelTables(doc)) {
            Table t = read(table);
            if (t.columns() < 2) continue;
            TableJudgement j = judge(t);
            if (j.verdict() == Verdict.LANDSCAPE) landscape = true;
            marked = true;
            table.addClass(j.verdict() == Verdict.CARDS ? HAS_CARDS_CLASS : KEPT_TABLE_CLASS);
            table.after(cardsOf(t));
        }
        return new Result(landscape ? Layout.LANDSCAPE : Layout.PORTRAIT, marked ? doc.body().html() : html);
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
            if (table.parents().stream().noneMatch(p -> p.tagName().equals("table")) && !table.hasClass(HAS_CARDS_CLASS) && !table.hasClass(KEPT_TABLE_CLASS)
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
        Rule rule = ruleOf(columns);
        Verdict verdict = rule != Rule.NONE ? Verdict.CARDS
                : sumMin <= PORTRAIT_CONTENT_PX ? Verdict.PORTRAIT : Verdict.LANDSCAPE;
        return new TableJudgement(columns, sumMin, rule, verdict);
    }

    /** @return the rule that turns a table with these columns into cards, or {@link Rule#NONE} */
    static Rule ruleOf(List<Column> columns) {
        int n = columns.size();
        if (n < 2) return Rule.NONE;
        long veryLong = columns.stream().filter(c -> c.chars() >= VERY_LONG_COLUMN_CHARS).count();
        long long_ = columns.stream().filter(c -> c.chars() >= LONG_COLUMN_CHARS).count();
        boolean allShort = columns.stream().allMatch(c -> c.kind() == ColumnKind.SHORT);
        if (allShort && n >= MANY_SHORT_COLUMNS) return Rule.MANY_SHORT_COLUMNS;
        if (veryLong >= VERY_LONG_COLUMNS_FOR_CARDS) return Rule.TWO_VERY_LONG;
        if (n <= MAX_COLUMNS_FOR_LONG_RULE && long_ >= LONG_COLUMNS_FOR_CARDS) return Rule.THREE_LONG;
        if (columns.stream().mapToDouble(Column::min).sum() > LANDSCAPE_CONTENT_PX) return Rule.WIDER_THAN_LANDSCAPE;
        return Rule.NONE;
    }

    private static Column classify(Table t, int c) {
        double longest = 0, longestTotal = 0;
        int cells = 0, codeCells = 0;
        for (List<Cell> row : t.rows()) {
            Cell cell = c < row.size() ? row.get(c) : null;
            if (cell == null || cell.empty()) continue;
            longest = Math.max(longest, cell.oneLine());
            longestTotal = Math.max(longestTotal, cell.totalWidth());
            cells++;
            if (cell.mostlyCode()) codeCells++;
        }
        if (cells == 0) {                       // no body cell: the header alone decides
            Cell h = c < t.headers().size() ? t.headers().get(c) : null;
            longest = h == null ? 0 : h.oneLine();
            longestTotal = h == null ? 0 : h.totalWidth();
        }
        double chars = longestTotal / BODY_FONT_PX;
        double max = longest + CELL_EXTRA_PX;
        double header = c < t.headers().size() && t.headers().get(c) != null ? t.headers().get(c).oneLine() : 0;
        if (longest <= SHORT_MAX_CHARS * BODY_FONT_PX) {
            // Kept on one line, but never narrower than a header that is itself a short word.
            double min = max;
            return new Column(ColumnKind.SHORT, min, Math.max(max, Math.min(header, SHORT_MAX_CHARS * BODY_FONT_PX) + CELL_EXTRA_PX), chars);
        }
        if (codeCells > cells * IDENTIFIER_CELL_SHARE) {
            return new Column(ColumnKind.IDENTIFIER, Math.min(max, IDENTIFIER_MIN_CHARS * BODY_FONT_PX + CELL_EXTRA_PX), max, chars);
        }
        return new Column(ColumnKind.TEXT, Math.min(max, TEXT_MIN_CHARS * BODY_FONT_PX + CELL_EXTRA_PX), max, chars);
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
