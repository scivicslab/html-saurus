package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TranslationEndpointState} as a POJO, and as the actor {@link TranslationClient} asks. The guarantees are pinned per
 * way of sending: {@code tell} and {@code ask} are served in order on the actor's own virtual thread, and the callers wait
 * on virtual threads outside it.
 */
class TranslationEndpointStateTest {

    private ActorSystem system;
    private ActorRef<TranslationEndpointState> state;

    @BeforeEach
    void open() {
        system = new ActorSystem("endpoint-state-test");
        state = TranslationEndpointState.host(system);
    }

    @AfterEach
    void close() {
        system.terminate();
    }

    @Test
    void pojo_modelIsFreshOnlyWithinItsTimeToLive() {
        TranslationEndpointState s = new TranslationEndpointState();
        assertNull(s.cachedModel(1_000, 5_000));
        s.cacheModel("m", 1_000);
        assertEquals("m", s.cachedModel(5_999, 5_000));
        assertNull(s.cachedModel(6_000, 5_000));
    }

    @Test
    void pojo_downPeriodEndsAtItsTimeAndMarkUpClearsIt() {
        TranslationEndpointState s = new TranslationEndpointState();
        assertFalse(s.isDown(0));
        s.markDown(30_000);
        assertTrue(s.isDown(29_999));
        assertFalse(s.isDown(30_000));
        s.markDown(30_000);
        s.markUp();
        assertFalse(s.isDown(1));
    }

    @Test
    void tell_isServedInOrderBeforeALaterAsk() {
        for (int i = 1; i <= 200; i++) {
            long until = i;
            state.tell(s -> s.markDown(until));
        }
        // The last tell sent (until = 200) must be the one in force when the ask is served.
        assertTrue(state.ask(s -> s.isDown(199)).join());
        assertFalse(state.ask(s -> s.isDown(200)).join());
    }

    @Test
    void ask_runsOnTheActorsVirtualThreadNotOnTheCaller() throws Exception {
        AtomicReference<Thread> served = new AtomicReference<>();
        state.ask(s -> { served.set(Thread.currentThread()); return null; }).join();
        assertNotSame(Thread.currentThread(), served.get());
        assertTrue(served.get().isVirtual(), "the actor runs on a virtual thread");
    }

    @Test
    void manyVirtualThreadsMayWaitOnTheActorAtOnce() throws Exception {
        state.tell(s -> s.cacheModel("shared", 100));
        List<Thread> waiters = new ArrayList<>();
        List<CompletableFuture<String>> answers = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            CompletableFuture<String> answer = new CompletableFuture<>();
            answers.add(answer);
            waiters.add(Thread.ofVirtual().start(() ->
                    answer.complete(state.ask(s -> s.cachedModel(101, 5_000)).join())));
        }
        for (Thread t : waiters) t.join(10_000);
        for (CompletableFuture<String> a : answers) assertEquals("shared", a.getNow("not answered"));
    }

    @Test
    void theStateIsALeaf_noMethodTakesOrHoldsAnActorOrAFuture() {
        for (Class<?> c = TranslationEndpointState.class; c != Object.class; c = c.getSuperclass()) {
            for (var f : c.getDeclaredFields()) {
                assertFalse(isActorOrWaiter(f.getType()), "field " + f.getName() + " would let the state ask or wait");
            }
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue; // host(...) is the one place that touches a system
                for (Class<?> p : m.getParameterTypes()) {
                    assertFalse(isActorOrWaiter(p), "method " + m.getName() + " takes " + p);
                }
                assertFalse(isActorOrWaiter(m.getReturnType()), "method " + m.getName() + " returns a waiter");
            }
        }
    }

    private static boolean isActorOrWaiter(Class<?> t) {
        String n = t.getName();
        return n.startsWith("com.scivicslab.pojoactor.") || java.util.concurrent.Future.class.isAssignableFrom(t)
                || java.util.concurrent.locks.Lock.class.isAssignableFrom(t)
                || java.util.concurrent.CountDownLatch.class.isAssignableFrom(t);
    }
}
