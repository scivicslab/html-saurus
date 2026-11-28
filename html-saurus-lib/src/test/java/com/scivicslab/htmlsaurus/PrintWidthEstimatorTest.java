package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.htmlsaurus.PrintWidthEstimator.ColumnKind;
import com.scivicslab.htmlsaurus.PrintWidthEstimator.Layout;
import com.scivicslab.htmlsaurus.PrintWidthEstimator.Result;
import com.scivicslab.htmlsaurus.PrintWidthEstimator.Rule;
import com.scivicslab.htmlsaurus.PrintWidthEstimator.TableJudgement;
import com.scivicslab.htmlsaurus.PrintWidthEstimator.Verdict;
import java.util.Arrays;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/** Whether a table is shown as a table or as cards, and how a table that stays prints on A4. */
class PrintWidthEstimatorTest {

    private static String row(String tag, String... cells) {
        StringBuilder sb = new StringBuilder("<tr>");
        for (String c : cells) sb.append('<').append(tag).append('>').append(c).append("</").append(tag).append('>');
        return sb.append("</tr>\n").toString();
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

    /** A table of {@code columns} columns whose first column is short and whose others hold {@code chars[i]} characters. */
    private static String table(int... chars) {
        String[] head = new String[chars.length];
        String[] cells = new String[chars.length];
        for (int i = 0; i < chars.length; i++) { head[i] = "列" + i; cells[i] = "あ".repeat(chars[i]); }
        return "<table>\n" + row("th", head) + row("td", cells) + "</table>";
    }

    private static int[] fill(int columns, int value) {
        int[] a = new int[columns];
        Arrays.fill(a, value);
        return a;
    }

    private static Rule ruleOf(String html) {
        return PrintWidthEstimator.analyze(html).get(0).rule();
    }

    @Test
    void aSingleLongColumnIsNeverCards_aTwoColumnTableStaysATable() {
        String html = "<table>" + row("th", "項目", "内容") + row("td", "名前", "あ".repeat(300)) + "</table>";
        assertEquals(Verdict.PORTRAIT, verdictOf(html));
        var doc = Jsoup.parseBodyFragment(PrintWidthEstimator.decide(html).html());
        assertEquals(1, doc.select("table.rule-table").size(), "marked as kept");
        assertEquals(0, doc.select("table.rule-cards").size());
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
        assertEquals(40, c.get(2).chars(), 0.01);
    }

    @Test
    void aColumnWhoseLongestCellIsAtMostEightFullWidthCharactersIsShort() {
        String html = "<table>" + row("th", "区分") + row("td", "あ".repeat(8)) + row("td", "い") + "</table>";
        assertEquals(ColumnKind.SHORT, PrintWidthEstimator.analyze(html).get(0).columns().get(0).kind());
        String longer = "<table>" + row("th", "区分") + row("td", "あ".repeat(9)) + "</table>";
        assertEquals(ColumnKind.TEXT, PrintWidthEstimator.analyze(longer).get(0).columns().get(0).kind());
    }

    @Test
    void theLengthOfAColumnIsItsLongestCell_halfWidthCountsAsAboutHalf() {
        String html = "<table>" + row("th", "A", "B") + row("td", "x", "a".repeat(46)) + row("td", "y", "b".repeat(10)) + "</table>";
        assertEquals(46 * 8.8 / 16, PrintWidthEstimator.analyze(html).get(0).columns().get(1).chars(), 0.01);
        // 46 half-width characters are 25.3 full-width ones, 45 are 24.75: the border of rule 3.
        assertTrue(46 * 8.8 / 16 >= PrintWidthEstimator.LONG_COLUMN_CHARS);
        assertTrue(45 * 8.8 / 16 < PrintWidthEstimator.LONG_COLUMN_CHARS);
    }

    @Test
    void rule1_thirteenOrMoreAllShortColumnsAreCards_twelveAreNot() {
        assertEquals(Rule.MANY_SHORT_COLUMNS, ruleOf(table(fill(13, 1))));
        assertEquals(Verdict.CARDS, verdictOf(table(fill(13, 1))));
        assertEquals(Rule.NONE, ruleOf(table(fill(12, 1))));
        assertEquals(Verdict.PORTRAIT, verdictOf(table(fill(12, 1))), "12 * 41 px fits portrait");
        int[] oneNotShort = fill(13, 1);
        oneNotShort[5] = 9;
        assertEquals(Rule.NONE, ruleOf(table(oneNotShort)), "one column that is not short is not rule 1");
    }

    @Test
    void rule2_twoVeryLongColumnsAreCardsWhateverTheNumberOfColumns() {
        assertEquals(Rule.TWO_VERY_LONG, ruleOf(table(1, 100, 100)));
        assertEquals(Rule.TWO_VERY_LONG, ruleOf(table(100, 100)));
        int[] many = fill(14, 1);
        many[2] = 100;
        many[9] = 150;
        assertEquals(Rule.TWO_VERY_LONG, ruleOf(table(many)), "more than 12 columns: rule 2 still applies");
        assertEquals(Rule.NONE, ruleOf(table(1, 100, 99)), "99 is not very long");
        assertEquals(Rule.NONE, ruleOf(table(1, 300)), "one very long column is not enough");
    }

    @Test
    void rule3_threeLongColumnsInAtMostTwelveColumnsAreCards() {
        assertEquals(Rule.THREE_LONG, ruleOf(table(1, 25, 25, 25)));
        int[] twelve = fill(12, 1);
        twelve[1] = 25; twelve[2] = 30; twelve[3] = 40;
        assertEquals(Rule.THREE_LONG, ruleOf(table(twelve)));
        int[] thirteen = fill(13, 1);
        thirteen[1] = 25; thirteen[2] = 30; thirteen[3] = 40;
        assertEquals(Rule.NONE, ruleOf(table(thirteen)), "13 columns: rule 3 does not apply");
        assertEquals(Rule.NONE, ruleOf(table(1, 25, 25, 24)), "24 is not long");
        assertEquals(Rule.NONE, ruleOf(table(1, 25, 25)), "two long columns are not enough");
    }

    @Test
    void aTableThatMatchesNoRuleStaysATable_portraitWhenItsMinimumsFit_elseLandscape() {
        // A text column needs 185 px and the short first column 50 px: 3 columns need 420 px, 5 need 790 px.
        assertEquals(Verdict.PORTRAIT, verdictOf(table(1, 12, 12)));
        assertEquals(Verdict.LANDSCAPE, verdictOf(table(1, 20, 20, 20, 20)));
        assertEquals(Layout.LANDSCAPE, PrintWidthEstimator.layoutOf(table(1, 20, 20, 20, 20)));
    }

    private static List<PrintWidthEstimator.Column> twoColumnsWithMinimums(double a, double b) {
        return List.of(new PrintWidthEstimator.Column(ColumnKind.TEXT, a, a, 1), new PrintWidthEstimator.Column(ColumnKind.TEXT, b, b, 1));
    }

    @Test
    void rule4_aTableWhoseMinimumsExceedTheLandscapeContentIsCards_exactlyThatMuchIsNot() {
        assertEquals(1001, PrintWidthEstimator.LANDSCAPE_CONTENT_PX, 0);
        assertEquals(Rule.NONE, PrintWidthEstimator.ruleOf(twoColumnsWithMinimums(500.5, 500.5)), "1001 px exactly fits");
        assertEquals(Rule.NONE, PrintWidthEstimator.ruleOf(twoColumnsWithMinimums(500.5, 500.49)), "below 1001 px");
        assertEquals(Rule.WIDER_THAN_LANDSCAPE, PrintWidthEstimator.ruleOf(twoColumnsWithMinimums(500.5, 500.51)), "over 1001 px");
        assertEquals(Rule.NONE, PrintWidthEstimator.ruleOf(twoColumnsWithMinimums(673, 100)), "fits landscape, not portrait");
    }

    @Test
    void rule4_aRealTableOfTwelveTextColumnsIsCards_andDoesNotMakeTheDocumentLandscape() {
        // 12 text columns need 2200 px, more than the landscape content, and none of rules 1 to 3 applies (20 < 25).
        String html = table(fill(12, 20));
        TableJudgement wide = PrintWidthEstimator.analyze(html).get(0);
        assertTrue(wide.sumMin() > PrintWidthEstimator.LANDSCAPE_CONTENT_PX);
        assertEquals(Rule.WIDER_THAN_LANDSCAPE, wide.rule());
        assertEquals(Verdict.CARDS, wide.verdict());
        assertEquals(Layout.PORTRAIT, PrintWidthEstimator.layoutOf(html));
    }

    @Test
    void rule4_comesAfterRules1To3_soTheEarlierRuleIsReported() {
        assertEquals(Rule.TWO_VERY_LONG, ruleOf(table(fill(12, 100))));
    }

    @Test
    void everyTableOfTwoOrMoreColumnsGetsCards_markedByTheVerdict_andACardTableIsNotLandscape() {
        String html = table(1, 12, 12) + table(1, 100, 100) + "<table>" + row("th", "x") + row("td", "y") + "</table>";
        Result r = PrintWidthEstimator.decide(html);
        assertEquals(Layout.PORTRAIT, r.layout(), "a table that is cards does not make the document landscape");
        var doc = Jsoup.parseBodyFragment(r.html());
        assertEquals(1, doc.select("table.rule-table").size());
        assertEquals(1, doc.select("table.rule-cards").size());
        assertEquals(2, doc.select(".table-cards").size(), "one block per table of two or more columns");
        assertEquals(1, doc.select("table.rule-table + .table-cards").size(), "the block follows its table");
        assertEquals(1, doc.select("table.rule-cards + .table-cards").size());
    }

    @Test
    void theCardsHaveOneSmallTablePerRowWithAHeadingForEveryColumnTheFirstIncluded() {
        String html = "<table>" + row("th", "ID", "名前", "説明")
                + row("td", "A1", "あ", "説<code>明</code>1") + row("td", "A2", "い", "説明2") + "</table>";
        var doc = Jsoup.parseBodyFragment(PrintWidthEstimator.decide(html).html());
        assertEquals(2, doc.select(".table-cards table.print-card").size());
        assertEquals(0, doc.select(".table-cards caption").size(), "the first column is not set apart as a caption");
        var first = doc.select(".table-cards table").first();
        assertEquals(3, first.select("tr").size(), "a row per column, the first included");
        assertEquals("ID", first.select("th").get(0).text(), "the first column keeps its heading");
        assertEquals("A1", first.select("td").get(0).text());
        assertEquals("名前", first.select("th").get(1).text());
        assertEquals("ID", doc.select(".table-cards table").get(1).select("th").get(0).text());
        assertEquals("A2", doc.select(".table-cards table").get(1).select("td").get(0).text());
        assertEquals(1, doc.select(".table-cards table").first().select("td code").size(), "the cell's markup is kept");
    }

    @Test
    void anyLandscapeTableMakesTheDocumentLandscape() {
        String html = table(1, 12, 12) + table(1, 20, 20, 20, 20) + table(1, 100, 100);
        assertEquals(Layout.LANDSCAPE, PrintWidthEstimator.decide(html).layout());
    }

    @Test
    void decidingTwiceDoesNotAddCardsToTheCards() {
        String once = PrintWidthEstimator.decide(table(1, 100, 100)).html();
        var doc = Jsoup.parseBodyFragment(PrintWidthEstimator.decide(once).html());
        assertEquals(1, doc.select(".table-cards").size());
    }

    @Test
    void nestedTablesAndSpansDoNotBreakTheEstimate() {
        String html = "<table><tr><td colspan=\"2\">wide</td></tr><tr><td>a<table><tr><td>b</td></tr></table></td><td>c</td></tr></table>";
        List<TableJudgement> js = PrintWidthEstimator.analyze(html);
        assertEquals(1, js.size());
        assertFalse(js.get(0).columns().isEmpty());
    }
}
