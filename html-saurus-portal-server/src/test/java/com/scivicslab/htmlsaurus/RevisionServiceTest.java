package com.scivicslab.htmlsaurus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Revise panel's work without a browser or an LLM (RevisionPanel_261120_oo01). */
@Tag("RevisionPanel_261120_oo01")
@DisplayName("RevisionService locates a selection, filters proposals, writes back and records")
class RevisionServiceTest {

    static final String DOC = String.join("\n",
            "---",
            "id: Sample_261120_oo01",
            "title: Sample",
            "---",
            "",
            "# Heading of the sample",
            "",
            "The first paragraph says what the **sample** is for,",
            "and goes on to a second line.",
            "",
            "- an item of the list",
            "- another item with `code`",
            "",
            "$$",
            "a = b + c",
            "$$",
            "",
            "The last paragraph comes after the formula.",
            "");

    @TempDir
    Path works;
    Path file;
    final List<String> asked = new ArrayList<>();
    String answer = "";
    RevisionService service;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = works.resolve("doc_x/docs/sample");
        Files.createDirectories(dir);
        file = dir.resolve("sample.md");
        Files.writeString(file, DOC, StandardCharsets.UTF_8);
        Path antipatterns = works.resolve("doc_Base010/ap/010_Why_261027_oo01");
        Files.createDirectories(antipatterns);
        Files.writeString(antipatterns.resolve("010_Why_261027_oo01.md"),
                "---\nid: Why_261027_oo01\ntitle: Answer why with a reason\n---\n\nThe rule body.\n");
        service = new RevisionService(works, (system, user) -> { asked.add(user); return answer; },
                works.resolve(".html-saurus/revisions.jsonl"), works.resolve("doc_Base010/ap"));
    }

    @Test
    void aParagraphIsFoundWithItsLinesInTheFile() throws Exception {
        // The browser shows the paragraph without the ** marks and with the line break as a space.
        RevisionService.Located l = service.locate("doc_x/docs/sample/sample.md",
                "The first paragraph says what the sample is for, and goes on to a second line.", null);
        assertNotNull(l);
        assertEquals(8, l.fromLine());
        assertEquals(9, l.toLine());
        assertEquals("The first paragraph says what the **sample** is for,\nand goes on to a second line.", l.original());
        assertEquals(RevisionService.hash(DOC), l.hash());
        assertFalse(l.generated());
    }

    @Test
    void aSelectionAcrossBlocksCoversThemAll() throws Exception {
        RevisionService.Located l = service.locate("doc_x/docs/sample/sample.md",
                "Heading of the sample", "an item of the list");
        assertEquals(6, l.fromLine());
        assertEquals(11, l.toLine());
    }

    @Test
    void lineNumbersHoldAfterAFormula() throws Exception {
        RevisionService.Located l = service.locate("doc_x/docs/sample/sample.md",
                "The last paragraph comes after the formula.", null);
        assertEquals(18, l.fromLine());
        assertEquals(18, l.toLine());
    }

    @Test
    void textNotInTheFileIsNotFound() throws Exception {
        assertNull(service.locate("doc_x/docs/sample/sample.md", "Nothing like this is written there.", null));
        assertNull(service.locate("../outside.md", "x", null), "a path outside the works directory");
    }

    @Test
    void aProposalWhoseQuoteIsNotInTheTextIsDropped() throws Exception {
        answer = "Here you are:\n```json\n{\"proposals\":["
                + "{\"quote\":\"says what the **sample** is for\",\"problem\":\"vague\",\"fix\":\"name the purpose\"},"
                + "{\"quote\":\"a sentence that is not there\",\"problem\":\"x\",\"fix\":\"y\"}]}\n```";
        List<RevisionService.Proposal> p = service.proposals("The first paragraph says what the **sample** is for,",
                service.instruction("propose", ""));
        assertEquals(1, p.size());
        assertEquals("vague", p.get(0).problem());
    }

    @Test
    void anAntipatternIsAppliedByItsBody() throws Exception {
        String rule = service.instruction("antipattern", "Why_261027_oo01");
        assertTrue(rule.contains("The rule body."), rule);
        assertFalse(rule.contains("title:"), "the front matter is not part of the rule");
        assertEquals(1, service.antipatterns().size());
        assertEquals("Answer why with a reason", service.antipatterns().get(0).get("title").asText());
    }

    @Test
    void theRevisedTextReplacesExactlyTheLines() throws Exception {
        String revised = "The first paragraph names the purpose of the sample.";
        RevisionService.WriteResult r = service.writeBack("doc_x/docs/sample/sample.md", 8, 9, RevisionService.hash(DOC), revised);
        assertTrue(r.written(), String.valueOf(r.reason()));
        String now = Files.readString(file);
        assertEquals(DOC.replace("The first paragraph says what the **sample** is for,\nand goes on to a second line.", revised), now);
        assertEquals(RevisionService.hash(now), r.hash());
    }

    @Test
    void aFileChangedSinceItWasReadIsNotWritten() throws Exception {
        String before = RevisionService.hash(DOC);
        Files.writeString(file, DOC + "one more line\n");
        RevisionService.WriteResult r = service.writeBack("doc_x/docs/sample/sample.md", 8, 9, before, "x");
        assertFalse(r.written());
        assertTrue(Files.readString(file).endsWith("one more line\n"));
        assertNull(service.linesIfUnchanged("doc_x/docs/sample/sample.md", 8, 9, before));
    }

    @Test
    void aFileARuleFileGeneratesIsNotWritten() throws Exception {
        Files.writeString(file.resolveSibling("sample.lisp"), "(doc)");
        RevisionService.WriteResult r = service.writeBack("doc_x/docs/sample/sample.md", 8, 9, RevisionService.hash(DOC), "x");
        assertFalse(r.written());
        assertTrue(r.reason().contains("sample.lisp"), r.reason());
        assertTrue(service.locate("doc_x/docs/sample/sample.md", "Heading of the sample", null).generated());
    }

    @Test
    void eachRevisionIsOneLineOfTheRecord() throws Exception {
        ObjectMapper json = new ObjectMapper();
        service.log(json.readTree("{\"path\":\"doc_x/docs/sample/sample.md\",\"written\":false}"));
        service.log(json.readTree("{\"path\":\"doc_x/docs/sample/sample.md\",\"written\":true}"));
        List<String> lines = Files.readAllLines(works.resolve(".html-saurus/revisions.jsonl"));
        assertEquals(2, lines.size());
        assertTrue(json.readTree(lines.get(1)).path("written").asBoolean());
        assertTrue(json.readTree(lines.get(0)).has("time"));
    }

    @Test
    void aConversationsReplyBecomesProposals() {
        List<RevisionService.Proposal> p = service.proposalsFromReply(
                "Done.\n{\"proposals\":[{\"quote\":\"q\",\"problem\":\"p\",\"fix\":\"f\"}]}");
        assertEquals(1, p.size());
        assertEquals("q", p.get(0).quote());
        List<RevisionService.Proposal> prose = service.proposalsFromReply("I would move the conclusion first.");
        assertEquals(1, prose.size());
        assertEquals("I would move the conclusion first.", prose.get(0).problem(), "a reply without JSON is shown whole");
    }

    @Test
    void aConversationIsNamedTheWayChatUiNamesIt() {
        assertEquals("project9/chat-04", ChatAsk.conversationName("project9/04"));
        assertEquals("project9/chat-04", ChatAsk.conversationName(" project9/chat-04 "));
    }

    @Test
    void theRequestToAConversationSaysWhereTheTextIsAndHowToAnswer() {
        String prompt = RevisionService.conversationPrompt("doc_x/docs/a.md", 3, 5, "TEXT HERE", "RULE HERE");
        assertTrue(prompt.contains("lines 3-5 of the Markdown file doc_x/docs/a.md"), prompt);
        assertTrue(prompt.contains("TEXT HERE") && prompt.contains("RULE HERE"));
        assertTrue(prompt.contains("{\"proposals\":["));
    }

    @Test
    void theRevisedTextLosesACodeFenceAroundIt() {
        assertEquals("line one\nline two", RevisionService.stripFence("```markdown\nline one\nline two\n```"));
        assertEquals("plain", RevisionService.stripFence("plain"));
    }
}
