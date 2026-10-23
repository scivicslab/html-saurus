package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Internal link checking against a built site: every same-site reference in a generated page must
 * name a file or page directory the build wrote, spelled with the same letter case.
 *
 * <p>The case rule is the reason this exists. A link {@code .../TypeScript/typescript} that differs
 * from the written directory in one letter resolves on a case-insensitive file system and answers
 * 404 on the Linux server that publishes the site, so a reader of the checked-out sources never sees
 * the break.
 */
@Tag("S1")
class LinkCheckerTest {

    @TempDir
    Path site;

    private void page(String dir, String body) throws IOException {
        Path d = dir.isEmpty() ? site : site.resolve(dir);
        Files.createDirectories(d);
        Files.writeString(d.resolve("index.html"), "<html><body>" + body + "</body></html>");
    }

    private List<LinkChecker.Finding> check() throws IOException {
        return LinkChecker.checkInternal(site);
    }

    @Test
    void absoluteLinkToExistingPageIsClean() throws IOException {
        page("guides/software/TypeScript/typescript", "<h1 id=\"top\">x</h1>");
        page("blog/post", "<a href=\"/guides/software/TypeScript/typescript\">t</a>"
                + "<a href=\"/guides/software/TypeScript/typescript/\">t</a>");
        assertEquals(List.of(), check());
    }

    @Test
    void linkToNothingIsMissing() throws IOException {
        page("blog/post", "<a href=\"/guides/nowhere\">t</a>");
        List<LinkChecker.Finding> f = check();
        assertEquals(1, f.size());
        assertEquals(LinkChecker.Kind.MISSING, f.get(0).kind());
        assertEquals("/blog/post/", f.get(0).page());
        assertEquals("/guides/nowhere", f.get(0).href());
    }

    @Test
    void linkDifferingOnlyInLetterCaseIsReportedAsCaseMismatch() throws IOException {
        page("guides/software/TypeScript/typescript", "x");
        page("blog/post", "<a href=\"/guides/software/typescript/typescript\">t</a>");
        List<LinkChecker.Finding> f = check();
        assertEquals(1, f.size());
        assertEquals(LinkChecker.Kind.CASE_MISMATCH, f.get(0).kind());
    }

    @Test
    void relativeReferencesResolveAgainstTheSlashTerminatedPageUrl() throws IOException {
        page("docs/a", "<a href=\"b/\">down</a><a href=\"../c/\">up</a><img src=\"pic.png\">");
        page("docs/a/b", "x");
        page("docs/c", "x");
        Files.writeString(site.resolve("docs/a/pic.png"), "png");
        assertEquals(List.of(), check());
    }

    @Test
    void missingSiblingFileIsMissing() throws IOException {
        page("docs/a", "<img src=\"pic.png\">");
        List<LinkChecker.Finding> f = check();
        assertEquals(1, f.size());
        assertEquals("pic.png", f.get(0).href());
    }

    @Test
    void externalMailAndScriptSchemesAreNotChecked() throws IOException {
        page("a", "<a href=\"https://example.com/x\">e</a><a href=\"//cdn.example.com/y\">p</a>"
                + "<a href=\"mailto:a@b.c\">m</a><a href=\"javascript:void(0)\">j</a>"
                + "<a href=\"tel:123\">t</a><a href=\"data:text/plain,hi\">d</a>");
        assertEquals(List.of(), check());
    }

    @Test
    void fragmentMustExistInTargetPage() throws IOException {
        page("t", "<h2 id=\"present\">h</h2><a name=\"named\"></a>");
        page("a", "<a href=\"/t/#present\">1</a><a href=\"/t/#named\">2</a><a href=\"/t/#absent\">3</a>"
                + "<a href=\"#self\">4</a><h3 id=\"self\">s</h3>");
        List<LinkChecker.Finding> f = check();
        assertEquals(1, f.size());
        assertEquals(LinkChecker.Kind.MISSING_FRAGMENT, f.get(0).kind());
        assertEquals("/t/#absent", f.get(0).href());
    }

