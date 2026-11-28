package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The work ledger of exports whose jobs the registry has forgotten (ExportToPdf_261119_oo01): the registry forgets
 * a job an hour after it ended, and its PDF would stay in the temporary directory until the next start.
 */
@Tag("ExportToPdf_261119_oo01")
class ExportWorkDirectoryLedgerTest {

    @TempDir
    Path tempDir;

    private Path directory(String name) throws IOException {
        Path d = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(d.resolve("proj.pdf"), "pdf");
        return d;
    }

    @Test
    void deleteOfForgottenJobs_deletesTheDirectoriesOfJobsTheRegistryNoLongerKnows_only() throws IOException {
        ExportWorkDirectoryLedger ledger = new ExportWorkDirectoryLedger();
        Path known = directory("known");
        Path forgotten = directory("forgotten");
        ledger.remember("job-known", known);
        ledger.remember("job-forgotten", forgotten);

        int deleted = ledger.deleteOfForgottenJobs(Set.of("job-known")::contains);

        assertEquals(1, deleted);
        assertTrue(Files.isDirectory(known), "the job is still listed, so its PDF can still be downloaded");
        assertFalse(Files.exists(forgotten), "the job was forgotten, nobody can download its PDF");
        assertEquals(0, ledger.deleteOfForgottenJobs(Set.of("job-known")::contains), "a directory is deleted once");
    }

    @Test
    void forget_whenAJobIsCleared_leavesItsDirectoryToTheCaller() throws IOException {
        ExportWorkDirectoryLedger ledger = new ExportWorkDirectoryLedger();
        Path cleared = directory("cleared");
        ledger.remember("job", cleared);
        ledger.forget("job");
        assertEquals(0, ledger.deleteOfForgottenJobs(id -> false));
        assertTrue(Files.isDirectory(cleared));
    }

    // ---- as an actor -----------------------------------------------------------------------------------------

    private com.scivicslab.pojoactor.core.ActorSystem system;
    private java.util.List<com.scivicslab.pojoactor.core.scheduler.Scheduler> schedulers = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void closeActors() {
        schedulers.forEach(com.scivicslab.pojoactor.core.scheduler.Scheduler::close);
        if (system != null) system.terminate();
    }

    private com.scivicslab.pojoactor.core.ActorRef<ExportWorkDirectoryLedger> hosted() {
        system = new com.scivicslab.pojoactor.core.ActorSystem("ledger-test");
        return ExportWorkDirectoryLedger.host(system);
    }

    @Test
    void tell_isServedInTheOrderSent_soALaterAskSeesEveryEarlierTell() throws IOException {
        var actor = hosted();
        for (int i = 0; i < 200; i++) {
            String id = "job-" + i;
            actor.tell(l -> l.remember(id, tempDir.resolve(id)));
            if (i % 2 == 0) actor.tell(l -> l.forget(id));
        }
        for (int i = 0; i < 200; i++) {
            String id = "job-" + i;
            assertEquals(i % 2 != 0, actor.ask(l -> l.remembers(id)).join(), id);
        }
    }

    @Test
    void ask_runsOnTheActorsVirtualThread_andACallerMayWaitOnAVirtualThread() throws Exception {
        var actor = hosted();
        java.util.concurrent.atomic.AtomicReference<Thread> served = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CompletableFuture<Boolean> done = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            actor.ask(l -> { served.set(Thread.currentThread()); return l.remembers("x"); }).join();
            done.complete(true);
        });
        assertTrue(done.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(served.get().isVirtual());
    }

    @Test
    void sweepSentToTheActor_deletesTheDirectoriesOfForgottenJobs() throws Exception {
        var actor = hosted();
        Path forgotten = directory("forgotten");
        actor.tell(l -> l.remember("job", forgotten));
        actor.tell(l -> l.sweep(id -> false));
        actor.ask(l -> l.remembers("job")).join(); // the sweep, sent before, has been served
        assertFalse(Files.exists(forgotten));
    }

    @Test
    void theScheduler_sendsTheSweepOnItsOwn() throws Exception {
        var actor = hosted();
        Path forgotten = directory("forgotten");
        actor.tell(l -> l.remember("job", forgotten));
        schedulers.add(ExportWorkDirectoryLedger.sweepEvery(actor, id -> false, 20, java.util.concurrent.TimeUnit.MILLISECONDS));
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (Files.exists(forgotten) && System.nanoTime() < end) Thread.sleep(20);
        assertFalse(Files.exists(forgotten), "the periodic sweep deleted it");
    }

    @Test
    void theLedgerIsALeaf_noFieldOrMethodHoldsAnActorOrAWaiter() {
        for (var f : ExportWorkDirectoryLedger.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            assertFalse(f.getType().getName().startsWith("com.scivicslab.pojoactor.")
                    || java.util.concurrent.Future.class.isAssignableFrom(f.getType()), f.getName());
        }
        for (var m : ExportWorkDirectoryLedger.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(p.getName().startsWith("com.scivicslab.pojoactor.")
                        || java.util.concurrent.Future.class.isAssignableFrom(p), m.getName());
            }
        }
    }
}
