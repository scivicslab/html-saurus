package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import com.scivicslab.htmlsaurus.MergedOcrClient.MarkerBlock;
import com.scivicslab.htmlsaurus.MergedOcrClient.YomiTokuBlock;
import com.scivicslab.htmlsaurus.MergedOcrClient.YomiTokuBlocks;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link MergedOcrClient}'s parsing and position-based merging: pure logic, no
 *  HTTP. The coordinate fixtures are the real measured values recorded in
 *  {@code MixedJapaneseMathOcr_260907_oo01} (page 150 of the scanned physics book both engines
 *  were compared on). */
class MergedOcrClientTest {

    // ---- merge ----

    /** The real measured page-150 case ({@code MixedJapaneseMathOcr_260907_oo01}, bboxes
     *  re-verified live against Marker): the first Equation rectangle covers TWO YomiToku
     *  paragraphs — the formula and the short prose line Marker lumped into its Equation block —
     *  and both are replaced at the first one's position by the equation's Markdown, which itself
     *  carries that prose as trailing text, so nothing is lost. The second Equation covers the
     *  page's two garbled formula paragraphs. Marker bbox values are PDF points; YomiToku boxes
     *  are pixels at 400 DPI. */
    @Test
    void merge_replacesCoveredParagraphs_evenWhenOneEquationCoversTwo() {
        YomiTokuBlocks yomi = new YomiTokuBlocks(List.of(
                new YomiTokuBlock("第11章 連成系と標準座標", 878, 272, 1361, 323, "page_header"),
                new YomiTokuBlock("129", 1873, 273, 1936, 315, "page_header"),
                new YomiTokuBlock("となる. 要するに,", 255, 366, 1983, 871, null),
                new YomiTokuBlock("x1=x'X+a\"Y=(garbled)", 504, 889, 1711, 958, null),
                new YomiTokuBlock("などとなり、", 259, 982, 1628, 1049, null),
                new YomiTokuBlock("ポテンシャルが座標の", 261, 1070, 1999, 2278, null),
                new YomiTokuBlock("標準座標が特に有用に", 274, 2302, 2003, 2713, null),
                new YomiTokuBlock("Fx=F axi R =a'F1+B'F2,(garbled)", 723, 2723, 1439, 2837, null),
                new YomiTokuBlock("Fr=F ax +F ax(garbled)", 723, 2856, 1462, 2965, null)
        ), 400);
        List<MarkerBlock> equations = List.of(
                new MarkerBlock(47.6, 162.1, 309.2, 189.6,
                        "$$x_1 = \\alpha' X + \\alpha'' Y$$ などとなり、これらの結果はすでに求めたものと一致している."),
                new MarkerBlock(126.7, 491.4, 273.1, 535.6, "$$F_X = \\alpha' F_1$$\n$$F_Y = \\alpha'' F_2$$")
        );

        List<String> merged = MergedOcrClient.merge(yomi, equations);

        assertEquals(List.of(
                "第11章 連成系と標準座標",
                "129",
                "となる. 要するに,",
                "$$x_1 = \\alpha' X + \\alpha'' Y$$ などとなり、これらの結果はすでに求めたものと一致している.",
                "ポテンシャルが座標の",
                "標準座標が特に有用に",
                "$$F_X = \\alpha' F_1$$\n$$F_Y = \\alpha'' F_2$$"
        ), merged);
    }

    @Test
    void merge_withoutEquations_keepsEveryParagraph() {
        YomiTokuBlocks yomi = new YomiTokuBlocks(List.of(
                new YomiTokuBlock("first", 0, 0, 100, 50, null),
                new YomiTokuBlock("second", 0, 60, 100, 110, null)
        ), 400);

        assertEquals(List.of("first", "second"), MergedOcrClient.merge(yomi, List.of()));
    }

    /** An equation YomiToku read nothing under (no covered paragraph) still appears, inserted
     *  before the first paragraph that starts below it. */
    @Test
    void merge_equationCoveringNoParagraph_isInsertedByVerticalPosition() {
        YomiTokuBlocks yomi = new YomiTokuBlocks(List.of(
                new YomiTokuBlock("above", 0, 0, 1000, 500, null),
                new YomiTokuBlock("below", 0, 1200, 1000, 1700, null)
        ), 400);
        // 700..900 px = 126..162 pt: between the two paragraphs, covering neither.
        List<MarkerBlock> equations = List.of(
                new MarkerBlock(18, 126, 180, 162, "$$E = mc^2$$"));

        assertEquals(List.of("above", "$$E = mc^2$$", "below"),
                MergedOcrClient.merge(yomi, equations));
    }

    @Test
    void merge_equationBelowEveryParagraph_isAppended() {
        YomiTokuBlocks yomi = new YomiTokuBlocks(List.of(
                new YomiTokuBlock("only", 0, 0, 1000, 500, null)
        ), 400);
        List<MarkerBlock> equations = List.of(
                new MarkerBlock(18, 200, 180, 250, "$$E = mc^2$$"));

        assertEquals(List.of("only", "$$E = mc^2$$"), MergedOcrClient.merge(yomi, equations));
    }