    @Test
    void queryStringAndPercentEncodingAreHandled() throws IOException {
        page("日本語/ページ", "x");
        page("a", "<a href=\"/%E6%97%A5%E6%9C%AC%E8%AA%9E/%E3%83%9A%E3%83%BC%E3%82%B8/?v=1\">j</a>");
        assertEquals(List.of(), check());
    }

    @Test
    void entitiesInHrefAreDecoded() throws IOException {
        page("t", "x");
        Files.writeString(site.resolve("t/f&g.pdf"), "pdf");
        page("a", "<a href=\"/t/f&amp;g.pdf\">p</a>");
        assertEquals(List.of(), check());
    }

    @Test
    void sameHrefOnOnePageIsReportedOnce() throws IOException {
        page("a", "<a href=\"/gone\">1</a><a href=\"/gone\">2</a>");
        assertEquals(1, check().size());
    }

    @Test
    void linkLeavingAboveTheRootIsMissing() throws IOException {
        page("a", "<a href=\"../../../x\">u</a>");
        assertEquals(1, check().size());
    }

    @TempDir
    Path works;

    /** A works directory holding one doc repository whose only document declares {@code id}. */
    private Path repoDeclaring(String id) throws IOException {
        Path repo = works.resolve("doc_a");
        Path docs = repo.resolve("docs/guide");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("x.md"), "---\nid: " + id + "\ntitle: x\n---\n\nbody\n");
        return repo;
    }

    @Test
    void referenceToADeclaredDocumentIdIsClean() throws IOException {
        Path repo = repoDeclaring("Known_260101_aa01");
        page("a", "<ul><li><span data-doc-id=\"Known_260101_aa01\" data-relation=\"details\">x</span></li></ul>");
        assertEquals(List.of(), LinkChecker.checkInternal(site, repo));
    }

    @Test
    void referenceToAnIdNoRepositoryDeclaresIsReported() throws IOException {
        Path repo = repoDeclaring("Known_260101_aa01");
        page("a", "<span data-doc-id=\"Gone_260101_aa99\" data-relation=\"details\">x</span>"
                + "<span data-doc-id=\"Gone_260101_aa99\" data-relation=\"example\">y</span>");
        List<LinkChecker.Finding> f = LinkChecker.checkInternal(site, repo);
        assertEquals(1, f.size());
        assertEquals(LinkChecker.Kind.UNKNOWN_DOC_ID, f.get(0).kind());
        assertEquals("Gone_260101_aa99", f.get(0).href());
        assertEquals("/a/", f.get(0).page());
    }

    @Test
    void aPagesOwnDocIdAttributeOnAnotherElementIsNotAReference() throws IOException {
        Path repo = repoDeclaring("Known_260101_aa01");
        page("a", "<select id=\"view-format\" data-doc-id=\"Unlisted_260101_aa00\"></select>");
        assertEquals(List.of(), LinkChecker.checkInternal(site, repo));
    }

    @Test
    void idsAreNotJudgedWhenNoRepositoryIsFound() throws IOException {
        Path lonely = works.resolve("elsewhere");
        Files.createDirectories(lonely);
        page("a", "<span data-doc-id=\"Anything_260101_aa01\" data-relation=\"details\">x</span>");
        assertEquals(List.of(), LinkChecker.checkInternal(site, lonely));
    }

    @Test
    void numericCharacterReferencesInAHrefAreDecoded() throws IOException {
        page("books/a \u2013 b\u00A0c", "x");
        page("a", "<a href=\"/books/a &#x2013; b&#xA0;c/\">1</a><a href=\"/books/a &#8211; b&nbsp;c/\">2</a>");
        assertEquals(List.of(), check());
    }

    @Test
    void localePageLinkingToADefaultLocaleOnlyPathIsUntranslated() throws IOException {
        page("guide/only-default", "x");
        page("en/guide/translated", "<a href=\"../only-default/\">d</a><a href=\"../nowhere/\">n</a>");
        List<LinkChecker.Finding> f = check();
        assertEquals(2, f.size());
        assertEquals(LinkChecker.Kind.UNTRANSLATED, f.get(0).kind());
        assertEquals("../only-default/", f.get(0).href());
        assertEquals(LinkChecker.Kind.MISSING, f.get(1).kind());
    }
}
