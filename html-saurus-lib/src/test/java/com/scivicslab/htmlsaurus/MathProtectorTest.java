package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies which {@code $...$} spans {@link MathProtector} hides from the Markdown parser, and
 * that a very long inline formula is handled without recursing once per character.
 */
@Tag("MarkdownConverter_260510_oo01")
@DisplayName("MathProtector — inline math detection")
class MathProtectorTest {

    /** Text that contains no formula comes back from protect() unchanged. */
    private static void assertNoMath(String text) {
        assertEquals(text, new MathProtector().protect(text));
    }

    /** Text whose every dollar sign belongs to a formula has no dollar sign left after protect(),
     *  and restore() gives the original text back. */
    private static void assertAllMath(String text) {
        MathProtector protector = new MathProtector();
        String hidden = protector.protect(text);
        assertNotEquals(text, hidden);
        assertFalse(hidden.contains("$"), hidden);
        assertEquals(text, protector.restore(hidden));
    }

    @Test
    @DisplayName("prices such as $5 and $10 are not math")
    void protect_prices_leftAsText() {
        assertNoMath("It costs $5 and $10.");
    }

    @Test
    @DisplayName("an escaped dollar does not open a formula")
    void protect_escapedOpeningDollar_leftAsText() {
        assertNoMath("a \\$x$ b");
    }

    @Test
    @DisplayName("an escaped dollar inside a formula does not close it")
    void protect_escapedDollarInside_oneFormula() {
        assertAllMath("$a\\$b$");
    }

    @Test
    @DisplayName("a dollar followed by white space does not open a formula")
    void protect_spaceAfterOpeningDollar_leftAsText() {
        assertNoMath("$ a$");
    }

    @Test
    @DisplayName("a dollar followed by a digit does not close a formula")
    void protect_digitAfterClosingDollar_leftAsText() {
        assertNoMath("$a$5");
    }

    @Test
    @DisplayName("two formulas on one line are both hidden")
    void protect_twoFormulas_bothHidden() {
        assertAllMath("$x_1$ and $y^*$");
    }

    @Test
    @DisplayName("a 480,000-character inline formula is hidden on a 256 KiB thread stack")
    void protect_veryLongFormula_noStackOverflow() throws InterruptedException {
        // Shaped like the OCR output of Principia Mathematica that stopped a whole site build:
        // one line, one pair of dollars, thousands of backslash commands in between.
        String line = "\\*\\*50\\*1.  $= " + "\\cdot \\mathsf{gray} \\cdot xRy ".repeat(16_000) + "z$";
        String[] hidden = new String[1];
        Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(null, () -> {
            try {
                MathProtector protector = new MathProtector();
                String protectedText = protector.protect(line);
                hidden[0] = protectedText;
                assertEquals(line, protector.restore(protectedText));
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "small-stack", 256 * 1024);
        worker.start();
        worker.join();

        assertNull(failure[0], () -> "protect() failed: " + failure[0]);
        assertFalse(hidden[0].contains("$"), "the formula was not hidden");
    }
}
