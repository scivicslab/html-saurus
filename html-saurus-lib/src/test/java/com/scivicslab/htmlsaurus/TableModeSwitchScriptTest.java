package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The screen-only table switch is written before every table, not once at the top of the document. */
class TableModeSwitchScriptTest {

    private static String script() {
        return PageScripts.render(BasePath.none());
    }

    @Test
    void aSwitchIsInsertedBeforeEachRuleMarkedTable() {
        String js = script();
        assertTrue(js.contains("main.querySelectorAll('table.rule-cards, table.rule-table')"), "every marked table is visited");
        assertTrue(js.contains("tables.forEach(function(table)"), "one switch per table");
        assertTrue(js.contains("table.insertAdjacentElement('beforebegin', bar)"), "the switch is placed before its table");
    }

    @Test
    void theSwitchIsNoLongerPlacedAtTheTopOfTheDocument() {
        String js = script();
        assertFalse(js.contains("insertAdjacentElement('afterend', bar)"), "no placement after the copy bar or the heading");
        assertFalse(js.contains("main.insertBefore(bar"), "no placement at the start of main");
    }

    @Test
    void theChoiceIsOneStateForTheWholeDocumentSoEverySwitchShowsIt() {
        String js = script();
        assertTrue(js.contains("main.querySelectorAll('.table-mode button')"), "every switch's buttons are updated together");
        assertTrue(js.contains("'hs-table-mode:' + location.pathname"), "one stored choice per document");
    }
}
