package com.scivicslab.htmlsaurus;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;
import com.scivicslab.pojoactor.distributed.NodeInfo;

/**
 * Tells the AI-workspace that runs this portal that the portal is a node: its name, its kind, its actor port and its
 * HTTP port, with {@code announceNode} on {@value #WORKSPACE_GATEWAY} (ActorNodesForWorkspaceAndPortal_261118_oo01,
 * question 11). The AI-workspace is the one named in the environment variable {@value #ENV}; a portal started without
 * it announces nothing.
 *
 * <p>The AI-workspace takes an announcement only from an instance it sees READY, and it may see this portal's HTTP port
 * open a little after the portal opened it. So a refused announcement, or one that did not arrive, is sent again after
 * {@link #RETRY_MILLIS}; an accepted one, after {@link #REANNOUNCE_MILLIS}, which brings it back within a minute after
 * the AI-workspace restarts. Nothing is recorded.</p>
 *
 * <p>{@link #tick} runs as a message to this object's own actor, so its fields are read and written by one thread at a
 * time. That actor is not on the node's allow-list, so other nodes cannot call it.</p>
 */
final class WorkspaceAnnouncer {

    /** The environment variable AI-workspace sets for every tool it launches: {@code <node>@<host>:<actor port>}. */
    static final String ENV = "AI_WORKSPACE_NODE";
    /** The AI-workspace's gateway actor. */
    static final String WORKSPACE_GATEWAY = "ai-workspace-remote";
    /** This node's kind, as the node list shows it. */
    static final String KIND = "html-saurus";
    /** The wait before sending again an announcement that was refused or did not arrive. */
    static final long RETRY_MILLIS = 5_000;
    /** The wait before sending again an announcement that was accepted. */
    static final long REANNOUNCE_MILLIS = 60_000;
    /** How long one announcement may wait for its answer. */
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** This object's actor name in the portal's actor system. */
    static final String ACTOR = "ai-workspace-announcer";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ADDRESS = Pattern.compile("([A-Za-z0-9._-]+)@([^:@\\s]+):(\\d{1,5})");

    private final DistributedIIActorSystem portal;
    private final NodeInfo workspace;
    private final String announcement;
    private final LongSupplier clockMillis;

    private boolean sentOnce;
    private long lastSent;
    private boolean accepted;
    private String lastOutcome = "";

    /**
     * @param portal      the portal's actor system, already a node
     * @param workspace   the AI-workspace's node, from {@link #parse}
     * @param httpPort    the portal's HTTP port: the AI-workspace finds the instance by it
     * @param clockMillis the time now, in milliseconds
     */
    WorkspaceAnnouncer(DistributedIIActorSystem portal, NodeInfo workspace, int httpPort, LongSupplier clockMillis) {
        this.portal = portal;
        this.workspace = workspace;
        this.clockMillis = clockMillis;
        NodeInfo me = portal.getMyNode();
        this.announcement = JSON.createObjectNode().put("name", me.getNodeId()).put("kind", KIND)
                .put("actorPort", me.getPort()).put("httpPort", httpPort).toString();
        portal.registerRemoteNode(workspace.getNodeId(), workspace.getHost(), workspace.getPort());
    }

    /** @return the AI-workspace's node in {@code <node>@<host>:<actor port>}, or empty when there is none or it is not that form */
    static Optional<NodeInfo> parse(String aiWorkspaceNode) {
        if (aiWorkspaceNode == null) return Optional.empty();
        Matcher m = ADDRESS.matcher(aiWorkspaceNode.strip());
        if (!m.matches()) return Optional.empty();
        int port = Integer.parseInt(m.group(3));
        if (port < 1 || port > 65535) return Optional.empty();
        return Optional.of(new NodeInfo(m.group(1), m.group(2), port));
    }

    /**
     * Announces this portal if it is time to: at the first call, {@link #RETRY_MILLIS} after a refusal or a failure,
     * and {@link #REANNOUNCE_MILLIS} after an acceptance. Never throws.
     *
     * @return whether it sent the announcement
     */
    boolean tick() {
        long now = clockMillis.getAsLong();
        if (sentOnce && now - lastSent < (accepted ? REANNOUNCE_MILLIS : RETRY_MILLIS)) return false;
        sentOnce = true;
        lastSent = now;
        String outcome;
        try {
            ActionResult r = portal.getRemoteActor(workspace.getNodeId(), WORKSPACE_GATEWAY)
                    .callByActionName("announceNode", announcement, TIMEOUT);
            JsonNode answer = r.isSuccess() ? JSON.readTree(r.getResult()) : null;
            accepted = answer != null && answer.path("accepted").asBoolean(false);
            outcome = accepted ? "accepted"
                    : "refused: " + (answer != null ? answer.path("reason").asText("") : r.getResult());
        } catch (Exception e) {
            accepted = false;
            outcome = "not delivered: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        // One line each time the outcome changes, not one a minute.
        if (!outcome.equals(lastOutcome)) {
            System.out.println("Announcement to " + workspace.getNodeId() + " " + outcome);
            lastOutcome = outcome;
        }
        return true;
    }

    /** @return whether the last announcement was accepted */
    boolean accepted() {
        return accepted;
    }

    /**
     * Starts announcing {@code portal} to the AI-workspace named in {@value #ENV}, now and then on schedule.
     *
     * @return the scheduler to close when the portal stops, or empty when no AI-workspace runs this portal
     */
    static Optional<Scheduler> start(DistributedIIActorSystem portal, String aiWorkspaceNode, int httpPort) {
        Optional<NodeInfo> workspace = parse(aiWorkspaceNode);
        if (workspace.isEmpty()) return Optional.empty();
        WorkspaceAnnouncer announcer = new WorkspaceAnnouncer(portal, workspace.get(), httpPort, System::currentTimeMillis);
        ActorRef<WorkspaceAnnouncer> self = portal.actorOf(ACTOR, announcer);
        Scheduler scheduler = new Scheduler(1);
        // Ticks every RETRY_MILLIS; tick itself decides whether it is time to send.
        scheduler.scheduleWithFixedDelay(ACTOR, self, WorkspaceAnnouncer::tick, 0, RETRY_MILLIS, TimeUnit.MILLISECONDS);
        return Optional.of(scheduler);
    }
}
