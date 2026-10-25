package com.scivicslab.htmlsaurus;

import com.scivicslab.htmlsaurus.ChapterSplitter.Kind;
import com.scivicslab.htmlsaurus.ChapterSplitter.Placement;
import com.scivicslab.htmlsaurus.ChapterSplitter.Section;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ChapterSplitter}: what the model is shown, how its answer is read, and
 *  where each part of the book is written. No model server is involved. */
class ChapterSplitterTest {

    @Test
    void digestShowsOneLinePerPageWithItsNumberAndTheStartOfItsText() {
        String[] pages = {"# Chapter 1\n\nBody  text", null, "x".repeat(500)};
        String digest = ChapterSplitter.digest(pages, 0, 3);
        String[] lines = digest.split("\n");
        assertEquals(3, lines.length);
        assertEquals("p1: # Chapter 1 Body text", lines[0]);
        assertEquals("p2: (blank)", lines[1]);
        assertEquals("p3: " + "x".repeat(ChapterSplitter.HEAD_CHARS), lines[2]);
    }

    @Test
    void digestOfALaterWindowKeepsTheAbsolutePageNumbers() {
        String[] pages = new String[150];
        java.util.Arrays.fill(pages, "text");
        String digest = ChapterSplitter.digest(pages, 100, 102);
        assertTrue(digest.startsWith("p101: text\np102: text"), digest);
    }

    @Test
    void anAnswerWrappedInACodeFenceIsRead() {
        String answer = "Here it is:\n```json\n{\"sections\":[{\"kind\":\"chapter\",\"title\":\"Intro\",\"startPage\":3}]}\n```";
        List<Section> sections = ChapterSplitter.parseWindowAnswer(answer, 1, 100);
        assertEquals(List.of(new Section(Kind.CHAPTER, "Intro", 2)), sections);
    }

    @Test
    void entriesWithAnUnknownKindABlankTitleOrAPageOutsideTheWindowAreDropped() {
        String answer = "{\"sections\":["
                + "{\"kind\":\"chapter\",\"title\":\"Good\",\"startPage\":5},"
                + "{\"kind\":\"poem\",\"title\":\"Odd kind\",\"startPage\":6},"
                + "{\"kind\":\"chapter\",\"title\":\"  \",\"startPage\":7},"
                + "{\"kind\":\"chapter\",\"title\":\"Outside\",\"startPage\":500},"
                + "{\"kind\":\"APPENDIX\",\"title\":\"Upper case kind\",\"startPage\":9}]}";
        List<Section> sections = ChapterSplitter.parseWindowAnswer(answer, 1, 100);
        assertEquals(List.of(new Section(Kind.CHAPTER, "Good", 4),
                new Section(Kind.APPENDIX, "Upper case kind", 8)), sections);
    }

