package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** {@link DerivedIndexes} as a POJO, and as the actor that builds and requests share. */
class DerivedIndexesTest {

    private static final Map<String, List<Map<String, String>>> BUILT = Map.of("/a/", List.of(Map.of("path", "/b/")));

    private ActorSystem system;
    private ActorRef<DerivedIndexes> actor;

    @BeforeEach
    void open() {
        system = new ActorSystem("derived-indexes-test");
        actor = DerivedIndexes.host(system);
    }

    @AfterEach
    void close() {
        system.terminate();
    }

    @Test
    void pojo_startsEmpty() {
        DerivedIndexes d = new DerivedIndexes();
        assertNull(d.semanticIndex());
        assertTrue(d.semanticRelated().isEmpty());
        assertNull(d.prerequisiteOf());
    }

    @Test
    void pojo_aNullRelatedMapIsAnEmptyOne() {
        DerivedIndexes d = new DerivedIndexes();
        d.installSemantic(null, null);
        assertTrue(d.semanticRelated().isEmpty());
    }

    @Test
    void pojo_aBuildStartedBeforeAnInvalidationIsRefused() {
        DerivedIndexes d = new DerivedIndexes();
        long generation = d.prerequisiteOfGeneration();
        d.invalidatePrerequisiteOf();
        assertFalse(d.installPrerequisiteOf(BUILT, generation));
        assertNull(d.prerequisiteOf());
        assertTrue(d.installPrerequisiteOf(BUILT, d.prerequisiteOfGeneration()));
        assertSame(BUILT, d.prerequisiteOf());
    }

    @Test
    void pojo_anInvalidationDropsTheInstalledIndex() {
        DerivedIndexes d = new DerivedIndexes();
        d.installPrerequisiteOf(BUILT, d.prerequisiteOfGeneration());
        d.invalidatePrerequisiteOf();
        assertNull(d.prerequisiteOf());
    }

    @Test
    void tell_isServedInOrder_soAnAskAfterAnInvalidationSeesNoIndex() {
        long generation = actor.ask(DerivedIndexes::prerequisiteOfGeneration).join();
        actor.tell(d -> d.installPrerequisiteOf(BUILT, generation));
        actor.tell(DerivedIndexes::invalidatePrerequisiteOf);
        assertNull(actor.ask(DerivedIndexes::prerequisiteOf).join());
        assertFalse(actor.ask(d -> d.installPrerequisiteOf(BUILT, generation)).join());
    }

    @Test
    void ask_runsOnTheActorsVirtualThread() {
        AtomicReference<Thread> served = new AtomicReference<>();
        actor.ask(d -> { served.set(Thread.currentThread()); return 0; }).join();
        assertNotSame(Thread.currentThread(), served.get());
        assertTrue(served.get().isVirtual());
    }

    @Test
    void ofManyVirtualThreadsInstallingTheSameGeneration_allAreAcceptedUntilAnInvalidation() throws Exception {
        long generation = actor.ask(DerivedIndexes::prerequisiteOfGeneration).join();
        List<CompletableFuture<Boolean>> answers = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            answers.add(answer);
            Thread.ofVirtual().start(() ->
                    answer.complete(actor.ask(d -> d.installPrerequisiteOf(BUILT, generation)).join()));
        }
        for (CompletableFuture<Boolean> a : answers) assertTrue(a.get(10, TimeUnit.SECONDS));
        actor.tell(DerivedIndexes::invalidatePrerequisiteOf);
        assertFalse(actor.ask(d -> d.installPrerequisiteOf(BUILT, generation)).join());
    }

    @Test
    void theIndexesAreALeaf_noFieldOrMethodHoldsAnActorOrAWaiter() {
        for (var f : DerivedIndexes.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            assertFalse(isActorOrWaiter(f.getType()), f.getName());
        }
        for (var m : DerivedIndexes.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            for (Class<?> p : m.getParameterTypes()) assertFalse(isActorOrWaiter(p), m.getName());
        }
    }

    private static boolean isActorOrWaiter(Class<?> t) {
        return t.getName().startsWith("com.scivicslab.pojoactor.")
                || java.util.concurrent.Future.class.isAssignableFrom(t);
    }
}
