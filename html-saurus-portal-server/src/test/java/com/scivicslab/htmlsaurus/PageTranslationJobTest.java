package com.scivicslab.htmlsaurus;

import com.scivicslab.jobregistry.Job;
import com.scivicslab.jobregistry.JobRegistry;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** The page translation job against a fake translator: no GPU broker, no server. */
class PageTranslationJobTest {

    @TempDir
    Path dir;

    private ActorSystem system;
    private JobRegistry registry;

    @BeforeEach
    void setUp() {
        system = new ActorSystem("page-translation-test");
        registry = new JobRegistry(system, 60_000L);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private Job<ImportOutcome> run(PageTranslationJob work) throws Exception {
        Job<ImportOutcome> job = registry.submit("translate", "page", work::run, System.currentTimeMillis());
        long deadline = System.currentTimeMillis() + 10_000;
        while (!job.isTerminal() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(job.isTerminal(), "job did not finish");
        return job;
    }

    private static PageTranslationJob.Block block(String text) {
        return new PageTranslationJob.Block(text, "Japanese");
    }

    private String cached(TranslationCache cache, String text) {
        return cache.get(TranslationCache.key(text, "Japanese"));
    }

    @Test
    void everyBlockIsTranslatedAndCached() throws Exception {
        TranslationCache cache = TranslationCache.load(dir);
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        PageTranslationJob work = new PageTranslationJob(List.of(block("one"), block("two"), block("three")), cache,
            (text, lang) -> { asked.add(text); return CompletableFuture.completedFuture("T:" + text); });

        Job<ImportOutcome> job = run(work);

        assertEquals(Job.State.DONE, job.state());
        assertEquals(3, job.done());
        assertEquals(3, job.total());
        assertEquals("T:one", cached(cache, "one"));
        assertEquals("T:three", cached(cache, "three"));
        assertEquals(List.of("one", "two", "three"), asked);
    }

    @Test
    void cachedBlocksAreNotAskedAgain() throws Exception {
        TranslationCache cache = TranslationCache.load(dir);
        cache.put(TranslationCache.key("one", "Japanese"), "already");
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        PageTranslationJob work = new PageTranslationJob(List.of(block("one"), block("two")), cache,
            (text, lang) -> { asked.add(text); return CompletableFuture.completedFuture("T:" + text); });

        Job<ImportOutcome> job = run(work);

        assertEquals(List.of("two"), asked);
        assertEquals("already", cached(cache, "one"));
        assertEquals(2, job.done());
    }

    @Test
    void sameTextTwiceIsAskedOnce() throws Exception {
        TranslationCache cache = TranslationCache.load(dir);
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        PageTranslationJob work = new PageTranslationJob(List.of(block("same"), block("other"), block("same")), cache,
            (text, lang) -> { asked.add(text); return CompletableFuture.completedFuture("T:" + text); });

        Job<ImportOutcome> job = run(work);

        assertEquals(List.of("same", "other"), asked);
        assertEquals(3, job.done());
    }

    @Test
    void aFailedBlockIsLeftUncachedAndTheRestGoOn() throws Exception {
        TranslationCache cache = TranslationCache.load(dir);
        PageTranslationJob work = new PageTranslationJob(List.of(block("good"), block("bad"), block("fine")), cache,
            (text, lang) -> text.equals("bad")
                ? CompletableFuture.<String>failedFuture(new java.io.IOException("no"))
                : CompletableFuture.completedFuture("T:" + text));

        Job<ImportOutcome> job = run(work);

        assertEquals(Job.State.DONE, job.state());
        assertEquals(3, job.done());
        assertNull(cached(cache, "bad"));
        assertEquals("T:fine", cached(cache, "fine"));
        assertEquals("1 block(s) failed", job.phase());
    }

    @Test
    void aBlockThatFinishesLaterIsWaitedFor() throws Exception {
        TranslationCache cache = TranslationCache.load(dir);
        CompletableFuture<String> slow = new CompletableFuture<>();
        PageTranslationJob work = new PageTranslationJob(List.of(block("slow")), cache, (text, lang) -> slow);
        Job<ImportOutcome> job = registry.submit("translate", "page", work::run, System.currentTimeMillis());

        Thread.sleep(300);
        assertFalse(job.isTerminal(), "the job must wait for the broker's answer");
        slow.complete("done now");
        long deadline = System.currentTimeMillis() + 10_000;
        while (!job.isTerminal() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(Job.State.DONE, job.state());
        assertEquals("done now", cached(cache, "slow"));
    }

    @Test
    void stoppingEndsTheJobWithoutWaitingForTheBroker() throws Exception {
        TranslationCache cache = TranslationCache.load(dir);
        PageTranslationJob work = new PageTranslationJob(List.of(block("never")), cache,
            (text, lang) -> new CompletableFuture<>());
        Job<ImportOutcome> job = registry.submit("translate", "page", work::run, System.currentTimeMillis());

        Thread.sleep(200);
        registry.stop(job.id());
        long deadline = System.currentTimeMillis() + 10_000;
        while (!job.isTerminal() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(Job.State.CANCELLED, job.state());
        assertNull(cached(cache, "never"));
    }
}
