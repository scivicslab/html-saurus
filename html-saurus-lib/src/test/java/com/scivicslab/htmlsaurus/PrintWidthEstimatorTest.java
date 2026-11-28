package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.htmlsaurus.PrintWidthEstimator.Layout;
import com.scivicslab.htmlsaurus.PrintWidthEstimator.TableFit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Print orientation of a document, decided from the width its tables need. */
class PrintWidthEstimatorTest {

    private static String row(String tag, String... cells) {
        StringBuilder sb = new StringBuilder("<tr>");
        for (String c : cells) sb.append('<').append(tag).append('>').append(c).append("</").append(tag).append('>');
        return sb.append("</tr>").append('\n').toString();
    }

    /** A table of {@code columns} columns, each cell {@code chars} full-width characters long. */
    private static String table(int columns, int rows, int chars) {
        String cell = "あ".repeat(chars);
        String[] cells = new String[columns];
        java.util.Arrays.fill(cells, cell);
        StringBuilder sb = new StringBuilder("<table>\n").append(row("th", cells));
        for (int r = 0; r < rows; r++) sb.append(row("td", cells));
        return sb.append("</table>").toString();
    }

    @Test
    void noTableMeansPortrait() {
        assertEquals(Layout.PORTRAIT, PrintWidthEstimator.layoutOf("<p>text</p>"));
        assertEquals(Layout.PORTRAIT, PrintWidthEstimator.layoutOf(""));
    }

    @Test
    void aTwoColumnTableIsPortrait_evenWithLongCells() {
        String html = "<table>" + row("th", "項目", "内容") + row("td", "名前", "あ".repeat(300)) + "</table>";
        assertEquals(Layout.PORTRAIT, PrintWidthEstimator.layoutOf(html));
    }

    @Test
    void aNineColumnTableOfLongCellsDoesNotFitPortrait_butFitsLandscape() {
        TableFit fit = PrintWidthEstimator.analyze(table(9, 2, 30)).get(0);
        assertEquals(9, fit.columns());
        assertTrue(!fit.fitsPortrait(PrintWidthEstimator.MIN_COLUMN_FULL_WIDTH_CHARS), "9 columns of 673 px leave under 4 characters each");
        assertTrue(fit.fitsLandscape(PrintWidthEstimator.MIN_COLUMN_FULL_WIDTH_CHARS));
        assertEquals(Layout.LANDSCAPE, PrintWidthEstimator.layoutOf(table(9, 2, 30)));
    }

    @Test
    void aTableOfThirteenColumnsFitsNeither_soTheDocumentStaysPortrait() {
        assertEquals(Layout.PORTRAIT, PrintWidthEstimator.layoutOf(table(26, 2, 30)));
    }

    @Test
    void theLowerLimitDecides() {
        String html = table(9, 2, 30);
        // Landscape gives each of 9 squeezed columns about 1001/9 = 111 px: 4 characters need 89 px, 6 need 121 px.
        assertEquals(Layout.LANDSCAPE, PrintWidthEstimator.layoutOf(html, 4));
        assertEquals(Layout.PORTRAIT, PrintWidthEstimator.layoutOf(html, 6));
        assertEquals(89, PrintWidthEstimator.limitPx(4), 0.001);
    }

    @Test
    void aNarrowColumnThatIsNotSqueezedDoesNotCount() {
        // The first column holds a number: its natural width is far below the limit but nothing squeezes it.
        String html = "<table>" + row("th", "No", "内容", "備考") + row("td", "1", "あ".repeat(60), "い".repeat(60)) + "</table>";
        TableFit fit = PrintWidthEstimator.analyze(html).get(0);
        assertTrue(fit.fitsPortrait(4), "only the long columns are squeezed, and they stay wide");
    }

    @Test
    void identifiersInCodeMayBreakInTheMiddle_soTheyDoNotForceLandscape() {
        // Without breaking, a 40-letter identifier needs 40 * 8.4 px in one column and would push the table past 673 px.
        String code = "<code>" + "a".repeat(40) + "</code>";
        String html = "<table>" + row("th", "A", "B", "C") + row("td", code, code, code) + "</table>";
        TableFit fit = PrintWidthEstimator.analyze(html).get(0);
        assertTrue(fit.sumMin() < PrintWidthEstimator.PORTRAIT_CONTENT_PX, "code breaks after about " + PrintWidthEstimator.CODE_RUN_CHARS + " letters");
    }

    @Test
    void latinWordsOutsideCodeDoNotBreak() {
        String word = "a".repeat(100);
        TableFit fit = PrintWidthEstimator.analyze("<table>" + row("td", word, "x") + "</table>").get(0);
        assertEquals((100 * 8.8 + 25) + (8.8 + 25), fit.sumMin(), 0.5);
    }

    @Test
    void fullWidthCharactersBreakAnywhere() {
        TableFit fit = PrintWidthEstimator.analyze("<table>" + row("td", "あ".repeat(50)) + "</table>").get(0);
        assertEquals(16 + 25, fit.sumMin(), 0.001);
        assertEquals(50 * 16 + 25, fit.sumMax(), 0.001);
    }

    @Test
    void nestedTablesAndSpansDoNotBreakTheEstimate() {
        String html = "<table><tr><td colspan=\"2\">wide</td></tr><tr><td>a<table><tr><td>b</td></tr></table></td><td>c</td></tr></table>";
        List<TableFit> fits = PrintWidthEstimator.analyze(html);
        assertEquals(1, fits.size());
        assertEquals(2, fits.get(0).columns());
    }
}
