package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.jobregistry.Job;
import com.scivicslab.jobregistry.JobRegistry;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * The export as a whole, with a stand-in for Chrome (ExportToPdf_261119_oo01): the numbers in the table of contents are
 * the pages the parts end up on, printing is repeated until they settle, and what cannot be trusted ends the job in
 * error. The stand-in lays a PDF out from the HTML it is given, and puts the table of contents on two pages while the
 * numbers are still the placeholder {@code 000}, as a wider number can make a real one.
 */
@Tag("ExportToPdf_261119_oo01")
class PdfExportJobTest {

    @TempDir
    Path dir;

    private ActorSystem system;
    private JobRegistry registry;
    private Path docs;
    private Path staticDir;
    private Path work;

    @BeforeEach
    void setUp() throws IOException {
        system = new ActorSystem("pdf-export-job-test");
        registry = new JobRegistry(system, 60_000L);
        docs = dir.resolve("docs");
        staticDir = dir.resolve("static-html");
        work = Files.createDirectories(dir.resolve("work"));
        Files.createDirectories(staticDir);
        for (String name : List.of("one", "two", "three")) {
            Files.createDirectories(docs.resolve("A"));
            Files.writeString(docs.resolve("A/" + name + ".md"), "---\ntitle: " + name + "\n---\n\nBody of " + name + ".\n");
        }
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    /** Lays a PDF out the way the HTML it is given asks; how many pages the contents take is the part a test chooses. */
    private static final class StubPrinter implements HtmlToPdfPrinter {
        final List<String> printed = new ArrayList<>();
        /** Decides how many pages the table of contents takes, from the HTML and the number of printings so far. */
        final java.util.function.BiFunction<String, Integer, Integer> contentsPages;
        final boolean withLinks;
        /** What a part's page says; Chrome prints the text of the page. */
        final String partText;
        /** Runs when printing starts, before the HTML is read: what another process may do to the site meanwhile. */
        Runnable beforeLayingOut = () -> { };
        /** The images of the HTML given to the last printing that were not there to be read. */
        final List<String> missingImages = new ArrayList<>();
        final List<String> imageUrls = new ArrayList<>();

        StubPrinter(java.util.function.BiFunction<String, Integer, Integer> contentsPages, boolean withLinks) {
            this(contentsPages, withLinks, null);
        }

        StubPrinter(java.util.function.BiFunction<String, Integer, Integer> contentsPages, boolean withLinks, String partText) {
            this.contentsPages = contentsPages;
            this.withLinks = withLinks;
            this.partText = partText;
        }

        @Override
        public void printHtmlToPdf(Path html, Path pdf, Path workDir, Duration timeout) throws IOException {
            beforeLayingOut.run();
            String text = Files.readString(html, StandardCharsets.UTF_8);
            printed.add(text);
            Matcher images = Pattern.compile("<img[^>]* src=\"(file:[^\"]+)\"").matcher(text);
            while (images.find()) {
                imageUrls.add(images.group(1));
                if (!Files.exists(Path.of(java.net.URI.create(images.group(1))))) missingImages.add(images.group(1));
            }
            Files.createDirectories(workDir.resolve("chrome-profile"));
            Files.writeString(workDir.resolve("chrome-profile/state"), "x");
            int contents = contentsPages.apply(text, printed.size());
            List<Integer> numbers = new ArrayList<>();
            Matcher matcher = Pattern.compile("<a href=\"#hs-part-(\\d+)\">").matcher(text);
            while (matcher.find()) if (!numbers.contains(Integer.valueOf(matcher.group(1)))) numbers.add(Integer.valueOf(matcher.group(1)));
            List<String> pages = new ArrayList<>();
            pages.add("cover");
            for (int i = 0; i < contents; i++) pages.add("contents " + i);
            Map<Integer, Integer> startOfPart = new LinkedHashMap<>();
            for (Integer number : numbers) {
                pages.add(partText != null ? partText : "part " + number);
                startOfPart.put(number, pages.size());
            }
            ExportTestPdfs.write(pdf, pages, withLinks ? 2 : 0, startOfPart);
        }
    }

    private Job<ExportOutcome> run(HtmlToPdfPrinter printer, PdfExportJob.HtmlBuild build, Lock lock) throws Exception {
        PdfExportJob work = new PdfExportJob("proj", docs, staticDir, "A", this.work, printer, build, lock);
        Job<ExportOutcome> job = registry.submit("export", "proj/A", work::exportToPdf, System.currentTimeMillis());
        long deadline = System.currentTimeMillis() + 20_000;
        while (!job.isTerminal() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(job.isTerminal(), "job did not finish");
        return job;
    }

    private static List<String> contentsNumbers(String html) {
        List<String> numbers = new ArrayList<>();
        Matcher matcher = Pattern.compile("hs-toc-page\">(\\d+)<").matcher(html);
        while (matcher.find()) numbers.add(matcher.group(1));
        return numbers;
    }

    @Test
    void exportToPdf_whenTheNumbersMoveThePartsOnce_printsAgainUntilTheContentsShowThePagesTheyAreOn() throws Exception {
        // Two pages of contents while the numbers are placeholders, one once they are real: the parts move up by one page.
        StubPrinter printer = new StubPrinter((html, call) -> html.contains(">000<") ? 2 : 1, true);

        Job<ExportOutcome> job = run(printer, () -> true, new ReentrantLock());

        assertEquals(Job.State.DONE, job.state(), job.error());
        assertEquals(3, printer.printed.size(), "placeholders, the first pages found, the pages once the contents moved");
        // Cover, one page of contents, then the three parts (a folder heading is not here: the three are documents of A).
        assertEquals(List.of("3", "4", "5"), contentsNumbers(printer.printed.get(2)));
        ExportOutcome outcome = job.result();
        assertNotNull(outcome.pdf());
        assertEquals(5, outcome.pages());
        try (PDDocument doc = Loader.loadPDF(outcome.pdf().toFile())) {
            assertEquals(5, doc.getNumberOfPages());
            PDOutlineItem first = doc.getDocumentCatalog().getDocumentOutline().getFirstChild();
            assertEquals("Contents", first.getTitle());
            assertEquals(1, doc.getPages().indexOf(first.findDestinationPage(doc)), "Contents is on the page the links were found on");
            PDOutlineItem one = first.getNextSibling();
            assertEquals("one", one.getTitle());
            assertEquals(2, doc.getPages().indexOf(one.findDestinationPage(doc)), "the page the contents say is 3");
        }
    }

    @Test
    void exportToPdf_whenTheNumbersAlreadyHold_printsTwice() throws Exception {
        StubPrinter printer = new StubPrinter((html, call) -> 1, true);
        Job<ExportOutcome> job = run(printer, () -> true, new ReentrantLock());
        assertEquals(Job.State.DONE, job.state(), job.error());
        assertEquals(2, printer.printed.size());
    }

    @Test
    void exportToPdf_whenThePagesDoNotSettle_endsInErrorAndLeavesNoIntermediateFiles() throws Exception {
        // The contents take one page, then two, then one: the numbers keep being wrong.
        StubPrinter printer = new StubPrinter((html, call) -> 1 + (call % 2), true);

        Job<ExportOutcome> job = run(printer, () -> true, new ReentrantLock());

        assertEquals(Job.State.ERROR, job.state());
        assertTrue(job.error().contains("did not settle"), job.error());
        assertEquals(PdfExportJob.PRINT_ATTEMPTS, printer.printed.size());
        assertFalse(Files.exists(work.resolve("export.html")) || Files.exists(work.resolve("printed.pdf")), "files of the printing are left");
        assertFalse(Files.exists(work.resolve("chrome-profile")), "the Chrome profile is left");
    }

    @Test
    void exportToPdf_whenThePrintedPdfDoesNotSayWhereThePartsStart_endsInError() throws Exception {
        StubPrinter printer = new StubPrinter((html, call) -> 1, false);
        Job<ExportOutcome> job = run(printer, () -> true, new ReentrantLock());
        assertEquals(Job.State.ERROR, job.state());
        assertTrue(job.error().contains("does not say which page"), job.error());
    }

    @Test
    void exportToPdf_whenTheHtmlCannotBeBuilt_endsInErrorWithoutPrinting() throws Exception {
        StubPrinter printer = new StubPrinter((html, call) -> 1, true);
        Job<ExportOutcome> job = run(printer, () -> false, new ReentrantLock());
        assertEquals(Job.State.ERROR, job.state());
        assertTrue(job.error().contains("could not be built"), job.error());
        assertTrue(printer.printed.isEmpty());
    }

    @Test
    void exportToPdf_whenMathIsStillAsWrittenInThePrintedPdf_saysSoOnTheLine() throws Exception {
        Files.writeString(docs.resolve("A/one.md"), "---\ntitle: one\n---\n\nThe formula $E = mc^2$ here.\n");
        Files.createDirectories(staticDir.resolve("A/one"));
        Files.writeString(staticDir.resolve("A/one/index.html"), "<html><body><main><h1>one</h1><p>The formula $E = mc^2$ here.</p></main></body></html>");
        // The stand-in prints the page text into the PDF as it is, as Chrome does when the KaTeX script did not load.
        HtmlToPdfPrinter printer = new StubPrinter((html, call) -> 1, true, "The formula $E = mc^2$ here.");
        Job<ExportOutcome> job = run(printer, () -> true, new ReentrantLock());
        assertEquals(Job.State.DONE, job.state(), job.error());
        assertTrue(((ExportOutcome) job.result()).warning().contains("KaTeX"), "warning: " + ((ExportOutcome) job.result()).warning());
    }

    /** Counts how many exports are rebuilding at once. */
    private static PdfExportJob.HtmlBuild countingBuild(AtomicInteger running, AtomicInteger most) {
        return () -> {
            int now = running.incrementAndGet();
            most.accumulateAndGet(now, Math::max);
            Thread.sleep(250);
            running.decrementAndGet();
            return true;
        };
    }

    private int mostRebuildingAtOnce(Lock first, Lock second) throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        Path otherWork = Files.createDirectories(dir.resolve("work2"));
        PdfExportJob one = new PdfExportJob("proj", docs, staticDir, "A", work, new StubPrinter((h, c) -> 1, true),
                countingBuild(running, most), first);
        PdfExportJob two = new PdfExportJob("proj", docs, staticDir, "A", otherWork, new StubPrinter((h, c) -> 1, true),
                countingBuild(running, most), second);
        Job<ExportOutcome> jobOne = registry.submit("export", "one", one::exportToPdf, System.currentTimeMillis());
        Job<ExportOutcome> jobTwo = registry.submit("export", "two", two::exportToPdf, System.currentTimeMillis());
        long deadline = System.currentTimeMillis() + 20_000;
        while ((!jobOne.isTerminal() || !jobTwo.isTerminal()) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(Job.State.DONE, jobOne.state(), jobOne.error());
        assertEquals(Job.State.DONE, jobTwo.state(), jobTwo.error());
        return most.get();
    }

    @Test
    void exportToPdf_whenTwoExportsShareAProjectLock_rebuildOneAfterTheOther() throws Exception {
        ReentrantLock shared = new ReentrantLock();
        assertEquals(1, mostRebuildingAtOnce(shared, shared));
    }

    @Test
    void exportToPdf_whenTheExportsHaveNoLockInCommon_rebuildAtTheSameTime() throws Exception {
        // The check above would pass for a job that never rebuilds at the same time as anything; this one shows it can.
        assertEquals(2, mostRebuildingAtOnce(new ReentrantLock(), new ReentrantLock()));
    }

    @Test
    void deleteStaleWorkDirectories_deletesOldExportDirectoriesOnly() throws Exception {
        Path old = Files.createDirectories(dir.resolve(PdfExportJob.WORK_DIRECTORY_PREFIX + "old"));
        Files.writeString(old.resolve("a.pdf"), "x");
        Path recent = Files.createDirectories(dir.resolve(PdfExportJob.WORK_DIRECTORY_PREFIX + "recent"));
        Path other = Files.createDirectories(dir.resolve("something-else"));
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(3))));
        Files.setLastModifiedTime(other, FileTime.from(Instant.now().minus(Duration.ofDays(3))));