    @Test
    void anAnswerWithoutAJsonObjectOrASectionsListIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ChapterSplitter.parseWindowAnswer("no idea", 1, 10));
        assertThrows(IllegalArgumentException.class, () -> ChapterSplitter.parseWindowAnswer("{\"parts\":[]}", 1, 10));
        assertEquals(List.of(), ChapterSplitter.parseWindowAnswer("{\"sections\":[]}", 1, 10));
    }

    @Test
    void validateSortsByPageAndKeepsTheFirstOfTwoSectionsOnOnePage() {
        List<Section> sections = ChapterSplitter.validate(List.of(
                new Section(Kind.CHAPTER, "Second", 20),
                new Section(Kind.CHAPTER, "First", 5),
                new Section(Kind.APPENDIX, "Same page", 20)));
        assertEquals(List.of(new Section(Kind.CHAPTER, "First", 5), new Section(Kind.CHAPTER, "Second", 20)), sections);
    }

    @Test
    void validateRefusesABookWithNoChapter() {
        assertThrows(IllegalArgumentException.class,
                () -> ChapterSplitter.validate(List.of(new Section(Kind.BACK, "Index", 90))));
        assertThrows(IllegalArgumentException.class, () -> ChapterSplitter.validate(List.of()));
    }

    @Test
    void layoutNamesChaptersAppendicesAndTheFrontMatterLikeTheExistingBooks() {
        List<Section> sections = List.of(
                new Section(Kind.FRONT, "Preface", 4),
                new Section(Kind.CHAPTER, "Understanding Kubernetes Architecture", 10),
                new Section(Kind.CHAPTER, "Creating Kubernetes Clusters", 40),
                new Section(Kind.APPENDIX, "Cheat sheet", 80),
                new Section(Kind.APPENDIX, "Glossary", 85),
                new Section(Kind.BACK, "Index", 90));
        List<Placement> placements = ChapterSplitter.layout(sections, 100, "Mastering K8s", "Mastering Kubernetes");

        assertEquals(List.of(
                new Placement("Mastering K8s", "Mastering K8s", "Mastering Kubernetes", 0, 4),
                new Placement("Preface", "Preface", "Preface", 4, 10),
                new Placement("Chap01", "Understanding Kubernetes Architecture", "Understanding Kubernetes Architecture", 10, 40),
                new Placement("Chap02", "Creating Kubernetes Clusters", "Creating Kubernetes Clusters", 40, 80),
                new Placement("Appendix_A", "Cheat sheet", "Cheat sheet", 80, 85),
                new Placement("Appendix_B", "Glossary", "Glossary", 85, 90),
                new Placement("Index", "Index", "Index", 90, 100)), placements);
    }

    @Test
    void layoutWithAChapterOnThePdfsFirstPageHasNoFrontMatterDirectory() {
        List<Placement> placements = ChapterSplitter.layout(
                List.of(new Section(Kind.CHAPTER, "Only", 0)), 12, "book", "Book");
        assertEquals(List.of(new Placement("Chap01", "Only", "Only", 0, 12)), placements);
    }

    @Test
    void layoutCoversEveryPageExactlyOnce() {
        List<Section> sections = List.of(new Section(Kind.CHAPTER, "A", 7), new Section(Kind.CHAPTER, "B", 30));
        List<Placement> placements = ChapterSplitter.layout(sections, 50, "book", "Book");
        int next = 0;
        for (Placement p : placements) {
            assertEquals(next, p.fromPage());
            assertTrue(p.toPage() > p.fromPage());
            next = p.toPage();
        }
        assertEquals(50, next);
    }

    @Test
    void directoryNamesStayUniqueWhenTwoPartsHaveTheSameTitle() {
        List<Section> sections = List.of(new Section(Kind.CHAPTER, "C", 0),
                new Section(Kind.BACK, "Notes", 10), new Section(Kind.BACK, "Notes", 20));
        List<Placement> placements = ChapterSplitter.layout(sections, 30, "book", "Book");
        assertEquals(List.of("Chap01", "Notes", "Notes_2"), placements.stream().map(Placement::dirName).toList());
    }

    @Test
    void eachBookOfABulkImportGetsADirectoryNamedAfterItsPdf() {
        assertEquals("Mastering K8s", ChapterSplitter.bookDirectoryName("Mastering K8s.pdf"));
        assertEquals("Upper", ChapterSplitter.bookDirectoryName("Upper.PDF"));
        assertEquals("a_b", ChapterSplitter.bookDirectoryName("a:b.pdf"));
        assertEquals("no extension", ChapterSplitter.bookDirectoryName("no extension"));
    }

    @Test
    void safeNameReplacesCharactersAFilesystemDislikesAndLimitsTheLength() {
        assertEquals("What _ Why_ How", ChapterSplitter.safeName("What / Why: How"));
        assertEquals("Untitled", ChapterSplitter.safeName("  ... "));
        assertEquals(80, ChapterSplitter.safeName("a".repeat(200)).length());
    }
}
