package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ProjectBuildGate} as a POJO, as the actor builds and scans queue at, and through {@link ProjectBuildGate.Turn}. */
class ProjectBuildGateTest {

    private ActorSystem system;
    private ActorRef<ProjectBuildGate> actor;

    @BeforeEach
    void open() {
        system = new ActorSystem("project-build-gate-test");
        actor = ProjectBuildGate.host(system);
    }

    @AfterEach
    void close() {
        system.terminate();
    }

    @Test
    void pojo_theFirstHasTheTurn_theNextWaitsAndIsHandedItOnLeave() {
        ProjectBuildGate g = new ProjectBuildGate();
        assertTrue(g.enter("k", 1, () -> { }));
        Runnable second = () -> { };
        assertFalse(g.enter("k", 2, second));
        assertEquals(2, g.inLine("k"));
        assertSame(second, g.leave("k", 1));
        assertNull(g.leave("k", 2));
        assertEquals(0, g.inLine("k"));
    }

    @Test
    void pojo_keysDoNotWaitForEachOther() {
        ProjectBuildGate g = new ProjectBuildGate();
        assertTrue(g.enter("a", 1, () -> { }));
        assertTrue(g.enter("b", 2, () -> { }));
    }

    @Test
    void pojo_aWaiterThatWithdrawsIsSkipped_andDoesNotEndTheTurnOfTheHead() {
        ProjectBuildGate g = new ProjectBuildGate();
        Runnable third = () -> { };
        g.enter("k", 1, () -> { });
        g.enter("k", 2, () -> { });
        g.enter("k", 3, third);
        assertNull(g.leave("k", 2));
        assertEquals(2, g.inLine("k"));
        assertSame(third, g.leave("k", 1));
    }

    @Test
    void ask_runsOnTheActorsVirtualThread() {
        AtomicReference<Thread> served = new AtomicReference<>();
        actor.ask(g -> { served.set(Thread.currentThread()); return 0; }).join();
        assertNotSame(Thread.currentThread(), served.get());
        assertTrue(served.get().isVirtual());
    }

    @Test
    void ofManyVirtualThreadsTakingTurnsOnOneKey_neverTwoAtOnce_andAllGetTheirs() throws Exception {
        ProjectBuildGate.Turn turn = new ProjectBuildGate.Turn(actor, "one");
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        List<CompletableFuture<Void>> done = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            CompletableFuture<Void> d = new CompletableFuture<>();
            done.add(d);
            Thread.ofVirtual().start(() -> {
                try {
                    long ticket = turn.enter();
                    try {
                        most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                        Thread.sleep(2);
                        inside.decrementAndGet();
                    } finally {
                        turn.leave(ticket);
                    }
                    d.complete(null);
                } catch (Exception e) {
                    d.completeExceptionally(e);
                }
            });
        }
        for (var d : done) d.get(30, TimeUnit.SECONDS);
        assertEquals(1, most.get());
        assertEquals(0, actor.ask(g -> g.inLine("one")).join());
    }

    @Test
    void turnsOfOneKeyAreGivenInTheOrderOfAsking() throws Exception {
        ProjectBuildGate.Turn turn = new ProjectBuildGate.Turn(actor, "ordered");
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        long first = turn.enter();
        List<Thread> waiters = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int n = i;
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    long ticket = turn.enter();
                    order.add(n);
                    turn.leave(ticket);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            waiters.add(t);
            while (actor.ask(g -> g.inLine("ordered")).join() < n + 2) Thread.sleep(2);
        }
        turn.leave(first);
        for (Thread t : waiters) t.join(10_000);
        assertEquals(List.of(0, 1, 2, 3, 4), order);
    }

    @Test
    void aWaiterInterruptedWhileWaiting_givesUpItsPlace_andTheTurnPassesOn() throws Exception {
        ProjectBuildGate.Turn turn = new ProjectBuildGate.Turn(actor, "interrupt");
        long first = turn.enter();
        AtomicReference<Throwable> seen = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                turn.enter();
            } catch (InterruptedException e) {
                seen.set(e);
            }
        });
        while (actor.ask(g -> g.inLine("interrupt")).join() < 2) Thread.sleep(2);
        waiter.interrupt();
        waiter.join(10_000);
        assertInstanceOf(InterruptedException.class, seen.get());
        assertEquals(1, actor.ask(g -> g.inLine("interrupt")).join());
        turn.leave(first);
        assertEquals(0, actor.ask(g -> g.inLine("interrupt")).join());
    }

    @Test
    void theGateIsALeaf_noFieldOrMethodHoldsAnActorOrAWaiter() {
        for (var f : ProjectBuildGate.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            assertFalse(isActorOrWaiter(f.getType()), f.getName());
        }
        for (var m : ProjectBuildGate.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            for (Class<?> p : m.getParameterTypes()) assertFalse(isActorOrWaiter(p), m.getName());
        }
    }

    private static boolean isActorOrWaiter(Class<?> t) {
        return t.getName().startsWith("com.scivicslab.pojoactor.")
                || java.util.concurrent.Future.class.isAssignableFrom(t);
    }
}