        PdfExportJob.deleteStaleWorkDirectories(dir, Duration.ofDays(1));

        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent), "a young directory may be an export of another portal");
        assertTrue(Files.exists(other), "only the directories of exports are looked at");
    }

    @Test
    void exportToPdf_whenTheSiteIsRebuiltWhileChromePrints_theImagesAreStillThere() throws Exception {
        Files.createDirectories(staticDir.resolve("A/one"));
        Files.writeString(staticDir.resolve("A/one/index.html"), "<html><body><main><h1>one</h1><p>text</p><img src=\"fig.png\"></main></body></html>");
        Files.writeString(staticDir.resolve("A/one/fig.png"), "not really a png");
        StubPrinter printer = new StubPrinter((html, call) -> 1, true);
        // Another export, the Projects tab or a build of the MCP rebuilds the project: static-html/ is gone for a while.
        printer.beforeLayingOut = () -> PdfExportJob.deleteDirectory(staticDir);

        Job<ExportOutcome> job = run(printer, () -> true, new ReentrantLock());

        assertEquals(Job.State.DONE, job.state(), job.error());
        assertFalse(printer.imageUrls.isEmpty(), "the page has an image the printing should read");
        assertTrue(printer.missingImages.isEmpty(), "images Chrome would not find: " + printer.missingImages);
    }

    @Test
    void mayBeStopped_whileTheHtmlIsRebuilt_isFalse_becauseAStoppedBuildLeavesHalfAWrittenSite() throws Exception {
        java.util.concurrent.CountDownLatch inRebuild = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Job<ExportOutcome> job = registry.submit("export", "proj/A", (Job<ExportOutcome> j) -> {
            j.phase(PdfExportJob.PHASE_BUILDING_THE_HTML);
            inRebuild.countDown();
            release.await();
            j.phase("Printing (1 of at most 3)");
            Thread.sleep(2_000);
        }, System.currentTimeMillis());
        assertTrue(inRebuild.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(PdfExportJob.mayBeStopped(job), "a rebuild is not stopped half way");
        release.countDown();
        for (int i = 0; i < 100 && !PdfExportJob.mayBeStopped(job); i++) Thread.sleep(20);
        assertTrue(PdfExportJob.mayBeStopped(job), "once it prints, it may be stopped");
    }

    @Test
    void mayBeStopped_beforeTheJobHasSaidWhatItDoes_isFalse() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Job<ExportOutcome> job = registry.submit("export", "proj/A", (Job<ExportOutcome> j) -> release.await(), System.currentTimeMillis());
        assertFalse(PdfExportJob.mayBeStopped(job), "a job that has just started is about to rebuild");
        release.countDown();
    }
}
