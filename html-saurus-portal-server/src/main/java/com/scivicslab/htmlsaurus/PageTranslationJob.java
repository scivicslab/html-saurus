package com.scivicslab.htmlsaurus;

import com.scivicslab.jobregistry.Job;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.util.List;
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

    private final List<Block> blocks;
    private final TranslationCache cache;
    private final BlockTranslator translator;
    private final ActorSystem system;

    PageTranslationJob(List<Block> blocks, TranslationCache cache, BlockTranslator translator, ActorSystem system) {
        this.blocks = blocks;
        this.cache = cache;
        this.translator = translator;
        this.system = system;
    }

    /** Set by every translation that ends; the job's loop swaps it before it reads the counts, so no end is missed. */
    private volatile CompletableFuture<Void> wake = new CompletableFuture<>();

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

        String[] name = new String[1];
        ActorRef<PageProgress> progress = PageProgress.host(system, cache, total, name);
        try {
            for (Block block : blocks) {
                if (job.isTerminal()) {
                    return;   // stop() got here first; what is already translated stays cached
                }
                String key = TranslationCache.key(block.text(), block.lang());
                PageProgress.Admission admission = progress.ask(p -> p.admit(key)).join();
                if (admission == PageProgress.Admission.TRANSLATE) {
                    // May hold the caller: the broker's queue paces the page here, outside the actor.
                    CompletableFuture<String> future = translator.translate(block.text(), block.lang());
                    future.whenComplete((text, error) -> {
                        if (error != null) logger.log(Level.WARNING, "Page translation: a block failed", error);
                        String translated = error == null ? text : null;
                        // The next message of this actor answers after this one, so a read after the wake sees it.
                        progress.ask(p -> { p.settle(key, translated); return 0; })
                                .whenComplete((ignored, e) -> wake.complete(null));
                    });
                }
                job.progress(progress.ask(PageProgress::counts).join().finished(), total);
            }
            while (true) {
                if (job.isTerminal()) {
                    return;
                }
                wake = new CompletableFuture<>();
                PageProgress.Counts counts = progress.ask(PageProgress::counts).join();
                job.progress(counts.finished(), total);
                if (counts.complete()) {
                    job.phase(counts.failed() == 0 ? "" : counts.failed() + " block(s) failed");
                    return;
                }
                try {
                    // A stop carries no message to this job, so the wait is bounded and the stop looked for again.
                    wake.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    // look again
                } catch (ExecutionException e) {
                    // wake is only ever completed normally
                }
            }
        } finally {
            system.removeActor(name[0]);
        }
    }
}
