package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.util.List;
import java.util.Map;

/**
 * What the portal derives from its documents and keeps in memory: the semantic index with the related-document map
 * rendered from it, and the backward direction of the {@code ## 参考文献} relation ({@code PrerequisiteOf_260806_oo01}).
 *
 * <p>A plain POJO, hosted as an actor ({@link #host}) and a leaf: it asks no actor and waits on none, and it never builds
 * anything, because a build walks every document and must not hold up the requests that read the other index. A build
 * runs on the caller's thread and is handed over with {@link #installPrerequisiteOf}, which refuses it when
 * {@link #invalidatePrerequisiteOf} came in between: the walk read files that have since changed, and installing it
 * would keep the stale answer until the next invalidation. (The volatile field this replaces installed it.)</p>
 */
final class DerivedIndexes {

    /** The name of the actor in the portal's {@link ActorSystem}. */
    static final String ACTOR = "derived-indexes";

    private SemanticIndex semanticIndex;
    private Map<String, List<Map<String, String>>> semanticRelated = Map.of();
    private Map<String, List<Map<String, String>>> prerequisiteOf;
    private long prerequisiteOfGeneration;

    /** Replaces the semantic index and the related-document map rendered from it; either may be empty. */
    void installSemantic(SemanticIndex index, Map<String, List<Map<String, String>>> related) {
        this.semanticIndex = index;
        this.semanticRelated = related == null ? Map.of() : related;
    }

    /** @return the semantic index, or {@code null} when there is none */
    SemanticIndex semanticIndex() {
        return semanticIndex;
    }

    /** @return portal path to the related documents; empty when there is no semantic index */
    Map<String, List<Map<String, String>>> semanticRelated() {
        return semanticRelated;
    }

    /** @return the backward index, or {@code null} while none is built (or it was invalidated) */
    Map<String, List<Map<String, String>>> prerequisiteOf() {
        return prerequisiteOf;
    }

    /** @return the generation a build started now belongs to; hand it back to {@link #installPrerequisiteOf} */
    long prerequisiteOfGeneration() {
        return prerequisiteOfGeneration;
    }

    /** Drops the backward index, and refuses any build that started before this. */
    void invalidatePrerequisiteOf() {
        prerequisiteOf = null;
        prerequisiteOfGeneration++;
    }

    /**
     * Installs a build, unless the index was invalidated after the build began.
     *
     * @param generation what {@link #prerequisiteOfGeneration} said when the build began
     * @return whether the build was installed
     */
    boolean installPrerequisiteOf(Map<String, List<Map<String, String>>> built, long generation) {
        if (generation != prerequisiteOfGeneration) return false;
        prerequisiteOf = built;
        return true;
    }

    /** Puts one set of derived indexes into {@code system} as the actor named {@link #ACTOR}. */
    static ActorRef<DerivedIndexes> host(ActorSystem system) {
        return system.actorOf(ACTOR, new DerivedIndexes());
    }
}