    /** A paragraph only partially overlapped (below the coverage threshold) stays as text. */
    @Test
    void merge_slightOverlap_doesNotReplaceTheParagraph() {
        YomiTokuBlocks yomi = new YomiTokuBlocks(List.of(
                new YomiTokuBlock("prose", 0, 0, 1000, 1000, null)
        ), 400);
        // 0..180pt x 0..36pt = 0..1000 x 0..200 px: covers 20% of the paragraph — under 50%.
        List<MarkerBlock> equations = List.of(
                new MarkerBlock(0, 0, 180, 36, "$$x$$"));

        List<String> merged = MergedOcrClient.merge(yomi, equations);

        assertTrue(merged.contains("prose"));
    }

    // ---- parseYomiTokuBlocks ----

    @Test
    void parseYomiTokuBlocks_readsBlocksAndDpi() throws IOException {
        String body = """
                {"markdown": "text", "dpi": 400, "device": "cuda",
                 "blocks": [
                   {"contents": "第11章", "box": [878, 272, 1361, 323], "role": "page_header",
                    "order": 0, "direction": "horizontal"},
                   {"contents": "本文です", "box": [255, 366, 1983, 871], "role": null,
                    "order": 1, "direction": "horizontal"}
                 ]}""";

        YomiTokuBlocks parsed = MergedOcrClient.parseYomiTokuBlocks(body);

        assertEquals(400.0, parsed.dpi());
        assertEquals(2, parsed.blocks().size());
        assertEquals("第11章", parsed.blocks().get(0).contents());
        assertEquals("page_header", parsed.blocks().get(0).role());
        assertEquals(878.0, parsed.blocks().get(0).x1());
        assertEquals(871.0, parsed.blocks().get(1).y2());
        assertNull(parsed.blocks().get(1).role());
    }

    /** A pre-blocks server deployment must fail the page loudly, not silently degrade. */
    @Test
    void parseYomiTokuBlocks_withoutBlocksField_throws() {
        String oldServerBody = "{\"markdown\": \"text\", \"device\": \"cuda\"}";

        IOException e = assertThrows(IOException.class,
                () -> MergedOcrClient.parseYomiTokuBlocks(oldServerBody));
        assertTrue(e.getMessage().contains("too old"));
    }

    // ---- parseMarkerMathBlocks ----

    /** The exact response shape Marker's /marker/upload returns for output_format=json: output is
     *  a JSON *string* holding a Document whose Page children carry block_type/bbox/html. */
    @Test
    void parseMarkerMathBlocks_readsEquationBlocksFromTheDocumentTree() {
        String inner = """
                {"block_type": "Document", "children": [
                  {"block_type": "Page", "bbox": [0.0, 0.0, 409.0, 593.0], "children": [
                    {"block_type": "Text", "bbox": [43.9, 172.5, 355.4, 295.7],
                     "html": "<p block-type=\\"Text\\">つまりそれが</p>"},
                    {"block_type": "Equation", "bbox": [107.4, 333.5, 301.0, 391.7],
                     "html": "<p block-type=\\"Equation\\"><math display=\\"block\\">F \\\\times G = 0</math></p>"}
                  ]}
                ]}""";
        String body = "{\"format\": \"json\", \"success\": true, \"output\": "
                + toJsonString(inner) + "}";

        List<MarkerBlock> equations = MergedOcrClient.parseMarkerMathBlocks(body);

        assertEquals(1, equations.size());
        assertEquals("$$F \\times G = 0$$", equations.get(0).markdown());
        assertEquals(107.4, equations.get(0).x1());
        assertEquals(391.7, equations.get(0).y2());
    }

    @Test
    void parseMarkerMathBlocks_withoutOutput_returnsEmpty() {
        assertEquals(List.of(), MergedOcrClient.parseMarkerMathBlocks("{\"success\": false}"));
    }

    /** With force_ocr, a formula inside a sentence arrives as an inline math element in a Text
     *  block — that block is taken from Marker too, converted with $...$ in place. A Text block
     *  without math is not collected. */
    @Test
    void parseMarkerMathBlocks_collectsTextBlocksWithInlineMath() {
        String inner = """
                {"block_type": "Document", "children": [
                  {"block_type": "Page", "bbox": [0.0, 0.0, 409.0, 593.0], "children": [
                    {"block_type": "Text", "bbox": [40.0, 100.0, 350.0, 150.0],
                     "html": "<p block-type=\\"Text\\">純粋な散文の段落。</p>"},
                    {"block_type": "Text", "bbox": [40.0, 160.0, 350.0, 210.0],
                     "html": "<p block-type=\\"Text\\">この級数が函数 <math display=\\"inline\\">e^{-(R/2L)t}</math> を表わす。</p>"}
                  ]}
                ]}""";
        String body = "{\"format\": \"json\", \"success\": true, \"output\": "
                + toJsonString(inner) + "}";

        List<MarkerBlock> blocks = MergedOcrClient.parseMarkerMathBlocks(body);

        assertEquals(1, blocks.size());
        assertEquals("この級数が函数 $e^{-(R/2L)t}$ を表わす。", blocks.get(0).markdown());
        assertEquals(160.0, blocks.get(0).y1());
    }

