package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link PageRenderer#isJapaneseText}: pure character-classification logic. */
class PageRendererTest {

    @Test
    void nullOrEmpty_isNotJapanese() {
        assertFalse(PageRenderer.isJapaneseText(null));
        assertFalse(PageRenderer.isJapaneseText(""));
    }

    @Test
    void plainEnglishProse_isNotJapanese() {
        String html = "<p>This manuscript reports the construction of a large-scale, "
            + "comprehensive knowledge graph database that integrates multiple data sources.</p>";
        assertFalse(PageRenderer.isJapaneseText(html));
    }

    @Test
    void plainJapaneseProse_isJapanese() {
        String html = "<p>本文書は、遺伝子、疾患、薬物などの多種多様なデータソースを統合した"
            + "大規模知識グラフデータベースの構築に関する査読報告書です。</p>";
        assertTrue(PageRenderer.isJapaneseText(html));
    }

    @Test
    void englishBodyUnderJapaneseLocaleDir_isNotJapanese() {
        // Regression case: a page filed under the ja locale directory whose body is English-only
        // must still be detected as English so on-demand translation runs ja→en in the right
        // direction (translating TO Japanese), not en→en.
        String html = "<p>However, the manuscript has the following concerns with respect to "
            + "the requirements for publication in Scientific Data.</p>";
        assertFalse(PageRenderer.isJapaneseText(html));
    }

    @Test
    void fewJapaneseWordsInMostlyEnglishText_isNotJapanese() {
        String html = "<p>The term 猫 (cat) appears once in this otherwise English paragraph "
            + "about software architecture and distributed systems design.</p>";
        assertFalse(PageRenderer.isJapaneseText(html));
    }

    @Test
    void japaneseTextWithEnglishTechnicalTerms_isJapanese() {
        String html = "<p>この設計では ActorRef を使って PromptBuilderActor にメッセージを送信します。"
            + "アクター間の通信は非同期に行われます。</p>";
        assertTrue(PageRenderer.isJapaneseText(html));
    }
}
