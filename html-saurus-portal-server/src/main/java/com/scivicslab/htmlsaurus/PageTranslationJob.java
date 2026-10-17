package com.scivicslab.htmlsaurus;

import com.scivicslab.jobregistry.Job;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The work of translating one page: every block the browser sent, translated and kept in
 * {@link TranslationCache}.
 *
 * <p>Runs as a {@code JobRegistry.Work} on an actor of its own, like {@link PdfImportJob}, so
 * closing the tab does not stop it and the Batch Job list shows how far it has got. The browser
 * reads the result from the cache, not from this job: a block that was already cached costs
 * nothing, and a block translated here is there for the next page that contains the same text.
 *
 * <p>Blocks are started one after another through {@link BlockTranslator}. With
 * quarkus-gpu-broker that call holds while the queue has its limit of jobs outstanding, so the
 * page is paced by the queue ({@code GpuBrokerClient}) rather than sent all at once. A block that
 * fails is counted and the rest go on; the cache simply has no entry for it.
 */
class PageTranslationJob {

    private static final Logger logger = Logger.getLogger(PageTranslationJob.class.getName());

    /** One block of the page: its text and the language to translate it into. */
    record Block(String text, String lang) {}

    /** Starts translating one block. May block the caller to pace it; the future completes with the
     *  translation, or exceptionally when this block cannot be translated. */
    @FunctionalInterface
    interface BlockTranslator {
        CompletableFuture<String> translate(String text, String lang) throws Exception;
    }

    private record Pending(String key, CompletableFuture<String> future) {}

    private final List<Block> blocks;
    private final TranslationCache cache;
    private final BlockTranslator translator;

    PageTranslationJob(List<Block> blocks, TranslationCache cache, BlockTranslator translator) {
        this.blocks = blocks;
        this.cache = cache;
        this.translator = translator;
    }

    /**
     * Translates every block that is not cached yet.
     *
     * @param job the registry's handle on this run, used to report progress and to notice a stop
     * @throws Exception to fail the job; the registry records the message
     */
    void run(Job<ImportOutcome> job) throws Exception {
        int total = blocks.size();
        job.progress(0, total);
        job.result(ImportOutcome.NOTHING_YET);
        job.phase("Translating");

        Deque<Pending> pending = new ArrayDeque<>();
        Map<String, CompletableFuture<String>> inFlight = new HashMap<>();
        int[] finished = {0};
        int[] failed = {0};

        for (Block block : blocks) {
            if (job.isTerminal()) {
                return;   // stop() got here first; what is already translated stays cached
            }
            String key = TranslationCache.key(block.text(), block.lang());
            if (cache.get(key) != null) {
                finished[0]++;
                job.progress(finished[0], total);
                continue;
            }
            // The same text twice on a page is translated once; both blocks wait for that one.
            CompletableFuture<String> future = inFlight.get(key);
            if (future == null) {
                future = translator.translate(block.text(), block.lang());
                inFlight.put(key, future);
            }
            pending.add(new Pending(key, future));
            settleDone(pending, finished, failed, job, total);
        }
        while (!pending.isEmpty()) {
            if (job.isTerminal()) {
                return;
            }
            try {
                pending.peekFirst().future().get(1, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                continue;
            } catch (ExecutionException e) {
                // counted by settleDone below
            }
            settleDone(pending, finished, failed, job, total);
        }
        job.phase(failed[0] == 0 ? "" : failed[0] + " block(s) failed");
    }

    /** Takes the blocks at the head that have finished off the queue, caching what was translated. */
    private void settleDone(Deque<Pending> pending, int[] finished, int[] failed, Job<ImportOutcome> job, int total) {
        while (!pending.isEmpty() && pending.peekFirst().future().isDone()) {
            Pending p = pending.pollFirst();
            try {
                cache.put(p.key(), p.future().join());
            } catch (RuntimeException e) {
                failed[0]++;
                logger.log(Level.WARNING, "Page translation: a block failed", e);
            }
            finished[0]++;
            job.progress(finished[0], total);
        }
    }
}
