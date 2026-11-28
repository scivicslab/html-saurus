package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@link PageProgress} as a POJO, and as the actor a page translation's callbacks send to. */
class PageProgressTest {

    @TempDir
    Path dir;

    private ActorSystem system;
    private TranslationCache cache;

    @BeforeEach
    void open() {
        system = new ActorSystem("page-progress-test");
        cache = TranslationCache.load(dir);
    }

    @AfterEach
    void close() {
        system.terminate();
    }

    private ActorRef<PageProgress> host(int total) {
        return PageProgress.host(system, cache, total, new String[1]);
    }

    @Test
    void pojo_aCachedBlockIsFinishedAtOnce() {
        cache.put("k", "T");
        PageProgress p = new PageProgress(cache, 2);
        assertEquals(PageProgress.Admission.CACHED, p.admit("k"));
        assertEquals(new PageProgress.Counts(1, 0, 2), p.counts());
    }

    @Test
    void pojo_theSameTextIsTranslatedOnce_andFinishesEveryBlockThatWaitedForIt() {
        PageProgress p = new PageProgress(cache, 3);
        assertEquals(PageProgress.Admission.TRANSLATE, p.admit("same"));
        assertEquals(PageProgress.Admission.TRANSLATE, p.admit("other"));
        assertEquals(PageProgress.Admission.SHARED, p.admit("same"));
        p.settle("same", "S");
        assertEquals(new PageProgress.Counts(2, 0, 3), p.counts());
        assertEquals("S", cache.get("same"));
        p.settle("other", "O");
        assertTrue(p.counts().complete());
    }

    @Test
    void pojo_aFailedTranslationIsNotCached_andCountsEveryBlockThatWaitedForIt() {
        PageProgress p = new PageProgress(cache, 2);
        p.admit("bad");
        p.admit("bad");
        p.settle("bad", null);
        assertNull(cache.get("bad"));
        assertEquals(new PageProgress.Counts(2, 2, 2), p.counts());
    }

    @Test
    void tell_isServedInOrder_soAnAskAfterItSeesEverySettle() {
        ActorRef<PageProgress> actor = host(100);
        for (int i = 0; i < 100; i++) {
            String key = "k" + i;
            actor.tell(p -> p.admit(key));
            actor.tell(p -> p.settle(key, "T"));
        }
        assertEquals(new PageProgress.Counts(100, 0, 100), actor.ask(PageProgress::counts).join());
    }

    @Test
    void ask_runsOnTheActorsVirtualThread() {
        AtomicReference<Thread> served = new AtomicReference<>();
        host(1).ask(p -> { served.set(Thread.currentThread()); return 0; }).join();
        assertNotSame(Thread.currentThread(), served.get());
        assertTrue(served.get().isVirtual());
    }

    @Test
    void ofManyVirtualThreadsAdmittingTheSameText_exactlyOneTranslatesIt() throws Exception {
        ActorRef<PageProgress> actor = host(64);
        List<CompletableFuture<PageProgress.Admission>> answers = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            CompletableFuture<PageProgress.Admission> answer = new CompletableFuture<>();
            answers.add(answer);
            Thread.ofVirtual().start(() -> answer.complete(actor.ask(p -> p.admit("same")).join()));
        }
        int translators = 0;
        for (var a : answers) if (a.get(10, TimeUnit.SECONDS) == PageProgress.Admission.TRANSLATE) translators++;
        assertEquals(1, translators);
        actor.ask(p -> { p.settle("same", "S"); return 0; }).join();
        assertEquals(new PageProgress.Counts(64, 0, 64), actor.ask(PageProgress::counts).join());
    }

    @Test
    void theProgressIsALeaf_noFieldOrMethodHoldsAnActorOrAWaiter() {
        for (var f : PageProgress.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            assertFalse(isActorOrWaiter(f.getType()), f.getName());
        }
        for (var m : PageProgress.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            for (Class<?> p : m.getParameterTypes()) assertFalse(isActorOrWaiter(p), m.getName());
        }
    }

    private static boolean isActorOrWaiter(Class<?> t) {
        return t.getName().startsWith("com.scivicslab.pojoactor.")
                || java.util.concurrent.Future.class.isAssignableFrom(t);
    }
}
