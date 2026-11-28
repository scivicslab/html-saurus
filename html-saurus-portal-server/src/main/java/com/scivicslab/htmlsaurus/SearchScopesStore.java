package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;

/**
 * The server-side file of search scopes. A change reads the file, edits it and writes it back as one step; hosted as an
 * actor ({@link #host}), the steps of different requests are served one at a time, which is what the
 * {@code synchronized} block around them used to guarantee.
 *
 * <p>A plain POJO that holds only the file's path. It is a leaf: it asks no actor and waits on none; a test pins this.
 * Callers use {@link #call}, which waits on the calling thread, never inside the actor.</p>
 */
final class SearchScopesStore {

    /** The name of the actor in the portal's {@link ActorSystem}. */
    static final String ACTOR = "search-scopes-store";

    /** A step on the store that may fail with a checked exception. */
    @FunctionalInterface
    interface Step<R> {
        R apply(SearchScopesStore store) throws Exception;
    }

    /** Carries a step's checked exception out of the actor's function and back to the caller. */
    private static final class Failure extends RuntimeException {
        Failure(Exception cause) {
            super(cause);
        }
    }

    private final Path file;

    SearchScopesStore(Path file) {
        this.file = file;
    }

    /** @return the scopes as the file holds them; none when the file does not exist */
    SearchScopes read() throws IOException {
        return SearchScopes.load(file);
    }

    /** Reads the scopes, lets {@code change} edit them, writes the result back and returns it. */
    SearchScopes change(Edit change) throws Exception {
        SearchScopes next = change.apply(SearchScopes.load(file));
        next.save(file);
        return next;
    }

    /** An edit of the scopes that may refuse a query. */
    @FunctionalInterface
    interface Edit {
        SearchScopes apply(SearchScopes scopes) throws Exception;
    }

    /** Puts a store on {@code file} into {@code system} as the actor named {@link #ACTOR}. */
    static ActorRef<SearchScopesStore> host(ActorSystem system, Path file) {
        return system.actorOf(ACTOR, new SearchScopesStore(file));
    }

    /**
     * Runs {@code step} on the store's actor and waits for it on the calling thread. A checked exception the step threw
     * is thrown here as it was.
     */
    static <R> R call(ActorRef<SearchScopesStore> store, Step<R> step) throws Exception {
        try {
            return store.ask(s -> {
                try {
                    return step.apply(s);
                } catch (Exception e) {
                    throw new Failure(e);
                }
            }).join();
        } catch (CompletionException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof Failure f) throw (Exception) f.getCause();
            }
            throw e;
        }
    }
}
