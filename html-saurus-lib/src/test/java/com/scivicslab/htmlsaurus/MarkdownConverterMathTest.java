package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that math written with {@code $...$} and {@code $$...$$} reaches the browser unchanged,
 * so that the client-side KaTeX auto-render can typeset it. CommonMark would otherwise treat
 * {@code *} and {@code _} inside a formula as emphasis and {@code \,} as an escaped comma.
 */
@Tag("S1.04")
class MarkdownConverterMathTest {

    private final MarkdownConverter converter = new MarkdownConverter();

    private String html(String markdown) {
        return converter.convertMarkdown(markdown);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("an asterisk in a formula is not emphasis")
    void asteriskInFormula() {
        String out = html("- 動かす量: 単価 $p$。値を変えて、A が選ぶ働く時間 $t^*$ の変化を見る。範囲は 1.25 から 8.75 [円/kg] である。"
                + "$p_{\\max} = 9.375\\,[\\text{円/kg}]$ 以上では、$t^*$ が $T$ で頭打ちになる。\n");
        assertFalse(out.contains("<em>"), out);
        assertEquals(2, count(out, "$t^*$"), out);
        assertTrue(out.contains("$p_{\\max} = 9.375\\,[\\text{円/kg}]$"), out);
        assertTrue(out.contains("$T$"), out);
    }

    @Test
    @DisplayName("a display formula inside a list item is kept as written")
    void displayFormulaInList() {
        String md = "4. 係数 $k$ を決める。\n\n   $$\n   k = \\frac{p \\times a}{t^*}\n   $$\n\n5. 次へ。\n";
        String out = html(md);
        assertFalse(out.contains("<em>"), out);
        assertTrue(out.contains("k = \\frac{p \\times a}{t^*}"), out);
        assertEquals(2, count(out, "$$"), out);
        assertTrue(out.contains("<ol"), out);
    }

    @Test
    @DisplayName("a dollar sign inside code is not math, and emphasis outside code still works")
    void codeIsNotMath() {
        String out = html("`$a_b$ と *c*` と *強調* です。\n");
        assertTrue(out.contains("<code>$a_b$ と *c*</code>"), out);
        assertTrue(out.contains("<em>強調</em>"), out);
    }

    @Test
    @DisplayName("a fenced code block is left untouched")
    void fencedCodeIsNotMath() {
        String out = html("```\n$x_1$ and $y^*$\n```\n");
        assertTrue(out.contains("$x_1$ and $y^*$"), out);
    }

    @Test
    @DisplayName("less-than and ampersand inside a formula are escaped for HTML")
    void htmlSpecialCharactersAreEscaped() {
        String out = html("$a < b \\& c$\n");
        assertTrue(out.contains("$a &lt; b \\&amp; c$"), out);
    }

    @Test
    @DisplayName("prices with dollar signs are not read as math")
    void pricesAreNotMath() {
        String out = html("価格は $5 と $10 です。 *強調*\n");
        assertTrue(out.contains("価格は $5 と $10 です。"), out);
        assertTrue(out.contains("<em>強調</em>"), out);
    }

    @Test
    @DisplayName("math inside a table cell is kept as written")
    void mathInTableCell() {
        String out = html("| 倍率 | 選ぶ時間 $t^*$ [日] |\n|---|---|\n| 1 | $k_0 \\times 2$ |\n");
        assertTrue(out.contains("<table"), out);
        assertTrue(out.contains("$t^*$"), out);
        assertTrue(out.contains("$k_0 \\times 2$"), out);
        assertFalse(out.contains("<em>"), out);
    }

    @Test
    @DisplayName("a heading with math gets an anchor that does not depend on the formula text")
    void headingWithMath() {
        String out = html("## 単価 $p$ を動かした結果\n");
        assertTrue(out.contains("$p$"), out);
        assertFalse(out.contains(""), out);
    }
}
