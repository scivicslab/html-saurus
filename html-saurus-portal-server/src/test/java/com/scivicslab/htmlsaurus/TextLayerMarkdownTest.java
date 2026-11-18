package com.scivicslab.htmlsaurus;

import com.scivicslab.htmlsaurus.TextLayerMarkdown.Glyph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The text-layer to Markdown rules, on glyph lists built by hand (no PDF involved). */
class TextLayerMarkdownTest {

    private static final float SIZE = 10f;
    private static final float ADVANCE = 5f;      // width of one character
    private static final float WORD_GAP = 3.3f;   // a word space

    /** Appends {@code text} as glyphs from {@code x}; returns the x just after it. */
    private static float word(List<Glyph> out, String text, String font, float size, float x, float y) {
        for (char c : text.toCharArray()) {
            out.add(new Glyph(String.valueOf(c), font, size, x, x + ADVANCE * size / SIZE, y));
            x += ADVANCE * size / SIZE;
        }
        return x;
    }

    /** One line of words in one font, starting at {@code x}. */
    private static float line(List<Glyph> out, String text, String font, float size, float x, float y) {
        for (String w : text.split(" ")) {
            x = word(out, w, font, size, x, y) + WORD_GAP * size / SIZE;   // a space grows with the type
        }
        return x;
    }

    /** A body line long enough to count towards the body's right edge. */
    private static final String LONG = "aaaa bbbb cccc dddd eeee ffff gggg hhhh iiii jjjj kkkk llll";

    private static List<Glyph> prose(float firstY, int lines) {
        List<Glyph> g = new ArrayList<>();
        for (int i = 0; i < lines; i++) line(g, LONG, "CMR10", SIZE, 72, firstY + 12 * i);
        return g;
    }

    @Test
    void linesOfOneParagraphAreJoinedWithSpaces() {
        List<String> out = TextLayerMarkdown.render(prose(100, 3));
        assertEquals(1, out.size());
        assertEquals(LONG + " " + LONG + " " + LONG, out.get(0));
    }

    @Test
    void aTypewriterRunInsideProseIsCode() {
        List<Glyph> g = new ArrayList<>();
        float x = line(g, "type", "CMR10", SIZE, 72, 100);
        x = line(g, "\\show", "CMTT10", SIZE, x, 100);
        line(g, "to see", "CMR10", SIZE, x, 100);
        assertEquals(List.of("type `\\show` to see"), TextLayerMarkdown.render(g));
    }

    @Test
    void boldAndItalicFontsAreEmphasis() {
        List<Glyph> g = new ArrayList<>();
        float x = line(g, "a", "CMR10", SIZE, 72, 100);
        x = line(g, "bold", "CMBX10", SIZE, x, 100);
        x = line(g, "and", "CMR10", SIZE, x, 100);
        line(g, "slanted", "CMSL10", SIZE, x, 100);
        assertEquals(List.of("a **bold** and *slanted*"), TextLayerMarkdown.render(g));
    }

    @Test
    void linesSetEntirelyInTypewriterAreAFencedBlockWithTheirIndentation() {
        List<Glyph> g = prose(100, 2);
        line(g, "> \\thinspace=macro:", "CMTT10", SIZE, 90, 136);
        line(g, "->\\kern .16667em .", "CMTT10", SIZE, 90, 148);
        line(g, "  indented", "CMTT10", SIZE, 90 + 10.5f, 160);
        List<String> out = TextLayerMarkdown.render(g);
        assertEquals(2, out.size());
        assertTrue(out.get(1).startsWith("```\n> \\thinspace=macro:\n->\\kern .16667em .\n"), out.get(1));
        assertTrue(out.get(1).endsWith("```"));
    }

    @Test
    void markdownCharactersInProseAreEscapedButCodeIsNot() {
        List<Glyph> g = new ArrayList<>();
        float x = line(g, "a_b *c*", "CMR10", SIZE, 72, 100);
        line(g, "x_1", "CMTT10", SIZE, x, 100);
        assertEquals(List.of("a\\_b \\*c\\* `x_1`"), TextLayerMarkdown.render(g));
    }

    @Test
    void smallTypeBeyondTheBodyIsAMarginNoteAndIsDropped() {
        List<Glyph> g = prose(100, 4);
        line(g, "index word", "CMR7", 7f, 455, 112);
        List<String> out = TextLayerMarkdown.render(g);
        assertEquals(1, out.size());
        assertFalse(out.get(0).contains("index"), out.get(0));
    }

    @Test
    void smallTypeAtTheLeftIsKept() {
        List<Glyph> g = prose(100, 2);
        line(g, "a footnote", "CMR9", 8f, 72, 130);
        assertTrue(TextLayerMarkdown.render(g).toString().contains("a footnote"));
    }

