package com.scivicslab.htmlsaurus;

import java.util.Optional;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.action.ActionResult;

/**
 * What other nodes may ask this portal: search, semantic search, related documents, a document's reference edges,
 * and a served document's Markdown (ActorNodesForWorkspaceAndPortal_261118_oo01, {@code html-saurus-remote}). Read
 * actions only. Holds no state.
 *
 * <p>Each action takes one JSON object; a search answers the JSON array the HTTP API answers. Each call is written to
 * the application log, one line (decision 8).</p>
 */
final class PortalGateway {

    private static final Logger LOG = Logger.getLogger(PortalGateway.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PortalReads portal;

    PortalGateway(PortalReads portal) {
        this.portal = portal;
    }

    /** {@code {"q": text}} -> full-text hits. */
    ActionResult search(String args) {
        return answer("search", () -> Optional.of(portal.searchJson(text(args, "q"))));
    }

    /** {@code {"q": text}} -> semantic hits. */
    ActionResult searchSemantic(String args) {
        return answer("searchSemantic", () -> Optional.of(portal.searchSemanticJson(text(args, "q"))));
    }

    /** {@code {"text": text}} -> documents like it. */
    ActionResult findRelated(String args) {
        return answer("findRelated", () -> Optional.of(portal.findRelatedJson(text(args, "text"))));
    }

    /** {@code {"id": docId, "direction": "forward" | "backward"}} -> the document's reference edges. */
    ActionResult references(String args) {
        return answer("references", () -> {
            JsonNode a = parse(args);
            String direction = a.path("direction").asText("forward");
            if (!direction.equals("forward") && !direction.equals("backward")) {
                throw new IllegalArgumentException("direction must be forward or backward: " + direction);
            }
            return portal.referencesJson(a.path("id").asText(""), direction.equals("forward"));
        });
    }

    /** {@code {"path": "<project>/docs/..."}} -> that document's Markdown, as a JSON string. */
    ActionResult readDocument(String args) {
        return answer("readDocument", () -> {
            Optional<String> md = portal.readServedDocument(text(args, "path"));
            return md.map(m -> {
                try {
                    return JSON.writeValueAsString(m);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        });
    }

    private static JsonNode parse(String args) throws Exception {
        return args == null || args.isBlank() ? JSON.createObjectNode() : JSON.readTree(args);
    }

    private static String text(String args, String field) throws Exception {
        return parse(args).path(field).asText("");
    }

    /** Runs one action and writes its log line; an empty answer is "not found". */
    private ActionResult answer(String action, Answer work) {
        long start = System.nanoTime();
        try {
            Optional<String> json = work.get();
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (json.isEmpty()) {
                LOG.info("remote call " + action + " not found in " + ms + " ms");
                return new ActionResult(false, action + ": not found");
            }
            LOG.info("remote call " + action + " ok in " + ms + " ms");
            return new ActionResult(true, json.get());
        } catch (Exception e) {
            LOG.warning("remote call " + action + " failed in " + (System.nanoTime() - start) / 1_000_000 + " ms: " + e);
            return new ActionResult(false, action + " failed: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface Answer {
        Optional<String> get() throws Exception;
    }
}
