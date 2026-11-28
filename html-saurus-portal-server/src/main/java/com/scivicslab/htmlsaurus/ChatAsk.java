package com.scivicslab.htmlsaurus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;
import com.scivicslab.pojoactor.distributed.Receipt;
import com.scivicslab.pojoactor.distributed.RemoteActorRef;
import com.scivicslab.pojoactor.core.ActorRef;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Asks a conversation of a chat-ui instance, as chat-ui instances ask one another ({@code RevisionPanel_261120_oo01},
 * {@code RepliesToToolNodes_261120_oo01}): a request to that node's gateway {@value #CHAT_GATEWAY}
 * ({@code enqueueRequest}), and the reply back at this portal's own gateway of the same name ({@code enqueueReply}).
 *
 * <p>The chat-ui nodes are read from the AI-workspace's {@code GET /api/nodes} (kind {@code chat-ui}), or given by
 * hand in {@code -Dhtml-saurus.chat-ui-nodes=name=host:port,...}.</p>
 */
final class ChatAsk {

    private static final Logger LOG = Logger.getLogger(ChatAsk.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The gateway chat-ui instances take requests at, and send replies to. */
    static final String CHAT_GATEWAY = "chat-ui-remote";
    /** The name the requests are sent under; chat-ui heads the request with it. */
    static final String SENDER = "html-saurus/revise";

    /**
     * Where a request stands.
     *
     * @param answered whether the reply has come
     * @param reply    the conversation's answer alone, or {@code null} when it gave none
     * @param text     the whole message chat-ui sent, its heading and ending included
     */
    record Answer(boolean answered, String reply, String text) {}

    private final DistributedIIActorSystem system;
    private final String workspaceUrl;
    private final String byHand;
    private final ActorRef<ChatAskLedger> ledger;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * @param system       the portal's node
     * @param workspaceUrl the AI-workspace's base URL, or {@code null} when the portal was not started by one
     * @param byHand       {@code name=host:port,...}, or {@code null}
     */
    ChatAsk(DistributedIIActorSystem system, String workspaceUrl, String byHand) {
        this.system = system;
        this.workspaceUrl = workspaceUrl == null || workspaceUrl.isBlank() ? null : workspaceUrl.replaceAll("/+$", "");
        this.byHand = byHand;
        this.ledger = ChatAskLedger.host(system);
    }

    /** @return the names of the chat-ui nodes known now, registering each with the portal's node */
    List<String> chatNodes() {
        List<String> names = new ArrayList<>();
        if (byHand != null) {
            for (String entry : byHand.split(",")) {
                String[] nameAndAddress = entry.strip().split("=", 2);
                if (nameAndAddress.length != 2) continue;
                String[] hostAndPort = nameAndAddress[1].split(":", 2);
                if (hostAndPort.length != 2) continue;
                register(nameAndAddress[0].strip(), hostAndPort[0].strip(), Integer.parseInt(hostAndPort[1].strip()), names);
            }
        }
        if (workspaceUrl != null) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(workspaceUrl + "/api/nodes"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    for (JsonNode n : JSON.readTree(r.body())) {
                        if ("chat-ui".equals(n.path("kind").asText())) {
                            register(n.path("name").asText(), n.path("host").asText("127.0.0.1"), n.path("actorPort").asInt(), names);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.warning("The AI-workspace's node list could not be read at " + workspaceUrl + ": " + e.getMessage());
            }
        }
        return names;
    }

    private void register(String name, String host, int port, List<String> names) {
        if (name.isEmpty() || port <= 0 || names.contains(name)) return;
        system.registerRemoteNode(name, host, port);
        names.add(name);
    }

    /**
     * Sends {@code prompt} to the conversation {@code target} of the chat-ui node {@code node}.
     *
     * @param target the conversation, {@code project1/chat-01}; {@code project1/01} is taken as the same
     * @return the request's id, under which {@link #answer} finds the reply
     * @throws IOException when the node is not known or did not take the request
     */
    String ask(String node, String target, String prompt) throws IOException {
        if (!chatNodes().contains(node)) throw new IOException("no chat-ui node " + node + " is known");
        String id = ledger.ask(l -> l.open(System.currentTimeMillis())).join();
        String body = JSON.createObjectNode().put("requestId", id).put("sender", SENDER)
                .put("senderNode", system.getMyNode().getNodeId()).put("target", conversationName(target))
                .put("prompt", prompt).put("urgent", false).toString();
        RemoteActorRef gateway = system.getRemoteActor(node, CHAT_GATEWAY);
        Receipt receipt = gateway.sendWithRetry("enqueueRequest", body, 3, Duration.ofSeconds(1));
        if (!receipt.accepted()) {
            ledger.tell(l -> l.withdraw(id));
            throw new IOException("node " + node + " did not take the request: " + receipt.detail());
        }
        return id;
    }

    /** @return the conversation's qualified name: {@code project1/01} becomes {@code project1/chat-01} */
    static String conversationName(String target) {
        String t = target == null ? "" : target.strip();
        return t.matches(".+/\\d+") ? t.replaceFirst("/(\\d+)$", "/chat-$1") : t;
    }

    /** @return where the request {@code id} stands, or {@code null} when this portal sent no such request */
    Answer answer(String id) {
        return ledger.ask(l -> l.answer(id)).join();
    }

    /** Takes a reply chat-ui sent to a request of this portal. */
    ActionResult enqueueReply(String args) {
        try {
            JsonNode m = JSON.readTree(args);
            String id = m.path("requestId").asText("");
            String reply = m.hasNonNull("reply") ? m.get("reply").asText() : null;
            String text = m.path("text").asText("");
            if (!ledger.ask(l -> l.accept(id, reply, text)).join()) {
                return new ActionResult(false, "no request " + id + " was sent from here");
            }
            return new ActionResult(true, "reply to " + id + " taken");
        } catch (IOException e) {
            return new ActionResult(false, "unreadable reply: " + e.getMessage());
        }
    }

    /** Takes the result of a cancel; the portal sends none, so it only answers. */
    ActionResult enqueueCancelResult(String args) {
        return new ActionResult(true, "nothing to do");
    }
}
