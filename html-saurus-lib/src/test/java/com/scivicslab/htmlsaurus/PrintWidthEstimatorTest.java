package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.htmlsaurus.PrintWidthEstimator.ColumnKind;
import com.scivicslab.htmlsaurus.PrintWidthEstimator.Layout;
import com.scivicslab.htmlsaurus.PrintWidthEstimator.Result;
import com.scivicslab.htmlsaurus.PrintWidthEstimator.TableJudgement;
import com.scivicslab.htmlsaurus.PrintWidthEstimator.Verdict;
import java.util.Arrays;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/** How a table prints on A4, decided from what its columns hold. */
class PrintWidthEstimatorTest {

    private static String row(String tag, String... cells) {
        StringBuilder sb = new StringBuilder("<tr>");
        for (String c : cells) sb.append('<').append(tag).append('>').append(c).append("</").append(tag).append('>');
        return sb.append("</tr>\n").toString();
    }

    /** A table of {@code columns} text columns, each body cell {@code chars} full-width characters long. */
    private static String textTable(int columns, int rows, int chars) {
        String[] head = new String[columns];
        String[] cells = new String[columns];
        for (int i = 0; i < columns; i++) { head[i] = "列" + i; cells[i] = "あ".repeat(chars); }
        StringBuilder sb = new StringBuilder("<table>\n").append(row("th", head));
        for (int r = 0; r < rows; r++) {
            String[] own = cells.clone();
            own[0] = "行" + r;
            sb.append(row("td", own));
        }
        return sb.append("</table>").toString();
    }

    private static Verdict verdictOf(String html) {
        return PrintWidthEstimator.analyze(html).get(0).verdict();
    }

    @Test
    void noTableMeansPortraitAndTheContentIsReturnedAsItIs() {
        String html = "<p>text</p>";
        Result r = PrintWidthEstimator.decide(html);
        assertEquals(Layout.PORTRAIT, r.layout());
        assertSame(html, r.html());
        assertEquals(Layout.PORTRAIT, PrintWidthEstimator.layoutOf(null));
    }

    @Test
    void aTwoColumnTableIsPortrait_evenWithALongSecondColumn() {
        String html = "<table>" + row("th", "項目", "内容") + row("td", "名前", "あ".repeat(60)) + "</table>";
        assertEquals(Verdict.PORTRAIT, verdictOf(html));
        assertSame(html, PrintWidthEstimator.decide(html).html(), "a portrait table is not rewritten");
    }

    @Test
    void columnsAreClassifiedByTheirContent() {
        String html = "<table>" + row("th", "No", "識別子", "説明")
                + row("td", "1", "<code>ssh_connector_upload_file</code>", "あ".repeat(40))
                + row("td", "12", "<code>read_file</code>", "い".repeat(40)) + "</table>";
        List<PrintWidthEstimator.Column> c = PrintWidthEstimator.analyze(html).get(0).columns();
        assertEquals(ColumnKind.SHORT, c.get(0).kind());
        assertEquals(ColumnKind.IDENTIFIER, c.get(1).kind());
        assertEquals(ColumnKind.TEXT, c.get(2).kind());
        assertEquals(2 * 8.8 + 25, c.get(0).min(), 0.01, "a short column's minimum is its longest cell on one line");
        assertEquals(PrintWidthEstimator.IDENTIFIER_MIN_CHARS * 16 + 25, c.get(1).min(), 0.01);
        assertEquals(PrintWidthEstimator.TEXT_MIN_CHARS * 16 + 25, c.get(2).min(), 0.01);
    }

    @Test
    void aColumnWhoseLongestCellIsAtMostEightFullWidthCharactersIsShort() {
        String html = "<table>" + row("th", "区分") + row("td", "あ".repeat(8)) + row("td", "い") + "</table>";
        assertEquals(ColumnKind.SHORT, PrintWidthEstimator.analyze(html).get(0).columns().get(0).kind());
        String longer = "<table>" + row("th", "区分") + row("td", "あ".repeat(9)) + "</table>";
        assertEquals(ColumnKind.TEXT, PrintWidthEstimator.analyze(longer).get(0).columns().get(0).kind());
    }

