package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.util.Optional;

/**
 * The reads another node may ask this portal for (ActorNodesForWorkspaceAndPortal_261118_oo01). {@link PortalServer}
 * answers them from the same methods its HTTP API uses, so the two cannot answer differently. Each answer is the JSON
 * array the matching HTTP endpoint sends.
 */
interface PortalReads {

    /** As {@code GET /api/search?q=}. */
    String searchJson(String q);

    /** As {@code GET /api/search-semantic?q=}. */
    String searchSemanticJson(String q);

    /** As {@code POST /api/find-related} with {@code text} as the body. */
    String findRelatedJson(String text);

    /** As {@code GET /api/prerequisites?id=} ({@code forward}) or {@code /api/prerequisite-of?id=}; empty when {@code id} is no document. */
    Optional<String> referencesJson(String id, boolean forward);

    /** The Markdown of a document this portal serves; empty for any other path. */
    Optional<String> readServedDocument(String path) throws IOException;
}
