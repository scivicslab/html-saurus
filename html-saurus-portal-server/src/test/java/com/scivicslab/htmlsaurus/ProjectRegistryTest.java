package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ProjectRegistry} as a POJO, and as the actor that scans and requests share. */
class ProjectRegistryTest {

    private ActorSystem system;
    private ActorRef<ProjectRegistry> actor;

    @BeforeEach
    void open() {
        system = new ActorSystem("project-registry-test");
        actor = ProjectRegistry.host(system);
    }

    @AfterEach
    void close() {
        system.terminate();
    }

    private static PortalServer.Project project(String name) {
        Path dir = Path.of("/works", name);
        return new PortalServer.Project(name, dir, dir.resolve("static-html"), dir.resolve("search-index"), "ja");
    }

    @Test
    void pojo_addsOncePerName_andSortsByName() {
        ProjectRegistry r = new ProjectRegistry();
        assertTrue(r.add(project("b")));
        assertTrue(r.add(project("a")));
        assertFalse(r.add(project("a")));
        assertEquals(List.of("b", "a"), r.snapshot().stream().map(PortalServer.Project::name).toList());
        r.sortByName();
        assertEquals(List.of("a", "b"), r.snapshot().stream().map(PortalServer.Project::name).toList());
        assertEquals("b", r.project("b").name());
        assertNull(r.project("c"));
    }

    @Test
    void pojo_removalDropsTheProjectAndTheLocalesOfItsSearchers_only() {
        ProjectRegistry r = new ProjectRegistry();
        r.add(project("a"));
        r.add(project("ab"));
        r.setLocale("a", "ja");
        r.setLocale("a:en", "en");
        r.setLocale("ab", "ja");
        List<PortalServer.Project> gone = r.removeNotIn(Set.of("ab"));
        assertEquals(List.of("a"), gone.stream().map(PortalServer.Project::name).toList());
        assertNull(r.project("a"));
        assertEquals(List.of("ab"), List.copyOf(r.searcherLocales().keySet()));
    }

    @Test
    void pojo_snapshotAndLocalesAreCopies() {
        ProjectRegistry r = new ProjectRegistry();
        r.add(project("a"));
        r.setLocale("a", "ja");
        assertThrows(UnsupportedOperationException.class, () -> r.snapshot().clear());
        r.searcherLocales().clear();
        assertEquals(1, r.searcherLocales().size());
    }

    @Test
    void tell_isServedInOrder_soAnAskAfterItSeesEveryChange() {
        for (int i = 0; i < 100; i++) {
            PortalServer.Project p = project(String.format("p%03d", 99 - i));
            actor.tell(r -> r.add(p));
        }
        actor.tell(ProjectRegistry::sortByName);
        List<PortalServer.Project> all = actor.ask(ProjectRegistry::snapshot).join();
        assertEquals(100, all.size());
        assertEquals("p000", all.get(0).name());
        assertEquals("p099", all.get(99).name());
    }

    @Test
    void ask_runsOnTheActorsVirtualThread() {
        AtomicReference<Thread> served = new AtomicReference<>();
        actor.ask(r -> { served.set(Thread.currentThread()); return 0; }).join();
        assertNotSame(Thread.currentThread(), served.get());
        assertTrue(served.get().isVirtual());
    }

    @Test
    void ofManyVirtualThreadsAddingTheSameName_exactlyOneWins() throws Exception {
        List<CompletableFuture<Boolean>> answers = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            answers.add(answer);
            Thread.ofVirtual().start(() -> answer.complete(actor.ask(r -> r.add(project("same"))).join()));
        }
        int winners = 0;
        for (CompletableFuture<Boolean> a : answers) if (a.get(10, TimeUnit.SECONDS)) winners++;
        assertEquals(1, winners);
        assertEquals(1, actor.ask(ProjectRegistry::snapshot).join().size());
    }

    @Test
    void theRegistryIsALeaf_noFieldOrMethodHoldsAnActorOrAWaiter() {
        for (var f : ProjectRegistry.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            assertFalse(isActorOrWaiter(f.getType()), f.getName());
        }
        for (var m : ProjectRegistry.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            for (Class<?> p : m.getParameterTypes()) assertFalse(isActorOrWaiter(p), m.getName());
        }
    }

    private static boolean isActorOrWaiter(Class<?> t) {
        return t.getName().startsWith("com.scivicslab.pojoactor.")
                || java.util.concurrent.Future.class.isAssignableFrom(t);
    }
}