    @Test
    void aTableWhoseMinimumsFitPortraitIsPortrait_oneThatFitsOnlyLandscapeIsLandscape() {
        // A text column needs 185 px and the short first column 50 px: 3 columns need 420 px, 5 need 790 px.
        assertEquals(Verdict.PORTRAIT, verdictOf(textTable(3, 3, 12)));
        assertEquals(Verdict.LANDSCAPE, verdictOf(textTable(5, 3, 30)));
        assertEquals(Layout.LANDSCAPE, PrintWidthEstimator.layoutOf(textTable(5, 3, 30)));
    }

    @Test
    void aTableThatFitsNeitherIsReflowed_andDoesNotMakeTheDocumentLandscape() {
        String html = textTable(7, 3, 30);       // 50 + 6 * 185 = 1160 px > 1001 px
        assertEquals(Verdict.REFLOW, verdictOf(html));
        Result r = PrintWidthEstimator.decide(html);
        assertEquals(Layout.PORTRAIT, r.layout());
        var doc = Jsoup.parseBodyFragment(r.html());
        assertEquals(1, doc.select("table.has-cards").size(), "the original table stays, marked");
        assertEquals(3, doc.select(".print-cards table.print-card").size(), "one small table per row");
        assertEquals("行0", doc.selectFirst(".print-cards caption").text(), "headed by the first column's value");
        assertEquals(6, doc.select(".print-cards table").first().select("tr").size(), "a row per other column");
        assertEquals("列1", doc.selectFirst(".print-cards th").text());
    }

    @Test
    void aTableThatFitsButWrapsIntoManyTallRowsIsReflowed() {
        // 3 text columns fit portrait, but 200-character cells wrap into far more than 6 lines each.
        TableJudgement j = PrintWidthEstimator.analyze(textTable(3, 4, 200)).get(0);
        assertTrue(j.sumMin() <= PrintWidthEstimator.PORTRAIT_CONTENT_PX);
        assertTrue(j.tallShare() > PrintWidthEstimator.TALL_ROW_SHARE);
        assertEquals(Verdict.REFLOW, j.verdict());
    }

    @Test
    void aFewTallRowsAreKept() {
        // One tall row of ten is under the 30% limit.
        StringBuilder sb = new StringBuilder(textTable(3, 0, 10).replace("</table>", ""));
        sb.append(row("td", "行0", "あ".repeat(300), "あ".repeat(300)));
        for (int i = 1; i < 10; i++) sb.append(row("td", "行" + i, "あ".repeat(10), "あ".repeat(10)));
        sb.append("</table>");
        assertEquals(Verdict.PORTRAIT, verdictOf(sb.toString()));
    }

    @Test
    void anyLandscapeTableMakesTheDocumentLandscape() {
        String html = textTable(3, 2, 12) + textTable(5, 3, 30) + textTable(7, 3, 30);
        Result r = PrintWidthEstimator.decide(html);
        assertEquals(Layout.LANDSCAPE, r.layout());
        assertTrue(r.html().contains("print-cards"), "the table that fits neither is still reflowed");
    }

    @Test
    void thirteenShortColumnsFitLandscapeOnTheirWidth() {
        String[] head = new String[13];
        String[] cells = new String[13];
        Arrays.fill(head, "高");
        Arrays.fill(cells, "中");
        String html = "<table>" + row("th", head) + row("td", cells) + "</table>";
        TableJudgement j = PrintWidthEstimator.analyze(html).get(0);
        assertEquals(13 * 41, j.sumMin(), 0.01);
        assertEquals(Verdict.PORTRAIT, j.verdict(), "13 * 41 px = 533 px");
    }

    @Test
    void nestedTablesAndSpansDoNotBreakTheEstimate() {
        String html = "<table><tr><td colspan=\"2\">wide</td></tr><tr><td>a<table><tr><td>b</td></tr></table></td><td>c</td></tr></table>";
        List<TableJudgement> js = PrintWidthEstimator.analyze(html);
        assertEquals(1, js.size());
        assertFalse(js.get(0).columns().isEmpty());
    }
}
