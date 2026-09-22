package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks that a reference entry ends up naming the document it points at, wherever that document
 * lives and whatever the author wrote in the visible text.
 */
class ReferenceLabelsTest {

    /** Writes one document with the given frontmatter id. */
    private void doc(Path worksDir, String repo, String dir, String id) throws IOException {
        Path file = worksDir.resolve(repo).resolve("docs").resolve(dir).resolve(id + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nid: " + id + "\ntitle: t\n---\n\nbody\n");
    }

    private ReferenceLabels labelsOver(Path worksDir) {
        return ReferenceLabels.forProject(worksDir.resolve("doc_Base010"));
    }

    /** Built as "." from inside the repository, the path carries a dot the parent must not keep. */
    @Test
    void placesDocumentsWhenTheProjectPathIsNotNormalised(@TempDir Path works) throws IOException {
        doc(works, "doc_Base010", "ProjectStandard2", "AgentBrief_260905_oo01");
        ReferenceLabels labels = ReferenceLabels.forProject(works.resolve("doc_Base010").resolve("."));

        String out = labels.apply("<li><span data-doc-id=\"AgentBrief_260905_oo01\" data-relation=\"details\">"
                + "— トップページ</span></li>");

        assertTrue(out.contains("<code>doc_Base010: ProjectStandard2: AgentBrief_260905_oo01</code>"), out);
    }

    @Test
    void namesTheDocumentThatTheVisibleTextAlreadyShows(@TempDir Path works) throws IOException {
        doc(works, "doc_Base010", "ProjectStandard2", "AgentBrief_260905_oo01");

        String out = labelsOver(works).apply(
                "<li><span data-doc-id=\"AgentBrief_260905_oo01\" data-relation=\"prerequisite\">"
                + "<code>AgentBrief_260905_oo01</code> — トップページ</span></li>");

        assertTrue(out.contains("<code>doc_Base010: ProjectStandard2: AgentBrief_260905_oo01</code>"), out);
        assertTrue(out.contains("— トップページ"), out);
    }

    @Test
    void namesItEvenWhenTheAuthorWroteNoVisibleCopy(@TempDir Path works) throws IOException {
        doc(works, "doc_Base010", "ProjectStandard2", "AgentBrief_260905_oo01");

        String out = labelsOver(works).apply(
                "<li><span data-doc-id=\"AgentBrief_260905_oo01\" data-relation=\"details\">"
                + "— トップページ</span></li>");

        assertTrue(out.contains("<code>doc_Base010: ProjectStandard2: AgentBrief_260905_oo01</code> — トップページ"), out);
    }

    @Test
    void reachesAcrossRepositories(@TempDir Path works) throws IOException {
        doc(works, "doc_Base010", "ProjectStandard2", "AgentBrief_260905_oo01");
        doc(works, "doc_SCIVICS002", "html-saurus", "HtmlSaurusConcept_260331_oo01");

        String out = labelsOver(works).apply(
                "<li><span data-doc-id=\"HtmlSaurusConcept_260331_oo01\" data-relation=\"details\">"
                + "<code>HtmlSaurusConcept_260331_oo01</code> — 説明</span></li>");

        assertTrue(out.contains("<code>doc_SCIVICS002: html-saurus: HtmlSaurusConcept_260331_oo01</code>"), out);
    }

    /** A location guessed at would be worse than the one the author wrote. */
    @Test
    void leavesAnIdItCannotPlaceExactlyAsWritten(@TempDir Path works) throws IOException {
        doc(works, "doc_Base010", "ProjectStandard2", "AgentBrief_260905_oo01");

        String entry = "<li><span data-doc-id=\"GoneAway_251201_oo01\" data-relation=\"details\">"
                + "<code>GoneAway_251201_oo01</code> — 消えた文書</span></li>";

        assertEquals(entry, labelsOver(works).apply(entry));
    }

    /** A page with no references must come back byte for byte. */
    @Test
    void leavesAPageWithoutReferencesAlone(@TempDir Path works) throws IOException {
        doc(works, "doc_Base010", "ProjectStandard2", "AgentBrief_260905_oo01");

        String page = "<h2>Problem Definition</h2>\n<p>本文に <code>data-doc</code> は出てこない。</p>";

        assertEquals(page, labelsOver(works).apply(page));
    }

    /** The id moved to another directory; the page must show where it is now, not where it was. */
    @Test
    void followsADocumentThatMovedDirectory(@TempDir Path works) throws IOException {
        doc(works, "doc_Base010", "ProjectStandard2", "NamingByTypeAndInstance_260628_oo01");

        String out = labelsOver(works).apply(
                "<li><span data-doc-id=\"NamingByTypeAndInstance_260628_oo01\" data-relation=\"prerequisite\">"
                + "<code>doc_Base010: ProjectStandard: NamingByTypeAndInstance_260628_oo01</code> — 命名</span></li>");

        assertTrue(out.contains("<code>doc_Base010: ProjectStandard2: NamingByTypeAndInstance_260628_oo01</code>"), out);
        assertTrue(!out.contains("ProjectStandard:"), out);
    }
}
