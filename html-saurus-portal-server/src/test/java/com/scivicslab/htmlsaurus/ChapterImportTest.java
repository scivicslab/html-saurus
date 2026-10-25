package com.scivicslab.htmlsaurus;

import com.scivicslab.htmlsaurus.ChapterSplitter.Kind;
import com.scivicslab.htmlsaurus.ChapterSplitter.Section;
import com.scivicslab.jobregistry.Job;
import com.scivicslab.jobregistry.JobRegistry;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chapter split of a PDF import: {@link ChapterBoundaryClient} asking the model window by
 * window (against a stub model), and {@link PdfImportJob} writing one directory per part of the
 * book from what the planner decided (against a stub OCR backend and a stub planner). No OCR or
 * model server is involved.
 */
class ChapterImportTest {

    @TempDir
    Path tempDir;

    private ActorSystem system;
    private JobRegistry registry;

    @BeforeEach
    void startRegistry() {
        system = new ActorSystem("chapter-import-test");
        registry = new JobRegistry(system, 60_000L);
    }

    @AfterEach
    void stopRegistry() {
        system.terminate();
    }

    // ── ChapterBoundaryClient ────────────────────────────────────────────────

    private static String[] pages(int count) {
        String[] pages = new String[count];
        java.util.Arrays.fill(pages, "text");
        return pages;
    }

    @Test
    void theModelIsAskedOneWindowOfPagesAtATimeAndTheAnswersAreJoined() throws Exception {
        List<String> users = new ArrayList<>();
        List<String> answers = new ArrayList<>(List.of(
                "{\"sections\":[{\"kind\":\"chapter\",\"title\":\"One\",\"startPage\":1}]}",
                "{\"sections\":[{\"kind\":\"chapter\",\"title\":\"Two\",\"startPage\":150}]}",
                "{\"sections\":[]}"));
        ChapterBoundaryClient client = new ChapterBoundaryClient((system, user, timeout) -> {
            users.add(user);
            return answers.remove(0);
        });

        List<Section> sections = client.plan(pages(250));

        assertEquals(List.of(new Section(Kind.CHAPTER, "One", 0), new Section(Kind.CHAPTER, "Two", 149)), sections);
        assertEquals(3, users.size());
        assertTrue(users.get(1).startsWith("Pages 101 to 200 of a book that has 250 pages:\np101: text"), users.get(1));
        assertTrue(users.get(2).startsWith("Pages 201 to 250 "), users.get(2));
    }

    @Test
    void anUnreadableAnswerIsAskedAgainOnce() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        ChapterBoundaryClient client = new ChapterBoundaryClient((system, user, timeout) ->
                asked.incrementAndGet() == 1 ? "I am not sure."
                        : "{\"sections\":[{\"kind\":\"chapter\",\"title\":\"One\",\"startPage\":2}]}");

