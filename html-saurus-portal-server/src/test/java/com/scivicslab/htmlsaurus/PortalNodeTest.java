package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;
import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.discovery.StaticNodeDiscovery;
import com.sun.net.httpserver.HttpServer;

/**
 * The portal's own actor system is a node of the distributed actor system, and other nodes may call only the five
 * read actions of {@code html-saurus-remote}, which answer what the HTTP API answers
 * (ActorNodesForWorkspaceAndPortal_261118_oo01, step 5b; decisions 1b, 2, 6).
 */
class PortalNodeTest {

    @TempDir
    Path works;

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer http;
    private DistributedIIActorSystem caller;
    private int actorPort;
    private String node;

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @BeforeEach
    void setUp() throws Exception {
        Path proj = works.resolve("proj");
        Files.createDirectories(proj.resolve("docs"));
        Files.writeString(proj.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(proj.resolve("docs/actors.md"), """
                ---
                id: ActorTree_261118_oo01
                title: Actor tree
                ---

                # Actor tree

                Every searcher is a child of the searchers actor in the portal.

                ## 参考文献

                <ul>
                <li><span data-relation="prerequisite" data-doc-id="Mailbox_261118_oo01">messages first</span></li>
                </ul>
                """);
        Files.writeString(proj.resolve("docs/mailbox.md"), """
                ---
                id: Mailbox_261118_oo01
                title: Mailbox
                ---

                # Mailbox

                An actor handles one message at a time from its mailbox.
                """);
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), false);
        BuildStages.reindex(proj.resolve("docs"), proj.resolve("search-index"));

        int httpPort = freePort();
        actorPort = freePort();
        System.setProperty("html-saurus.actor-port", Integer.toString(actorPort));
        node = PortalNode.name(httpPort);
        PortalServer ps = new PortalServer(works, List.of(proj), httpPort, null, 0);
        http = ps.start();

        caller = DistributedIIActorSystem.builder()
                .discovery(new StaticNodeDiscovery(new NodeInfo("caller", "127.0.0.1", freePort()), List.of()))
                .noDeduplication().build();
        caller.registerRemoteNode(node, "127.0.0.1", actorPort);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("html-saurus.actor-port");
        if (caller != null) caller.close();
        if (http != null) http.stop(0);
    }

    private ActionResult call(String actor, String action, String args) {
        return caller.getRemoteActor(node, actor).callByActionName(action, args, Duration.ofSeconds(10));
    }

    private String httpGet(String pathAndQuery) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + http.getAddress().getPort() + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void searchAnswersWhatGetApiSearchAnswers() throws Exception {
        ActionResult r = call(PortalNode.GATEWAY, "search", "{\"q\":\"mailbox\"}");
        assertTrue(r.isSuccess(), r.getResult());
        assertEquals(JSON.readTree(httpGet("/api/search?q=" + q("mailbox"))), JSON.readTree(r.getResult()));
        assertTrue(JSON.readTree(r.getResult()).size() > 0, "the indexed document is found: " + r.getResult());
    }

    @Test
    void referencesAnswerWhatThePrerequisiteEndpointsAnswer() throws Exception {
        ActionResult forward = call(PortalNode.GATEWAY, "references", "{\"id\":\"ActorTree_261118_oo01\",\"direction\":\"forward\"}");
        assertTrue(forward.isSuccess(), forward.getResult());
        assertEquals(JSON.readTree(httpGet("/api/prerequisites?id=ActorTree_261118_oo01")), JSON.readTree(forward.getResult()));
        assertEquals("Mailbox_261118_oo01", JSON.readTree(forward.getResult()).get(0).get("id").asText());

        ActionResult backward = call(PortalNode.GATEWAY, "references", "{\"id\":\"Mailbox_261118_oo01\",\"direction\":\"backward\"}");
        assertTrue(backward.isSuccess(), backward.getResult());
        assertEquals(JSON.readTree(httpGet("/api/prerequisite-of?id=Mailbox_261118_oo01")), JSON.readTree(backward.getResult()));

        assertFalse(call(PortalNode.GATEWAY, "references", "{\"id\":\"NoSuchDoc_000000_oo01\"}").isSuccess());
    }

    @Test
    void searchSemanticAndFindRelatedAnswerWhatTheHttpApiAnswers() throws Exception {
        ActionResult semantic = call(PortalNode.GATEWAY, "searchSemantic", "{\"q\":\"mailbox\"}");
        assertTrue(semantic.isSuccess(), semantic.getResult());
        assertEquals(JSON.readTree(httpGet("/api/search-semantic?q=" + q("mailbox"))), JSON.readTree(semantic.getResult()));

        ActionResult related = call(PortalNode.GATEWAY, "findRelated", "{\"text\":\"one message at a time from its mailbox\"}");
        assertTrue(related.isSuccess(), related.getResult());
        String body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + http.getAddress().getPort() + "/api/find-related"))
                .POST(HttpRequest.BodyPublishers.ofString("one message at a time from its mailbox")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertEquals(JSON.readTree(body), JSON.readTree(related.getResult()));
    }

    @Test
    void readDocumentAnswersAServedDocumentAndNothingElse() throws Exception {
        ActionResult md = call(PortalNode.GATEWAY, "readDocument", "{\"path\":\"proj/docs/mailbox.md\"}");
        assertTrue(md.isSuccess(), md.getResult());
        assertEquals(Files.readString(works.resolve("proj/docs/mailbox.md")), JSON.readValue(md.getResult(), String.class));

        Files.writeString(works.resolve("secret.md"), "not served");
        for (String path : List.of("secret.md", "proj/docusaurus.config.js", "proj/docs/../../secret.md", "proj/docs/none.md")) {
            assertFalse(call(PortalNode.GATEWAY, "readDocument", "{\"path\":\"" + path + "\"}").isSuccess(), path);
        }
    }

    @Test
    void theRestOfTheTreeIsRefusedEvenByName() {
        assertFalse(call("searchers", "x", "{}").isSuccess());
        assertFalse(call("proj", "search", "{}").isSuccess(), "a searcher actor");
        assertFalse(call(PortalNode.GATEWAY, "rebuild", "{}").isSuccess(), "an action not on the allow-list");
    }

    @Test
    void theReceiverListensOnLoopbackOnly() throws Exception {
        Optional<InetAddress> outside = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .filter(i -> { try { return i.isUp() && !i.isLoopback(); } catch (IOException e) { return false; } })
                .flatMap(i -> Collections.list(i.getInetAddresses()).stream())
                .filter(a -> a instanceof Inet4Address && !a.isLoopbackAddress()).findFirst();
        Assumptions.assumeTrue(outside.isPresent(), "no non-loopback address on this machine");
        boolean connected;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(outside.get(), actorPort), 1000);
            connected = true;
        } catch (IOException e) {
            connected = false;
        }
        assertFalse(connected, "reachable on " + outside.get());
    }

    @Test
    void theNameAndActorPortFollowTheHttpPort() {
        System.clearProperty("html-saurus.actor-port");
        assertEquals("html-saurus-28001", PortalNode.name(28001));
        assertEquals(29001, PortalNode.actorPort(28001));
        assertEquals(0, PortalNode.actorPort(0), "an ephemeral HTTP port has no actor port");
    }
}
