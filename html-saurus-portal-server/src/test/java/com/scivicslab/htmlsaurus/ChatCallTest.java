package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ChatCall}: the blocking question runs on an actor's virtual thread, and questions run side by side. */
class ChatCallTest {

    private ActorSystem system;

    @BeforeEach
    void open() {
        system = new ActorSystem("chat-call-test");
    }

    @AfterEach
    void close() {
        system.terminate();
    }

    @Test
    void theCallRunsOnTheActorsVirtualThread_andItsAnswerIsTheAsksResult() {
        AtomicReference<Thread> ran = new AtomicReference<>();
        ActorRef<ChatCall> call = system.actorOf("c1", new ChatCall((s, u, t) -> {
            ran.set(Thread.currentThread());
            return s + "|" + u + "|" + t;
        }));
        assertEquals("S|U|7", call.ask(c -> c.chat("S", "U", 7)).join());
        assertNotSame(Thread.currentThread(), ran.get());
        assertTrue(ran.get().isVirtual());
    }

    @Test
    void anIoFailureFailsTheAskWithThatCause() {
        ActorRef<ChatCall> call = system.actorOf("c2", new ChatCall((s, u, t) -> { throw new IOException("down"); }));
        CompletionException e = assertThrows(CompletionException.class, () -> call.ask(c -> c.chat("S", "U", 1)).join());
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        assertInstanceOf(IOException.class, root);
        assertEquals("down", root.getMessage());
    }

    @Test
    void questionsOfDifferentActorsRunAtTheSameTime() throws Exception {
        int n = 8;
        CountDownLatch allIn = new CountDownLatch(n);
        List<CompletableFuture<String>> answers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ActorRef<ChatCall> call = system.actorOf("q" + i, new ChatCall((s, u, t) -> {
                allIn.countDown();
                // Held until all eight are inside: they could not be, one after another.
                if (!allIn.await(10, TimeUnit.SECONDS)) throw new IOException("not side by side");
                return "ok";
            }));
            answers.add(call.ask(c -> c.chat("S", "U", 1)));
        }
        for (var a : answers) assertEquals("ok", a.get(15, TimeUnit.SECONDS));
    }

    @Test
    void theCallIsALeaf_noFieldOrMethodHoldsAnActorOrAWaiter() {
        for (var f : ChatCall.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            assertFalse(isActorOrWaiter(f.getType()), f.getName());
        }
        for (var m : ChatCall.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            for (Class<?> p : m.getParameterTypes()) assertFalse(isActorOrWaiter(p), m.getName());
        }
    }

    private static boolean isActorOrWaiter(Class<?> t) {
        return t.getName().startsWith("com.scivicslab.pojoactor.")
                || java.util.concurrent.Future.class.isAssignableFrom(t);
    }
}