        assertEquals(List.of(new Section(Kind.CHAPTER, "One", 1)), client.plan(pages(10)));
        assertEquals(2, asked.get());
    }

    @Test
    void anAnswerThatStaysUnreadableFailsWithTheWindowNamed() {
        ChapterBoundaryClient client = new ChapterBoundaryClient((system, user, timeout) -> "nope");
        var e = assertThrows(IllegalArgumentException.class, () -> client.plan(pages(10)));
        assertTrue(e.getMessage().startsWith("pages 1-10: "), e.getMessage());
    }

    @Test
    void aBookWithNoChapterFoundFails() {
        ChapterBoundaryClient client = new ChapterBoundaryClient((system, user, timeout) -> "{\"sections\":[]}");
        assertThrows(IllegalArgumentException.class, () -> client.plan(pages(10)));
    }

    @Test
    void aModelThatCannotBeReachedFailsWithAnIoException() {
        ChapterBoundaryClient client = new ChapterBoundaryClient((system, user, timeout) -> {
            throw new IOException("connection refused");
        });
        assertThrows(IOException.class, () -> client.plan(pages(10)));
    }

    // ── PdfImportJob ─────────────────────────────────────────────────────────

    /** Page {@code n} (1-based, in the order the job asks) comes back as the text {@code page-n}. */
    static class StubOcrClient implements OcrClient {
        private int calls = 0;
        @Override public String backendId() { return "stub"; }
        @Override public Result ocrPage(byte[] onePagePdfBytes) {
            calls++;
            return new Result(List.of("page-" + calls), Map.of());
        }
    }

    private static byte[] pdfOf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private Job<ImportOutcome> runImport(int pages, int pagesPerFile, ChapterSplitter.Planner planner,
                                         AtomicBoolean done) throws Exception {
        PdfImportJob work = new PdfImportJob(pdfOf(pages), tempDir, "book", new StubOcrClient(), pagesPerFile,
                pages, "Book", "proj/docs/dest", () -> done.set(true), planner);
        Job<ImportOutcome> job = registry.submit("pdf", "book.pdf", work::run, System.currentTimeMillis());
        for (int i = 0; i < 200 && !job.isTerminal(); i++) Thread.sleep(50);
        assertTrue(job.isTerminal(), "the import did not finish");
        return job;
    }

    private List<String> filesUnderTempDir() throws IOException {
        try (Stream<Path> walk = Files.walk(tempDir)) {
            return walk.filter(Files::isRegularFile).map(p -> tempDir.relativize(p).toString()).sorted().toList();
        }
    }

    @Test
    void theFilesAreWrittenUnderOneDirectoryPerPartWithPageNumbersRestartingInEach() throws Exception {
        ChapterSplitter.Planner planner = pageMarkdown -> List.of(
                new Section(Kind.CHAPTER, "Alpha", 2), new Section(Kind.CHAPTER, "Beta", 5));
        AtomicBoolean done = new AtomicBoolean();

        Job<ImportOutcome> job = runImport(7, 2, planner, done);

        assertEquals(Job.State.DONE, job.state(), job.error());
        assertTrue(done.get(), "the project is rebuilt once the files are written");
        assertEquals(List.of(
                "Chap01/Alpha_p001-002/Alpha_p001-002.md",
                "Chap01/Alpha_p003-003/Alpha_p003-003.md",
                "Chap02/Beta_p001-002/Beta_p001-002.md",
                "book/book_p001-002/book_p001-002.md"), filesUnderTempDir());
        String chapterOne = Files.readString(tempDir.resolve("Chap01/Alpha_p001-002/Alpha_p001-002.md"), StandardCharsets.UTF_8);
        assertTrue(chapterOne.contains("title: \"Alpha\""), chapterOne);
        assertTrue(chapterOne.contains("pages: \"3-4\""), "the front matter keeps the PDF's own page numbers: " + chapterOne);
        assertTrue(chapterOne.contains("page-3") && chapterOne.contains("page-4"), chapterOne);
        assertEquals("proj/docs/dest/Chap02/Beta_p001-002/Beta_p001-002.md", job.result().lastFile());
    }

    @Test
    void whenTheChaptersCannotBeJudgedTheFlatLayoutIsWrittenAndTheReasonIsKept() throws Exception {
        ChapterSplitter.Planner planner = pageMarkdown -> { throw new IOException("model down"); };
        AtomicBoolean done = new AtomicBoolean();

        Job<ImportOutcome> job = runImport(5, 2, planner, done);

        assertEquals(Job.State.DONE, job.state(), job.error());
        assertTrue(done.get());
        assertEquals(List.of(
                "book_p001-002/book_p001-002.md",
                "book_p003-004/book_p003-004.md",
                "book_p005-005/book_p005-005.md"), filesUnderTempDir());
        assertTrue(job.phase().contains("Chapter split failed (model down)"), job.phase());
    }

    @Test
    void withoutAPlannerTheFlatLayoutIsWrittenAsBefore() throws Exception {
        AtomicBoolean done = new AtomicBoolean();

        Job<ImportOutcome> job = runImport(3, 2, null, done);

        assertEquals(Job.State.DONE, job.state(), job.error());
        assertEquals(List.of("book_p001-002/book_p001-002.md", "book_p003-003/book_p003-003.md"), filesUnderTempDir());
        assertEquals("proj/docs/dest/book_p003-003/book_p003-003.md", job.result().lastFile());
    }
}
