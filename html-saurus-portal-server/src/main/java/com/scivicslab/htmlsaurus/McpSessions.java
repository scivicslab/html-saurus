package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * The MCP sessions this portal has opened ({@link McpHandler}): a counter that numbers them and the id of the newest.
 *
 * <p>A plain POJO, hosted as an actor ({@link #host}) so that the request threads opening a session and the ones
 * answering never share it. It is a leaf: it asks no actor and waits on none; a test pins this. The newest session is
 * the one every response names in {@code Mcp-Session-Id}; the map this replaces gave an arbitrary one of them.</p>
 */
final class McpSessions {

    /** The name of the actor in the portal's {@link ActorSystem}. */
    static final String ACTOR = "mcp-sessions";

    private long counter;
    private String newest;

    /** Opens a session. @return its id, {@code htmlsaurus-<n>} */
    String open() {
        newest = "htmlsaurus-" + (++counter);
        return newest;
    }

    /** @return the id of the session opened last, or {@code null} when none was opened */
    String newest() {
        return newest;
    }

    /** Puts one record of sessions into {@code system} as the actor named {@link #ACTOR}. */
    static ActorRef<McpSessions> host(ActorSystem system) {
        return system.actorOf(ACTOR, new McpSessions());
    }
}
