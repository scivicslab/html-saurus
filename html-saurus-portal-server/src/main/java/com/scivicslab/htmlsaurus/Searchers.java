package com.scivicslab.htmlsaurus;

/**
 * The parent of every searcher actor of the portal: one {@link LuceneSearcher} per project ({@code <project>}) and one
 * per locale sub-index ({@code <project>:<locale>}).
 *
 * <p>Holds nothing. Which searchers exist is the set of this actor's children, which the actor system already keeps;
 * a map of their references beside it would be a second registry of the same actors
 * ({@code TwoRegistriesForOneActorPopulation_260914_oo01}). {@link SearcherTree} reads and changes the children.</p>
 */
final class Searchers {
}
