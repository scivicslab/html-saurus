package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.ParseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SearchScopesStore}: a change reads the file, edits it and writes it back as one step, and the steps of
 * concurrent requests are served one at a time.
 */
class SearchScopesStoreTest {

    private static final StandardAnalyzer BASE = new StandardAnalyzer();

    @TempDir
    Path tempDir;

    private ActorSystem system;

    @AfterEach
    void close() {
        if (system != null) system.terminate();
    }

    private ActorRef<SearchScopesStore> hosted() {
        system = new ActorSystem("scopes-store-test");
        return SearchScopesStore.host(system, tempDir.resolve(".html-saurus").resolve("search-scopes.json"));
    }

    @Test
    void change_writesTheEditBack_andReadSeesIt() throws Exception {
        var store = hosted();
        SearchScopes after = SearchScopesStore.call(store,
                s -> s.change(c -> c.add("Books", "path_idx:\\/proj\\/Books\\/*", BASE)));
        assertEquals(1, after.categories().size());
        assertEquals(after.toJson(), SearchScopesStore.call(store, SearchScopesStore::read).toJson());
    }

    @Test
    void manyVirtualThreadsAddingAtOnce_loseNoScope() throws Exception {
        var store = hosted();
        List<CompletableFuture<Void>> done = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String name = "scope-" + i;
            CompletableFuture<Void> f = new CompletableFuture<>();
            done.add(f);
            Thread.ofVirtual().start(() -> {
                try {
                    SearchScopesStore.call(store, s -> s.change(c -> c.add(name, "path_idx:\\/proj\\/" + name + "\\/*", BASE)));
                    f.complete(null);
                } catch (Exception e) {
                    f.completeExceptionally(e);
                }
            });
        }
        for (var f : done) f.get(20, TimeUnit.SECONDS);
        assertEquals(32, SearchScopesStore.call(store, SearchScopesStore::read).categories().size(),
                "each change saw the previous one's result");
    }

    @Test
    void aCheckedExceptionOfAStepReachesTheCallerAsItWas() throws Exception {
        var store = hosted();
        assertThrows(ParseException.class,
                () -> SearchScopesStore.call(store, s -> s.change(c -> c.add("bad", "path_idx:(", BASE))));
        assertEquals(0, SearchScopesStore.call(store, SearchScopesStore::read).categories().size(),
                "a refused edit writes nothing");
        assertThrows(IOException.class, () -> SearchScopesStore.call(store, s -> { throw new IOException("disk"); }));
    }

    @Test
    void theStepRunsOnTheActorsVirtualThread_notOnTheCaller() throws Exception {
        var store = hosted();
        AtomicReference<Thread> ran = new AtomicReference<>();
        SearchScopesStore.call(store, s -> { ran.set(Thread.currentThread()); return null; });
        assertNotSame(Thread.currentThread(), ran.get());
        assertTrue(ran.get().isVirtual());
    }

    @Test
    void theStoreIsALeaf_itHoldsOnlyAPath() {
        for (var f : SearchScopesStore.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            assertEquals(Path.class, f.getType(), f.getName());
        }
    }
}
