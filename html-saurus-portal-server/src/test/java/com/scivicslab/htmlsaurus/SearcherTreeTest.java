package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * The portal's searchers are held in one place: the children of the actor {@code searchers}
 * (ActorNodesForWorkspaceAndPortal_261118_oo01, step 5a). The map every reader uses is a read-only view of that
 * tree, not a second registry of the same actors (TwoRegistriesForOneActorPopulation_260914_oo01).
 */
class SearcherTreeTest {

    @TempDir
    Path dir;

    private ActorSystem system;
    private SearcherTree tree;

    @BeforeEach
    void setUp() {
        system = new ActorSystem("searcher-tree-test");
        tree = new SearcherTree(system);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private LuceneSearcher searcher() {
        return new LuceneSearcher(dir.resolve("index"), "ja");
    }

    @Test
    void eachSearcherIsAChildOfSearchersAndTheViewListsThemInOrder() {
        ActorRef<LuceneSearcher> a = tree.put("doc_A", searcher());
        ActorRef<LuceneSearcher> b = tree.put("doc_A:en", searcher());
        ActorRef<LuceneSearcher> c = tree.put("doc_B", searcher());

        assertEquals(SearcherTree.ROOT, a.getParentName());
        assertEquals(List.of("doc_A", "doc_A:en", "doc_B"), List.copyOf(system.getActor(SearcherTree.ROOT).getNamesOfChildren()));
        Map<String, ActorRef<LuceneSearcher>> view = tree.view();
        assertEquals(List.of("doc_A", "doc_A:en", "doc_B"), List.copyOf(view.keySet()));
        assertSame(b, view.get("doc_A:en"));
        assertSame(c, view.values().stream().toList().get(2));
        assertTrue(view.containsKey("doc_B"));
        assertNull(view.get("doc_C"));
    }

    @Test
    void closingASearcherRemovesItsActorFromTheTreeAndTheSystem() {
        tree.put("doc_A", searcher());
        ActorRef<LuceneSearcher> gone = tree.put("doc_B", searcher());

        tree.close("doc_B");

        assertEquals(List.of("doc_A"), List.copyOf(tree.view().keySet()));
        assertFalse(system.hasActor("doc_B"));
        assertFalse(gone.isAlive());
    }

    @Test
    void puttingAKeyThatIsThereClosesTheOldSearcher() {
        ActorRef<LuceneSearcher> old = tree.put("doc_A", searcher());
        ActorRef<LuceneSearcher> now = tree.put("doc_A", searcher());

        assertFalse(old.isAlive());
        assertSame(now, tree.view().get("doc_A"));
        assertEquals(1, tree.view().size());
    }

    @Test
    void theViewCannotBeWrittenThrough() {
        tree.put("doc_A", searcher());
        assertThrows(UnsupportedOperationException.class, () -> tree.view().remove("doc_A"));
        assertThrows(UnsupportedOperationException.class, () -> tree.view().put("doc_B", null));
    }

    @Test
    void theSearchersActorHoldsNoReferences() {
        assertEquals(0, Searchers.class.getDeclaredFields().length,
                "the parent of the searcher actors keeps no map of them: the tree is the map");
    }
}
