package com.scivicslab.htmlsaurus;

import com.scivicslab.htmlsaurus.ExternalLinkChecker.Kind;
import com.scivicslab.htmlsaurus.ExternalLinkChecker.Outcome;
import com.scivicslab.htmlsaurus.ExternalLinkChecker.Result;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * External links are checked with hosts side by side and one request at a time per host, with a
 * pause between the requests to one host. These tests drive the checker with a probe that only
 * sleeps and records when it was called, so the order and the overlap can be read off.
 */
@Tag("S1")
class ExternalLinkCheckerTest {

    private static Map<String, List<String>> urls(String... urls) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String u : urls) m.put(u, List.of("/page/"));
        return m;
    }

    @Test
    void everyUrlGetsItsOwnResultCarryingItsPages() {
        Map<String, List<String>> in = new LinkedHashMap<>();
        in.put("https://a.example/1", List.of("/p/", "/q/"));
        in.put("https://b.example/2", List.of("/r/"));
        List<Result> out = ExternalLinkChecker.check(in,
                url -> new Outcome(Kind.OK, 200, ""), 0);
        assertEquals(2, out.size());
        assertEquals("https://a.example/1", out.get(0).url());
        assertEquals(List.of("/p/", "/q/"), out.get(0).pages());
        assertEquals(List.of("/r/"), out.get(1).pages());
    }

    @Test
    void oneHostIsAskedOneThingAtATimeAndDifferentHostsOverlap() {
        AtomicInteger inFlightOnA = new AtomicInteger();
        AtomicInteger worstOnA = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger worstOverall = new AtomicInteger();
        ExternalLinkChecker.Probe probe = url -> {
            boolean hostA = url.contains("a.example");
            int now = inFlight.incrementAndGet();
            worstOverall.accumulateAndGet(now, Math::max);
            if (hostA) worstOnA.accumulateAndGet(inFlightOnA.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (hostA) inFlightOnA.decrementAndGet();
            inFlight.decrementAndGet();
            return new Outcome(Kind.OK, 200, "");
        };
        long start = System.nanoTime();
        ExternalLinkChecker.check(urls("https://a.example/1", "https://a.example/2", "https://a.example/3",
                "https://b.example/1", "https://c.example/1"), probe, 0);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, worstOnA.get(), "two requests to one host were in flight at once");
        assertTrue(worstOverall.get() >= 2, "different hosts never overlapped");
        // Three in a row on host A take about 450 ms; had every request run in turn, 750 ms.
        assertTrue(ms < 700, "took " + ms + " ms");
    }

    @Test
    void requestsToOneHostStartAtLeastTheIntervalApart() {
        List<Long> starts = Collections.synchronizedList(new ArrayList<>());
        ExternalLinkChecker.check(urls("https://a.example/1", "https://a.example/2", "https://a.example/3"),
                url -> { starts.add(System.nanoTime()); return new Outcome(Kind.OK, 200, ""); }, 200);
        assertEquals(3, starts.size());
        for (int i = 1; i < starts.size(); i++) {
            long gapMs = (starts.get(i) - starts.get(i - 1)) / 1_000_000;
            assertTrue(gapMs >= 190, "gap " + i + " was " + gapMs + " ms");
        }
    }

    @Test
    void aFirstRequestToAHostIsNotMadeToWait() {
        long start = System.nanoTime();
        ExternalLinkChecker.check(urls("https://a.example/1"),
                url -> new Outcome(Kind.OK, 200, ""), 5000);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000);
    }

    @Test
    void anUnreadableUrlStillGetsAnAnswerFromTheProbe() {
        List<Result> out = ExternalLinkChecker.check(urls("http://[bad"),
                url -> new Outcome(Kind.UNCERTAIN, 0, "unreadable"), 0);
        assertEquals(Kind.UNCERTAIN, out.get(0).outcome().kind());
    }
}
