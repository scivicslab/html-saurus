package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ChatAskLedger}: the requests {@link ChatAsk} sent, as a POJO and as the leaf actor everyone reaches it by. */
class ChatAskLedgerTest {

    private ActorSystem system;
    private ActorRef<ChatAskLedger> actor;

    @BeforeEach
    void open() {
        system = new ActorSystem("chat-ask-ledger-test");
        actor = ChatAskLedger.host(system);
    }

    @AfterEach
    void close() {
        system.terminate();
    }

    @Test
    void pojo_aRequestIsOpenUnansweredThenAnsweredOnce() {
        ChatAskLedger l = new ChatAskLedger();
        String id = l.open(1_000);
        assertEquals("revise-1000-1", id);
        assertFalse(l.answer(id).answered());
        assertTrue(l.accept(id, "r", "whole"));
        assertEquals(new ChatAsk.Answer(true, "r", "whole"), l.answer(id));
        assertEquals("revise-1000-2", l.open(1_000));
    }

    @Test
    void pojo_aReplyToARequestNeverSentIsRefused_andAWithdrawnRequestIsGone() {
        ChatAskLedger l = new ChatAskLedger();
        assertFalse(l.accept("nope", "r", "t"));
        assertNull(l.answer("nope"));
        String id = l.open(1);
        l.withdraw(id);
        assertNull(l.answer(id));
        assertFalse(l.accept(id, "r", "t"));
    }

    @Test
    void ask_ofManyVirtualThreads_givesEveryRequestItsOwnId() throws Exception {
        List<CompletableFuture<String>> ids = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            CompletableFuture<String> id = new CompletableFuture<>();
            ids.add(id);
            Thread.ofVirtual().start(() -> id.complete(actor.ask(l -> l.open(5)).join()));
        }
        Set<String> distinct = new HashSet<>();
        for (var f : ids) distinct.add(f.get(10, TimeUnit.SECONDS));
        assertEquals(64, distinct.size());
    }

    @Test
    void tell_isServedInOrder_soAWithdrawSentAfterTheOpenLeavesNothing() {
        String id = actor.ask(l -> l.open(1)).join();
        actor.tell(l -> l.accept(id, "r", "t"));
        actor.tell(l -> l.withdraw(id));
        assertNull(actor.ask(l -> l.answer(id)).join());
    }

    @Test
    void ask_runsOnTheActorsVirtualThread() {
        AtomicReference<Thread> ran = new AtomicReference<>();
        actor.ask(l -> { ran.set(Thread.currentThread()); return 0; }).join();
        assertNotSame(Thread.currentThread(), ran.get());
        assertTrue(ran.get().isVirtual());
    }

    @Test
    void theLedgerIsALeaf_noFieldHoldsAnActorOrAFuture() {
        for (var f : ChatAskLedger.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            assertFalse(f.getType().getName().startsWith("com.scivicslab.pojoactor.")
                    || java.util.concurrent.Future.class.isAssignableFrom(f.getType()), f.getName());
        }
    }
}
