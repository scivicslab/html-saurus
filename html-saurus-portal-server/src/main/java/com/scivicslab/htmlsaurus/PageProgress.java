package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bookkeeping of one page translation ({@link PageTranslationJob}): which blocks are being translated, which blocks
 * wait for the same text, and how many are finished or failed.
 *
 * <p>A plain POJO, hosted as an actor ({@link #host}) so that the job's thread and the callbacks that translations
 * complete on all reach it as messages. It is a leaf: it asks no actor and waits on none, and it holds no future; a test
 * pins this. The waiting is done by the job, outside the actor.</p>
 */
final class PageProgress {

    /** What the job has to do with one block. */
    enum Admission {
        /** The text is cached already; the block is finished. */
        CACHED,
        /** The same text is being translated; the block is finished with it. */
        SHARED,
        /** Nobody is translating the text yet; the caller starts it. */
        TRANSLATE
    }

    /** How far the page has got. */
    record Counts(int finished, int failed, int total) {
        boolean complete() { return finished >= total; }
    }

    private static final AtomicLong NAMES = new AtomicLong();

    private final TranslationCache cache;
    private final int total;
    /** For each text being translated, how many blocks of the page wait for it. */
    private final Map<String, Integer> waiting = new HashMap<>();
    private int finished;
    private int failed;

    PageProgress(TranslationCache cache, int total) {
        this.cache = cache;
        this.total = total;
    }

    /** Decides what to do with a block whose cache key is {@code key}, and counts it when it needs no work. */
    Admission admit(String key) {
        if (cache.get(key) != null) {
            finished++;
            return Admission.CACHED;
        }
        Integer n = waiting.get(key);
        if (n != null) {
            waiting.put(key, n + 1);
            return Admission.SHARED;
        }
        waiting.put(key, 1);
        return Admission.TRANSLATE;
    }

    /**
     * Records the end of one translation: caches it, or counts every block that waited for it as failed.
     *
     * @param translated the translation, or {@code null} when it failed
     */
    void settle(String key, String translated) {
        int blocks = waiting.getOrDefault(key, 0);
        waiting.remove(key);
        if (translated != null) cache.put(key, translated);
        else failed += blocks;
        finished += blocks;
    }

    Counts counts() {
        return new Counts(finished, failed, total);
    }

    /** Puts one page's bookkeeping into {@code system} under a name of its own; remove it with {@code removeActor}. */
    static ActorRef<PageProgress> host(ActorSystem system, TranslationCache cache, int total, String[] nameOut) {
        String name = "page-progress-" + NAMES.incrementAndGet();
        nameOut[0] = name;
        return system.actorOf(name, new PageProgress(cache, total));
    }
}
