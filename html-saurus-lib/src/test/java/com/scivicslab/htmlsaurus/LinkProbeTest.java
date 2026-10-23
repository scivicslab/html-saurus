package com.scivicslab.htmlsaurus;

import com.scivicslab.htmlsaurus.ExternalLinkChecker.Kind;
import com.scivicslab.htmlsaurus.ExternalLinkChecker.Outcome;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

/** What a single URL's answer means: there, moved, gone, or no clear answer. */
@Tag("S1")
class LinkProbeTest {

    private HttpServer server;
    private String base;
    private final LinkProbe probe = new LinkProbe();

    private void answer(String path, int status, String location) {
        server.createContext(path, ex -> {
            if (location != null) ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        answer("/ok", 200, null);
        answer("/gone", 404, null);
        answer("/deleted", 410, null);
        answer("/moved", 301, "/ok");
        answer("/moved-for-now", 302, "/ok");
        answer("/forbidden", 403, null);
        answer("/limited", 429, null);
        answer("/error", 500, null);
        answer("/loop", 302, "/loop");
        answer("/no-location", 302, null);
        server.createContext("/head-refused", ex -> {
            ex.sendResponseHeaders("HEAD".equals(ex.getRequestMethod()) ? 405 : 200, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aPageThatAnswersIsOk() {
        assertEquals(Kind.OK, probe.probe(base + "/ok").kind());
    }

    @Test
    void notFoundAndGoneAreBroken() {
        assertEquals(Kind.BROKEN, probe.probe(base + "/gone").kind());
        assertEquals(404, probe.probe(base + "/gone").status());
        assertEquals(Kind.BROKEN, probe.probe(base + "/deleted").kind());
    }

    @Test
    void aPermanentRedirectIsMovedAndNamesTheNewAddress() {
        Outcome o = probe.probe(base + "/moved");
        assertEquals(Kind.MOVED, o.kind());
        assertEquals(base + "/ok", o.detail());
    }

    @Test
    void aTemporaryRedirectIsFollowedAndIsNotAFinding() {
        assertEquals(Kind.OK, probe.probe(base + "/moved-for-now").kind());
    }

    @Test
    void aServerThatRefusesHeadIsAskedAgainWithGet() {
        assertEquals(Kind.OK, probe.probe(base + "/head-refused").kind());
    }

    @Test
    void refusalsRateLimitsAndServerErrorsAreNotClearAnswers() {
        for (String path : new String[] {"/forbidden", "/limited", "/error"}) {
            assertEquals(Kind.UNCERTAIN, probe.probe(base + path).kind(), path);
        }
    }

    @Test
    void aRedirectLoopAndARedirectWithNoTargetAreNotClearAnswers() {
        assertEquals(Kind.UNCERTAIN, probe.probe(base + "/loop").kind());
        assertEquals(Kind.UNCERTAIN, probe.probe(base + "/no-location").kind());
    }

    @Test
    void aHostThatCannotBeReachedIsNotClearEither() {
        Outcome o = probe.probe("http://127.0.0.1:1/x");
        assertEquals(Kind.UNCERTAIN, o.kind());
        assertEquals(0, o.status());
    }
}
