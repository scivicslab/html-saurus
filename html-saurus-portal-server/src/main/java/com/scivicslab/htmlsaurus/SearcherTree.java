package com.scivicslab.htmlsaurus;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * The portal's searchers, held in one place: the children of the actor {@value #ROOT}
 * (ActorNodesForWorkspaceAndPortal_261118_oo01, step 5a).
 *
 * <p>Each searcher is a {@link LuceneSearcher} actor named by its key, {@code <project>} or {@code <project>:<locale>},
 * created with {@code createChild} under {@value #ROOT}. {@link #view()} is the map every reader uses: a read-only
 * view computed from the children, in the order they were put, so nothing keeps a second list of their
 * references. Changes run as messages to {@value #ROOT}, one at a time.</p>
 */
final class SearcherTree {

    /** The name of the parent of every searcher actor. */
    static final String ROOT = "searchers";

    private final ActorSystem system;
    private final ActorRef<Searchers> root;
    private final Map<String, ActorRef<LuceneSearcher>> view = new View();

    SearcherTree(ActorSystem system) {
        this.system = system;
        this.root = system.actorOf(ROOT, new Searchers());
    }

    /**
     * Puts {@code searcher} under {@code key}. A searcher already under that key is closed first: its Lucene handles
     * on its own actor thread, then its actor.
     *
     * @return the new searcher's actor
     */
    ActorRef<LuceneSearcher> put(String key, LuceneSearcher searcher) {
        return inRoot(() -> {
            if (root.getNamesOfChildren().contains(key)) closeChild(key);
            return root.createChild(key, searcher);
        });
    }

    /** Closes the searcher under {@code key}, if there is one: its Lucene handles, then its actor. */
    void close(String key) {
        inRoot(() -> {
            if (root.getNamesOfChildren().contains(key)) closeChild(key);
            return null;
        });
    }

    /** The searchers by key, in the order they were put. Read-only; it follows the tree as it changes. */
    Map<String, ActorRef<LuceneSearcher>> view() {
        return view;
    }

    /**
     * Closes one searcher. The Lucene close runs as a message to the searcher, because {@link LuceneSearcher} is
     * unsynchronized and owned by its actor; stopping the actor first would cancel that message and leak the open
     * reader. Then the actor is closed and taken out of the tree ({@code close} does that from pojo-actor 4.6.0; the
     * removals after it make sure of it whatever the version).
     */
    private void closeChild(String key) {
        ActorRef<LuceneSearcher> ref = system.getActor(key);
        if (ref != null) {
            try {
                ref.ask(searcher -> {
                    searcher.close();
                    return Boolean.TRUE;
                }).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Could not close the searcher " + key + ": " + e.getMessage());
            }
            ref.close();
        }
        system.removeActor(key);
        root.getNamesOfChildren().remove(key);
    }

    private <R> R inRoot(Supplier<R> work) {
        try {
            return root.ask(s -> work.get()).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    /** A read-only map over the children of {@value #ROOT}. */
    private final class View extends AbstractMap<String, ActorRef<LuceneSearcher>> {
        @Override
        public ActorRef<LuceneSearcher> get(Object key) {
            return key instanceof String k && root.getNamesOfChildren().contains(k) ? system.getActor(k) : null;
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof String k && root.getNamesOfChildren().contains(k);
        }

        @Override
        public Set<Entry<String, ActorRef<LuceneSearcher>>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, ActorRef<LuceneSearcher>>> iterator() {
                    return snapshot().entrySet().iterator();
                }

                @Override
                public int size() {
                    return snapshot().size();
                }
            };
        }

        /** The children as they stand, in order; a child closed meanwhile is left out. */
        private Map<String, ActorRef<LuceneSearcher>> snapshot() {
            Map<String, ActorRef<LuceneSearcher>> m = new LinkedHashMap<>();
            for (String k : root.getNamesOfChildren()) {
                ActorRef<LuceneSearcher> r = system.getActor(k);
                if (r != null) m.put(k, r);
            }
            return java.util.Collections.unmodifiableMap(m);
        }
    }
}
