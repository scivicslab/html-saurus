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

import static org.junit.jupiter.api.Assertions.*;

/** {@link McpSessions}: the numbering of MCP sessions and the newest one, as a POJO and as a leaf actor. */
class McpSessionsTest {

    private ActorSystem system;
    private ActorRef<McpSessions> actor;

    @BeforeEach
    void open() {
        system = new ActorSystem("mcp-sessions-test");
        actor = McpSessions.host(system);
    }

    @AfterEach
    void close() {
        system.terminate();
    }

    @Test
    void pojo_numbersSessionsFromOne_andTheNewestIsTheOneOpenedLast() {
        McpSessions s = new McpSessions();
        assertNull(s.newest());
        assertEquals("htmlsaurus-1", s.open());
        assertEquals("htmlsaurus-2", s.open());
        // The map this replaced named an arbitrary one of the sessions; the newest is what the header should carry.
        for (int i = 0; i < 20; i++) s.open();
        assertEquals("htmlsaurus-22", s.newest());
    }

    @Test
    void ask_ofManyVirtualThreads_opensEverySessionWithItsOwnId() throws Exception {
        List<CompletableFuture<String>> ids = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            CompletableFuture<String> id = new CompletableFuture<>();
            ids.add(id);
            Thread.ofVirtual().start(() -> id.complete(actor.ask(McpSessions::open).join()));
        }
        Set<String> distinct = new HashSet<>();
        for (var f : ids) distinct.add(f.get(10, TimeUnit.SECONDS));
        assertEquals(64, distinct.size());
        assertTrue(actor.ask(McpSessions::newest).join().startsWith("htmlsaurus-"));
    }

    @Test
    void ask_runsOnTheActorsVirtualThread() {
        assertTrue(actor.ask(s -> Thread.currentThread().isVirtual()).join());
    }

    @Test
    void theSessionsAreALeaf_noFieldHoldsAnActorOrAFuture() {
        for (var f : McpSessions.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            assertFalse(f.getType().getName().startsWith("com.scivicslab.pojoactor.")
                    || java.util.concurrent.Future.class.isAssignableFrom(f.getType()), f.getName());
        }
    }
}