    @Test
    void aRunningHeadSetApartFromTheTextIsDropped() {
        List<Glyph> g = prose(130, 4);
        line(g, "10 Chapter 3: Controlling", "CMTI10", SIZE, 72, 82);
        List<String> out = TextLayerMarkdown.render(g);
        assertEquals(1, out.size());
        assertFalse(out.get(0).contains("Chapter"), out.get(0));
    }

    @Test
    void aPageNumberAtTheFootIsDropped() {
        List<Glyph> g = prose(100, 4);
        line(g, "12", "CMR10", SIZE, 300, 220);
        List<String> out = TextLayerMarkdown.render(g);
        assertEquals(1, out.size());
        assertFalse(out.get(0).endsWith("12"), out.get(0));
    }

    @Test
    void aShortFirstLineClosetoTheTextIsKept() {
        List<Glyph> g = new ArrayList<>();
        line(g, "A short opening line", "CMR10", SIZE, 72, 100);
        for (int i = 0; i < 4; i++) line(g, LONG, "CMR10", SIZE, 72, 112 + 12 * i);
        assertTrue(TextLayerMarkdown.render(g).get(0).startsWith("A short opening line"));
    }

    @Test
    void biggerTypeIsAHeadingWhoseLevelFollowsItsSize() {
        List<Glyph> g = prose(160, 3);
        line(g, "Big Title", "CMBX10", 25f, 72, 100);
        line(g, "Section", "CMBX10", 13f, 72, 140);
        List<String> out = TextLayerMarkdown.render(g);
        assertEquals("# **Big Title**", out.get(0));
        assertEquals("### **Section**", out.get(1));
    }

    @Test
    void aWordBrokenAcrossLinesIsJoinedBack() {
        List<Glyph> g = new ArrayList<>();
        line(g, LONG + " con-", "CMR10", SIZE, 72, 100);
        line(g, "trol " + LONG.substring(0, 56), "CMR10", SIZE, 72, 112);
        List<String> out = TextLayerMarkdown.render(g);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("llll control aaaa"), out.get(0));
    }

    @Test
    void aHyphenBeforeACapitalIsKept() {
        List<Glyph> g = new ArrayList<>();
        line(g, LONG + " semi-", "CMR10", SIZE, 72, 100);
        line(g, "Final " + LONG, "CMR10", SIZE, 72, 112);
        assertTrue(TextLayerMarkdown.render(g).get(0).contains("semi- Final"));
    }

    @Test
    void ligaturesBecomeLetters() {
        List<Glyph> g = new ArrayList<>();
        g.add(new Glyph("\uFB01", "CMR10", SIZE, 72, 77, 100));
        g.add(new Glyph("n", "CMR10", SIZE, 77, 82, 100));
        g.add(new Glyph("d", "CMR10", SIZE, 82, 87, 100));
        assertEquals(List.of("find"), TextLayerMarkdown.render(g));
    }

    @Test
    void theDroppedEOfTheTeXLogoIsWrittenAsLowercaseE() {
        List<Glyph> g = new ArrayList<>();
        g.add(new Glyph("T", "CMR10", SIZE, 72, 77, 100));
        g.add(new Glyph("E", "CMR10", SIZE, 77, 82, 102));   // lowered
        g.add(new Glyph("X", "CMR10", SIZE, 82, 87, 100));
        assertEquals(List.of("TeX"), TextLayerMarkdown.render(g));
    }

    @Test
    void anOrdinaryTEXOnTheBaselineStaysAsItIs() {
        List<Glyph> g = new ArrayList<>();
        g.add(new Glyph("T", "CMR10", SIZE, 72, 77, 100));
        g.add(new Glyph("E", "CMR10", SIZE, 77, 82, 100));
        g.add(new Glyph("X", "CMR10", SIZE, 82, 87, 100));
        assertEquals(List.of("TEX"), TextLayerMarkdown.render(g));
    }

    @Test
    void marksInADecorativeFontAreNotText() {
        List<Glyph> g = new ArrayList<>();
        float x = word(g, "x", "manfnt", SIZE, 72, 100) + WORD_GAP;
        line(g, "EXERCISE", "CMBX10", SIZE, x, 100);
        assertEquals(List.of("**EXERCISE**"), TextLayerMarkdown.render(g));
    }

    @Test
    void twoColumnsAreReadOneAfterTheOther() {
        List<Glyph> g = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            line(g, "left" + i + " entry one", "CMR10", SIZE, 72, 100 + 12 * i);
            line(g, "right" + i + " entry two", "CMR10", SIZE, 320, 100 + 12 * i);
        }
        String all = String.join("\n", TextLayerMarkdown.render(g));
        assertTrue(all.indexOf("left29") < all.indexOf("right0"), "the whole left column comes first");
        assertFalse(all.contains("one right"), "a line must not run from one column into the other");
    }

    @Test
    void aPageWithNoGlyphsGivesNothing() {
        assertEquals(List.of(), TextLayerMarkdown.render(List.of()));
    }
}