    /** The repetition failure OCR models fall into on an unreadable line (the real measured
     *  fragment) must keep the block out of the merge, so YomiToku's reading of that region
     *  survives; ordinary prose and repeated math operands must pass. */
    @Test
    void hasDegenerateRepetition_matchesTheObservedFailure_notNormalText() {
        assertTrue(MergedOcrClient.hasDegenerateRepetition(
                "平衡点に $1 \\, \\mathrm{m}$ で で で で で で で で で で で で で が で が で か"));
        assertFalse(MergedOcrClient.hasDegenerateRepetition(
                "この級数が函数 $e^{-(R/2L)t}(A\\sin\\omega t+B\\cos\\omega t)$ を表わしていることを示せ。"));
        assertFalse(MergedOcrClient.hasDegenerateRepetition(
                "$$x_1 + x_2 + x_3 + x_4$$"));
    }

    /** A ListItem inside a ListGroup is reached by descending through the group; a collected
     *  item's own children are NOT descended into — its html already carries the nested item's
     *  text (the real page-51 shape), and collecting the child too would duplicate it. */
    @Test
    void parseMarkerMathBlocks_descendsIntoListGroups_butNotIntoCollectedItems() {
        String inner = """
                {"block_type": "Document", "children": [
                  {"block_type": "Page", "bbox": [0.0, 0.0, 409.0, 593.0], "children": [
                    {"block_type": "ListGroup", "bbox": [45.0, 200.0, 359.0, 534.0],
                     "html": "<p block-type='ListGroup'><content-ref src='x'></content-ref></p>",
                     "children": [
                      {"block_type": "ListItem", "bbox": [46.0, 278.0, 357.0, 341.0],
                       "html": "<li>3. 函数 <math display=\\"inline\\">\\\\omega^2</math> を示せ. <ul><li>4. 入れ子の項</li></ul></li>",
                       "children": [
                        {"block_type": "ListItem", "bbox": [60.0, 320.0, 357.0, 341.0],
                         "html": "<li>4. 入れ子の項 <math display=\\"inline\\">90</math></li>"}
                       ]}
                     ]}
                  ]}
                ]}""";
        String body = "{\"format\": \"json\", \"success\": true, \"output\": "
                + toJsonString(inner) + "}";

        List<MarkerBlock> blocks = MergedOcrClient.parseMarkerMathBlocks(body);

        assertEquals(1, blocks.size());
        assertEquals("3. 函数 $\\omega^2$ を示せ. 4. 入れ子の項", blocks.get(0).markdown());
    }

    // ---- equationHtmlToMarkdown ----

    /** Several display-math elements in one Equation block, as Marker actually returns them
     *  (page 80's four-line expansion of the vector product) — one $$ block per line. */
    @Test
    void equationHtmlToMarkdown_severalMathElements_eachBecomesADisplayBlock() {
        String html = "<p block-type=\"Equation\">"
                + "<math display=\"block\">F \\times G = a</math>\n"
                + "<math display=\"block\">= b + c</math></p>";

        assertEquals("$$F \\times G = a$$\n$$= b + c$$",
                MergedOcrClient.equationHtmlToMarkdown(html));
    }

    /** An equation number sits outside the math element as plain text and must survive. */
    @Test
    void equationHtmlToMarkdown_keepsTrailingEquationNumber() {
        String html = "<p block-type=\"Equation\">"
                + "<math display=\"block\">F \\times G = x. </math> (2)</p>";

        assertEquals("$$F \\times G = x.$$ (2)", MergedOcrClient.equationHtmlToMarkdown(html));
    }

    @Test
    void equationHtmlToMarkdown_inlineMathUsesSingleDollars() {
        String html = "<p><math display=\"inline\">x</math></p>";

        assertEquals("$x$", MergedOcrClient.equationHtmlToMarkdown(html));
    }

    @Test
    void equationHtmlToMarkdown_unescapesHtmlEntities() {
        String html = "<math display=\"block\">a &lt; b &amp;&amp; c &gt; d</math>";

        assertEquals("$$a < b && c > d$$", MergedOcrClient.equationHtmlToMarkdown(html));
    }

    // ---- buildMarkerJsonRequest ----

    @Test
    void buildMarkerJsonRequest_asksForJsonOutput() throws IOException {
        GpuBrokerOcrClient.MultipartRequest req =
                MergedOcrClient.buildMarkerJsonRequest(new byte[] {1, 2, 3});

        String body = new String(req.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("name=\"output_format\""));
        assertTrue(body.contains("json"));
        assertTrue(body.contains("name=\"page_range\""));
        assertTrue(body.contains("name=\"force_ocr\""),
                "force_ocr must be requested - without it Marker reads the scanner's embedded text layer");
        assertTrue(req.contentType().startsWith("multipart/form-data; boundary="));
    }

    /** JSON-encodes a string the way Marker's response embeds its output tree. */
    private static String toJsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
