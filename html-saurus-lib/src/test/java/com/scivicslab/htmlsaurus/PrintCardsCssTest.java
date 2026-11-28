package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Pins the rules that decide when a screen shows the reflowed cards instead of the wide table. */
class PrintCardsCssTest {

    private static String css() throws Exception {
        try (var in = PageRenderer.class.getResourceAsStream("page.css")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aNarrowMainShowsTheCardsAndHidesTheTable() throws Exception {
        String css = css();
        int q = css.indexOf("@container (max-width: 1000px)");
        assertTrue(q > 0, "the narrow-screen query must exist");
        int end = css.indexOf("\n}", q);
        String block = css.substring(q, end);
        assertTrue(block.contains(".print-cards { display: block; }"));
        assertTrue(block.contains("main table.has-cards { display: none !important; }"));
    }

    @Test
    void aWideScreenKeepsTheTableAndHidesTheCards() throws Exception {
        String css = css();
        assertTrue(css.contains("main { container-type: inline-size; }"));
        int q = css.indexOf("@container (max-width: 1000px)");
        assertTrue(css.substring(0, q).contains(".print-cards { display: none; }"));
    }
}
