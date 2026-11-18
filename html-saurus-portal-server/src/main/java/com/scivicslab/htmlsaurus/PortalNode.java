package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.scivicslab.pojoactor.distributed.AllowListDispatcher.AllowedAction;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;
import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.discovery.StaticNodeDiscovery;

/**
 * The portal's node of the distributed actor system (ActorNodesForWorkspaceAndPortal_261118_oo01). The portal's own
 * actor system, {@code searcher-system}, is the node; there is no second one (decisions 1, 1b).
 *
 * <p>Its name and actor port come from the portal's HTTP port: {@code html-saurus-<HTTP port>} and the HTTP port plus
 * 1000; {@code -Dhtml-saurus.node-name} and {@code -Dhtml-saurus.actor-port} override them. A portal on an
 * ephemeral port (0), as in tests, has no actor port and opens no receiver. Other nodes may call only the read
 * actions of {@link #ALLOWED}, on {@value #GATEWAY}; the searchers and the import jobs of the tree are refused even by
 * name.</p>
 */
final class PortalNode {

    /** The one actor other nodes may call. */
    static final String GATEWAY = "html-saurus-remote";
    /** The actions other nodes may call on {@link #GATEWAY}: reads only (decision 2). */
    static final List<String> ACTIONS = List.of("search", "searchSemantic", "findRelated", "references", "readDocument");
    /** The allow-list: {@link #GATEWAY} with each of {@link #ACTIONS}, and nothing else. */
    static final Set<AllowedAction> ALLOWED = ACTIONS.stream()
            .map(a -> new AllowedAction(GATEWAY, a)).collect(Collectors.toUnmodifiableSet());
    /** The receiver listens here only. */
    static final String BIND = "127.0.0.1";
    /** The actor port is the HTTP port plus this. */
    static final int ACTOR_PORT_OFFSET = 1000;

    private PortalNode() {}

    /** @return the node name for a portal on {@code httpPort} */
    static String name(int httpPort) {
        String given = System.getProperty("html-saurus.node-name");
        return given != null && !given.isBlank() ? given.strip() : "html-saurus-" + httpPort;
    }

    /** @return the actor port for a portal on {@code httpPort}, or 0 when there is none (an ephemeral HTTP port) */
    static int actorPort(int httpPort) {
        String given = System.getProperty("html-saurus.actor-port");
        if (given != null && !given.isBlank()) return Integer.parseInt(given.strip());
        int p = httpPort + ACTOR_PORT_OFFSET;
        return httpPort > 0 && p <= 65535 ? p : 0;
    }

    /** The portal's actor system, as the node {@code nodeName} on {@code actorPort}, with the allow-list. Nothing listens yet. */
    static DistributedIIActorSystem buildSystem(String nodeName, int actorPort) {
        return DistributedIIActorSystem.builder()
                .discovery(new StaticNodeDiscovery(new NodeInfo(nodeName, BIND, actorPort), List.of()))
                .allowedActions(ALLOWED)
                // Every allowed action is a read: a message delivered twice answers the same.
                .noDeduplication()
                .build();
    }

    /** Puts {@code gateway} at the root of {@code system} as {@value #GATEWAY} and opens the receiver on {@code bind}. */
    static void open(DistributedIIActorSystem system, PortalGateway gateway, String bind) throws IOException {
        system.addIIActor(new PortalGatewayIIAR(GATEWAY, gateway, system));
        system.startHttpServer(bind, system.getMyNode().getPort());
    }
}
