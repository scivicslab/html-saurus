package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.distributed.AllowListDispatcher.AllowedAction;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;
import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.discovery.StaticNodeDiscovery;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The portal announces itself to the AI-workspace named in {@code AI_WORKSPACE_NODE}, with {@code announceNode} on
 * {@code ai-workspace-remote}: every few seconds until it is accepted, then every 60 seconds
 * (ActorNodesForWorkspaceAndPortal_261118_oo01, step 5; question 11).
 *
 * <p>The AI-workspace is a stub: a real {@code DistributedIIActorSystem} on 127.0.0.1 whose gateway records what it is
 * told, and refuses until it is told to accept, as AI-workspace does while the instance is not yet READY.</p>
 */
class WorkspaceAnnouncerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the stub AI-workspace was told, and whether it accepts. */
    static final class StubWorkspace {
        final List<String> told = new ArrayList<>();
        boolean accepting;
        ActionResult announceNode(String args) {
            told.add(args);
            return new ActionResult(true, accepting ? "{\"accepted\":true}"
                    : "{\"accepted\":false,\"reason\":\"the instance on port 18400 is STARTING, not READY\"}");
        }
    }

    static final class StubGatewayIIAR extends IIActorRef<StubWorkspace> {
        StubGatewayIIAR(String name, StubWorkspace w, IIActorSystem system) { super(name, w, system); }
        @Action("announceNode")
        public ActionResult announceNode(String args) { return object.announceNode(args); }
    }

    private DistributedIIActorSystem workspace;
    private DistributedIIActorSystem portal;
    private StubWorkspace stub;
    private int workspacePort;
    private int portalActorPort;
    private final AtomicLong now = new AtomicLong(1_000_000);

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @BeforeEach
    void setUp() throws IOException {
        workspacePort = freePort();
        workspace = DistributedIIActorSystem.builder()
                .discovery(new StaticNodeDiscovery(new NodeInfo("ai-workspace-18300", "127.0.0.1", workspacePort), List.of()))
                .allowedActions(Set.of(new AllowedAction(WorkspaceAnnouncer.WORKSPACE_GATEWAY, "announceNode")))
                .noDeduplication().build();
        stub = new StubWorkspace();
        workspace.addIIActor(new StubGatewayIIAR(WorkspaceAnnouncer.WORKSPACE_GATEWAY, stub, workspace));
        workspace.startHttpServer("127.0.0.1", workspacePort);

        portalActorPort = freePort();
        portal = PortalNode.buildSystem("html-saurus-18400", portalActorPort);
    }

    @AfterEach
    void tearDown() {
        if (portal != null) portal.close();
        if (workspace != null) workspace.close();
    }

    private WorkspaceAnnouncer announcer() {
        NodeInfo ws = WorkspaceAnnouncer.parse("ai-workspace-18300@127.0.0.1:" + workspacePort).orElseThrow();
        return new WorkspaceAnnouncer(portal, ws, 18400, now::get);
    }

    @Test
    void theWorkspaceNodeIsReadFromAiWorkspaceNode() {
        NodeInfo ws = WorkspaceAnnouncer.parse("ai-workspace-28000@127.0.0.1:29000").orElseThrow();
        assertEquals("ai-workspace-28000", ws.getNodeId());
        assertEquals("127.0.0.1", ws.getHost());
        assertEquals(29000, ws.getPort());
        assertEquals(Optional.empty(), WorkspaceAnnouncer.parse(null), "not run by an AI-workspace");
        assertEquals(Optional.empty(), WorkspaceAnnouncer.parse(""));
        assertEquals(Optional.empty(), WorkspaceAnnouncer.parse("ai-workspace-28000"), "no address");
        assertEquals(Optional.empty(), WorkspaceAnnouncer.parse("ai-workspace-28000@127.0.0.1:port"));
    }

    @Test
    void itTellsItsNameKindAndBothPorts() throws Exception {
        stub.accepting = true;
        assertTrue(announcer().tick(), "sent");
        assertEquals(1, stub.told.size());
        JsonNode told = JSON.readTree(stub.told.get(0));
        assertEquals("html-saurus-18400", told.get("name").asText());
        assertEquals("html-saurus", told.get("kind").asText());
        assertEquals(portalActorPort, told.get("actorPort").asInt());
        assertEquals(18400, told.get("httpPort").asInt());
    }

    @Test
    void untilItIsAcceptedItTriesEveryFewSeconds_thenEverySixty() {
        WorkspaceAnnouncer a = announcer();
        assertTrue(a.tick(), "the first tick sends");
        assertFalse(a.accepted(), "refused: the instance is not READY yet");
        now.addAndGet(WorkspaceAnnouncer.RETRY_MILLIS);
        stub.accepting = true;
        assertTrue(a.tick(), "a refused announcement is sent again after a few seconds");
        assertTrue(a.accepted());
        now.addAndGet(WorkspaceAnnouncer.RETRY_MILLIS);
        assertFalse(a.tick(), "an accepted one is not sent again so soon");
        now.addAndGet(WorkspaceAnnouncer.REANNOUNCE_MILLIS);
        assertTrue(a.tick(), "sent again after 60 seconds");
        assertEquals(3, stub.told.size());
    }

    @Test
    void aWorkspaceThatIsDownIsTriedAgain_andNothingIsThrown() {
        workspace.close();
        workspace = null;
        WorkspaceAnnouncer a = announcer();
        assertTrue(a.tick());
        assertFalse(a.accepted());
        now.addAndGet(WorkspaceAnnouncer.RETRY_MILLIS);
        assertTrue(a.tick(), "tried again after a few seconds");
    }

    @Test
    void anAcceptedAnnouncementThatIsLaterRefusedIsTriedAgainSoon() {
        stub.accepting = true;
        WorkspaceAnnouncer a = announcer();
        a.tick();
        now.addAndGet(WorkspaceAnnouncer.REANNOUNCE_MILLIS);
        stub.accepting = false;
        assertTrue(a.tick());
        assertFalse(a.accepted(), "the AI-workspace restarted and has not adopted this instance yet");
        now.addAndGet(WorkspaceAnnouncer.RETRY_MILLIS);
        assertTrue(a.tick());
    }
}
