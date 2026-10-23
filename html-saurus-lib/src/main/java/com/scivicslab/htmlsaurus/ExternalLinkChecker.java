package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asks every external site a built site links to whether the page is still there.
 *
 * <p>The cost of the check is waiting on other people's servers, so it is not part of a build. It
 * is run on its own, and it is made quick by running hosts side by side while each host is asked
 * one thing at a time: one {@link HostWorker} actor per host. An actor's mailbox is first in, first
 * out, so the requests to one host line up behind each other with no lock written anywhere, and
 * the worker leaves a pause between them so a site is not hammered.</p>
 */
public final class ExternalLinkChecker {

    private ExternalLinkChecker() {}

    /** What was found when one URL was asked about. */
    public enum Kind {
        /** The page answered. */
        OK,
        /** The page answered, but at another address the site announced as permanent (301, 308). */
        MOVED,
        /** The site says the page is gone (404, 410). */
        BROKEN,
        /** No clear answer: refusals, rate limits, server errors, timeouts, an unknown host. */
        UNCERTAIN
    }

    /**
     * @param kind   what was found
     * @param status the last HTTP status, or 0 when there was none
     * @param detail the address a moved page now has, or the reason for no clear answer
     */
    public record Outcome(Kind kind, int status, String detail) {}

    /** One URL, the pages that link to it, and what was found. */
    public record Result(String url, List<String> pages, Outcome outcome) {}

    /** Asks one URL; knows nothing of hosts, pauses or threads. */
    public interface Probe {
        Outcome probe(String url);
    }

    /**
     * Checks the URLs, hosts in parallel and one request at a time per host.
     *
     * @param pagesByUrl  each URL, with the pages that link to it
     * @param probe       how one URL is asked
     * @param intervalMs  the least time between the starts of two requests to one host
     * @return the results ordered by URL
     */
    public static List<Result> check(Map<String, List<String>> pagesByUrl, Probe probe, long intervalMs) {
        Map<String, List<String>> urlsByHost = new LinkedHashMap<>();
        for (String url : pagesByUrl.keySet()) {
            urlsByHost.computeIfAbsent(hostOf(url), h -> new ArrayList<>()).add(url);
        }
        ActorSystem system = new ActorSystem("external-links");
        try {
            List<CompletableFuture<Result>> pending = new ArrayList<>();
            int n = 0;
            for (Map.Entry<String, List<String>> host : urlsByHost.entrySet()) {
                ActorRef<HostWorker> worker = system.actorOf("host-" + (n++), new HostWorker(probe, intervalMs));
                for (String url : host.getValue()) {
                    pending.add(worker.ask(w -> w.check(url))
                            .thenApply(outcome -> new Result(url, pagesByUrl.get(url), outcome)));
                }
            }
            List<Result> results = new ArrayList<>();
            for (CompletableFuture<Result> f : pending) results.add(f.join());
            results.sort(Comparator.comparing(Result::url));
            return results;
        } finally {
            system.terminate();
        }
    }

    /** The host a URL names, lower-cased; an unreadable URL gets a group of its own. */
    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
