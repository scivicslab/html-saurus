package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ActivityMonitor} as a POJO, and as the actor the portal's request threads send to. */
class ActivityMonitorTest {

    private ActorSystem system;
    private ActorRef<ActivityMonitor> actor;

    @BeforeEach
    void open() {
        system = new ActorSystem("activity-monitor-test");
        actor = ActivityMonitor.host(system);
    }

    @AfterEach
    void close() {
        system.terminate();
    }

    @Test
    void pojo_keepsNewestFirst_oneEntryPerDocument_andAtMostTheLimit() {
        ActivityMonitor m = new ActivityMonitor();
        m.noteServed("/a/", "A", 1);
        m.noteServed("/b/", "B", 2);
        m.noteServed("/a/", "A again", 3);
        assertEquals(List.of("/a/", "/b/"), m.recent().stream().map(ActivityMonitor.Served::path).toList());
        assertEquals("A again", m.recent().get(0).title());
        for (int i = 0; i < 50; i++) m.noteServed("/p" + i + "/", "P" + i, 10 + i);
        assertEquals(ActivityMonitor.SERVED_LIMIT, m.recent().size());
        assertEquals("/p49/", m.recent().get(0).path());
    }

    @Test
    void pojo_aBlankTitleIsNotRemembered() {
        ActivityMonitor m = new ActivityMonitor();
        m.noteServed("/a/", " ", 1);
        m.noteServed("/b/", null, 2);
        assertTrue(m.recent().isEmpty());
    }

    @Test
    void pojo_recentIsACopy() {
        ActivityMonitor m = new ActivityMonitor();
        m.noteServed("/a/", "A", 1);
        m.recent().clear();
        assertEquals(1, m.recent().size());
    }

    @Test
    void pojo_theTranslationCallIsClaimedOncePerInterval() {
        ActivityMonitor m = new ActivityMonitor();
        assertTrue(m.claimTranslation(1_000, 500));
        assertFalse(m.claimTranslation(1_499, 500));
        assertTrue(m.claimTranslation(1_500, 500));
    }

    @Test
    void tell_isServedInOrder_soAnAskAfterItSeesEveryNote() {
        for (int i = 0; i < 100; i++) {
            String path = "/p" + i + "/";
            long at = i;
            actor.tell(m -> m.noteServed(path, "T", at));
        }
        List<ActivityMonitor.Served> recent = actor.ask(ActivityMonitor::recent).join();
        assertEquals(ActivityMonitor.SERVED_LIMIT, recent.size());
        assertEquals("/p99/", recent.get(0).path());
        assertEquals("/p80/", recent.get(ActivityMonitor.SERVED_LIMIT - 1).path());
    }

    @Test
    void ask_runsOnTheActorsVirtualThread() {
        AtomicReference<Thread> served = new AtomicReference<>();
        actor.ask(m -> { served.set(Thread.currentThread()); return 0; }).join();
        assertNotSame(Thread.currentThread(), served.get());
        assertTrue(served.get().isVirtual());
    }

    @Test
    void ofManyVirtualThreadsAskingAtOnce_exactlyOneClaimsTheTranslationCall() throws Exception {
        List<CompletableFuture<Boolean>> answers = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            answers.add(answer);
            Thread.ofVirtual().start(() ->
                    answer.complete(actor.ask(m -> m.claimTranslation(5_000, 60_000)).join()));
        }
        int winners = 0;
        for (CompletableFuture<Boolean> a : answers) if (a.get(10, TimeUnit.SECONDS)) winners++;
        assertEquals(1, winners);
    }

    @Test
    void theMonitorIsALeaf_noFieldOrMethodHoldsAnActorOrAWaiter() {
        for (var f : ActivityMonitor.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            assertFalse(isActorOrWaiter(f.getType()), f.getName());
        }
        for (var m : ActivityMonitor.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            for (Class<?> p : m.getParameterTypes()) assertFalse(isActorOrWaiter(p), m.getName());
        }
    }

    private static boolean isActorOrWaiter(Class<?> t) {
        return t.getName().startsWith("com.scivicslab.pojoactor.")
                || java.util.concurrent.Future.class.isAssignableFrom(t);
    }
}
