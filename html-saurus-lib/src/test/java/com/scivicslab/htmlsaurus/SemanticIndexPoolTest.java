package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ManagedThreadPool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins where {@link SemanticIndex#computeNeighbours} runs its work: on the threads of the {@link ManagedThreadPool} it is
 * given, never on the common fork-join pool, and the same result whichever thread waits.
 */
class SemanticIndexPoolTest {

    /** A pool that notes every thread a task runs on. */
    private static final class RecordingPool extends ManagedThreadPool {
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();

        RecordingPool(int parallelism) {
            super(parallelism);
        }

        @Override
        public void execute(Runnable command) {
            super.execute(() -> {
                threads.add(Thread.currentThread());
                command.run();
            });
        }
    }

    private static List<SemanticIndex.Entry> entries(int count) {
        Random rnd = new Random(7L);
        List<SemanticIndex.Entry> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float[] v = {rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()};
            out.add(new SemanticIndex.Entry("p", new SemanticIndexer.DocVec(
                    "/d" + i + ".html", "T" + i, i % 2 == 0 ? "ja" : "en", "s", "id" + i, "/d" + i + ".md", v)));
        }
        return out;
    }

    @Test
    void theWorkRunsOnTheThreadsOfTheGivenPoolAndTheCallerKeepsItsPool() {
        RecordingPool pool = new RecordingPool(3);
        try {
            int[][] result = SemanticIndex.computeNeighbours(entries(120), 4, pool);
            assertEquals(120, result.length);
            assertFalse(pool.threads.isEmpty());
            for (Thread t : pool.threads) {
                assertFalse(t == Thread.currentThread(), "the caller only waits");
                assertFalse(t.isVirtual());
                assertFalse(t instanceof java.util.concurrent.ForkJoinWorkerThread, "ran on " + t.getName());
            }
            assertTrue(pool.getCompletedTaskCount() > 0);
            assertFalse(pool.isShutdown(), "a pool the caller gave is the caller's to shut down");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void theResultDoesNotDependOnHowManyThreadsTheWorkIsSpreadOver() {
        List<SemanticIndex.Entry> es = entries(97);
        int[][] expected;
        ManagedThreadPool one = new ManagedThreadPool(1);
        try {
            expected = SemanticIndex.computeNeighbours(es, 5, one);
        } finally {
            one.shutdown();
        }
        for (int threads : new int[]{2, 5, 16}) {
            ManagedThreadPool pool = new ManagedThreadPool(threads);
            try {
                int[][] got = SemanticIndex.computeNeighbours(es, 5, pool);
                for (int i = 0; i < expected.length; i++) {
                    assertArrayEquals(expected[i], got[i], threads + " threads, entry " + i);
                }
            } finally {
                pool.shutdown();
            }
        }
    }

    @Test
    void aVirtualThreadMayWaitForTheWork() throws Exception {
        List<SemanticIndex.Entry> es = entries(60);
        ManagedThreadPool pool = new ManagedThreadPool(2);
        try {
            int[][] expected = SemanticIndex.computeNeighbours(es, 3, pool);
            AtomicReference<int[][]> got = new AtomicReference<>();
            Thread t = Thread.ofVirtual().start(() -> got.set(SemanticIndex.computeNeighbours(es, 3, pool)));
            t.join(60_000);
            assertFalse(t.isAlive());
            for (int i = 0; i < expected.length; i++) {
                assertArrayEquals(expected[i], got.get()[i]);
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aFailureInsideATaskReachesTheCaller() {
        List<SemanticIndex.Entry> es = entries(10);
        ManagedThreadPool pool = new ManagedThreadPool(2);
        try {
            assertThrows(IllegalStateException.class, () -> SemanticIndex.computeNeighbours(es, -1, pool));   // a negative K fails inside a task
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void noEntriesNeedNoTask() {
        RecordingPool pool = new RecordingPool(2);
        try {
            assertEquals(0, SemanticIndex.computeNeighbours(List.of(), 3, pool).length);
            assertTrue(pool.threads.isEmpty());
        } finally {
            pool.shutdown();
        }
    }
}
